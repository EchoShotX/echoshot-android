package com.echoshot.app.utils

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaScanUtils {
    private const val TAG = "MediaScanUtils"
    private const val PREFS_NAME = "echoshot_media_scan"
    private const val KEY_LAST_SCAN_TIME = "last_scan_time"
    private const val KEY_FIRST_SCAN_DONE = "first_scan_done"
    
    /**
     * 저장공간 권한이 있는지 확인
     */
    fun hasStoragePermission(context: Context): Boolean {
        return when {
            // Android 13+ (API 33+)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val hasVideo = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
                val hasImages = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
                hasVideo && hasImages
            }
            // Android 10-12 (API 29-32)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10+에서는 scoped storage로 인해 자신이 생성한 파일에는 접근 가능
                // 하지만 MediaScannerConnection 사용 시 권한이 필요할 수 있음
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
            // Android 9 이하
            else -> {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
    
    /**
     * EchoShot 폴더의 모든 미디어 파일을 MediaStore에 등록
     * 최적화: 첫 실행 시에만 전체 스캔, 이후에는 새로운 파일만 스캔
     */
    suspend fun scanEchoShotMedia(context: Context, forceFullScan: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            // 저장공간 권한 확인
            if (!hasStoragePermission(context)) {
                Log.w(TAG, "저장공간 권한이 없어 미디어 스캔을 건너뜁니다")
                return@withContext
            }
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastScanTime = prefs.getLong(KEY_LAST_SCAN_TIME, 0L)
            val firstScanDone = prefs.getBoolean(KEY_FIRST_SCAN_DONE, false)
            
            val echoShotDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "EchoShot"
            )
            
            if (!echoShotDir.exists() || !echoShotDir.isDirectory) {
                Log.d(TAG, "EchoShot 폴더가 존재하지 않음: ${echoShotDir.absolutePath}")
                return@withContext
            }
            
            // 첫 실행이거나 강제 전체 스캔인 경우
            val shouldScanAll = forceFullScan || !firstScanDone
            
            val videoFiles = echoShotDir.listFiles { file ->
                file.isFile && file.extension.equals("mp4", ignoreCase = true) &&
                        (shouldScanAll || file.lastModified() > lastScanTime)
            } ?: emptyArray()
            
            val imageFiles = echoShotDir.listFiles { file ->
                file.isFile && (file.extension.equals("jpg", ignoreCase = true) ||
                        file.extension.equals("jpeg", ignoreCase = true)) &&
                        (shouldScanAll || file.lastModified() > lastScanTime)
            } ?: emptyArray()
            
            val totalFiles = videoFiles.size + imageFiles.size
            Log.d(TAG, "스캔할 파일 수: 비디오 ${videoFiles.size}개, 이미지 ${imageFiles.size}개 (전체: $totalFiles 개)")
            
            if (totalFiles == 0) {
                Log.d(TAG, "스캔할 새 파일이 없음")
                if (!firstScanDone) {
                    // 첫 스캔 완료 표시
                    prefs.edit().putBoolean(KEY_FIRST_SCAN_DONE, true).apply()
                }
                return@withContext
            }
            
            // 비디오 파일 스캔
            videoFiles.forEach { file ->
                try {
                    scanVideoFile(context, file)
                } catch (e: Exception) {
                    Log.e(TAG, "비디오 파일 스캔 실패: ${file.absolutePath}", e)
                }
            }
            
            // 이미지 파일 스캔
            imageFiles.forEach { file ->
                try {
                    scanImageFile(context, file)
                } catch (e: Exception) {
                    Log.e(TAG, "이미지 파일 스캔 실패: ${file.absolutePath}", e)
                }
            }
            
            // 마지막 스캔 시간 업데이트
            val currentTime = System.currentTimeMillis()
            prefs.edit()
                .putLong(KEY_LAST_SCAN_TIME, currentTime)
                .putBoolean(KEY_FIRST_SCAN_DONE, true)
                .apply()
            
            Log.d(TAG, "미디어 스캔 완료: $totalFiles 개 파일 처리됨")
        } catch (e: Exception) {
            Log.e(TAG, "미디어 스캔 중 오류 발생", e)
        }
    }
    
    /**
     * 비디오 파일을 MediaStore에 등록
     */
    private fun scanVideoFile(context: Context, file: File) {
        // MediaScannerConnection을 사용하여 스캔
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("video/mp4")
        ) { path, uri ->
            if (uri != null) {
                Log.d(TAG, "비디오 등록 완료: $path -> $uri")
            } else {
                Log.w(TAG, "비디오 등록 실패: $path")
            }
        }
    }
    
    /**
     * 이미지 파일을 MediaStore에 등록
     */
    private fun scanImageFile(context: Context, file: File) {
        // MediaScannerConnection을 사용하여 스캔
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("image/jpeg")
        ) { path, uri ->
            if (uri != null) {
                Log.d(TAG, "이미지 등록 완료: $path -> $uri")
            } else {
                Log.w(TAG, "이미지 등록 실패: $path")
            }
        }
    }
    
    /**
     * 스캔 상태 초기화 (디버그용)
     */
    fun resetScanState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_LAST_SCAN_TIME)
            .remove(KEY_FIRST_SCAN_DONE)
            .apply()
        Log.d(TAG, "스캔 상태 초기화 완료")
    }
    
    /**
     * 첫 스캔이 완료되었는지 확인
     */
    fun isFirstScanDone(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FIRST_SCAN_DONE, false)
    }
}

