package com.echoshot.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.Date

// 호출부에서 그냥 LogFormat 으로 쓰도록 top-level 로 둔다.
enum class LogFormat { PROCESSED_JSON, MERGED_JSONL }

/**
 * VideoPipeline: 세션 UUID와 원본 비디오 Uri를 받아서
 * (1) 기존 processed.json 또는 (2) 병합 JSONL 로그 기반으로 크롭된 비디오를 생성.
 */
object VideoPipeline {
    private const val TAG = "VideoPipeline"

    // === public API 1: 레거시 processed.json 사용 ===
    suspend fun processSession(
        context: Context,
        sessionUuid: String,
        srcVideoUri: Uri,
        fps: Int,
        paddingFactor: Float
    ): Uri? {
        val jsonFile = File(context.filesDir, "${sessionUuid}_processed.json")
        if (!jsonFile.exists()) {
            Log.w(TAG, "processed.json 없음: ${jsonFile.absolutePath}")
            return null
        }
        // 내부적으로 공용 경로로 위임
        return processSessionFromLog(
            context = context,
            sessionId = sessionUuid,
            srcVideoUri = srcVideoUri,
            fps = fps,
            paddingFactor = paddingFactor,
            logFile = jsonFile,
            format = LogFormat.PROCESSED_JSON
        )
    }

    // === public API 2: 신규 진입점 ===
    suspend fun processSessionFromLog(
        context: Context,
        sessionId: String,
        srcVideoUri: Uri,
        fps: Int,
        paddingFactor: Float,
        logFile: File,
        format: LogFormat
    ): Uri? {
        // 0) 촬영 시각
        val origTakenMs = queryDateTakenMs(context, srcVideoUri)

        // 1) 로그 존재 확인
        if (!logFile.exists() || logFile.length() == 0L) {
            Log.w(TAG, "로그 파일이 없거나 비어있음: ${logFile.absolutePath}")
            return null
        }

        // 2) 원본 비디오를 내부(filesDir)로 복사
        val filesDir = context.filesDir
        val srcVideoFile = File(filesDir, "$sessionId.mp4")
        context.contentResolver.openInputStream(srcVideoUri)?.use { inp ->
            FileOutputStream(srcVideoFile).use { out -> inp.copyTo(out) }
        }

        // 3) 출력 파일 경로 (DCIM/Camera2App)
        val dcimDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Camera2App"
        )
        if (!dcimDir.exists()) dcimDir.mkdirs()
        val ts = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US).format(Date())
        val unique = (System.nanoTime() % 100000).toString().padStart(5, '0')
        val outName = "VID_${sessionId}_cropped_${ts}_$unique.mp4"
        val outputFile = File(dcimDir, outName)

        // ✅ 디버그 JSON(실제 적용된 크롭) 내부 파일 경로
        val debugFile = File(context.filesDir, "${sessionId}_applied_crops.jsonl")

        // 4) 크롭 실행
        try {
            FrameCropper(
                srcPath = srcVideoFile.absolutePath,
                dstPath = outputFile.absolutePath,
                fps = fps,
                paddingFactor = paddingFactor,
                logPath = logFile.absolutePath,
                logFormat = format,
                debugJsonPath = debugFile.absolutePath    // ✅ 내부에 먼저 기록
            ).cropAll()
        } catch (e: Exception) {
            Log.e(TAG, "FrameCropper 실패", e)
            return null
        }

        // 5) MP4 creation_time 덮어쓰기(가능하면)
        if (origTakenMs != null) {
            val iso = toIso8601Utc(origTakenMs)
            val metaOut = File(outputFile.parentFile, "meta_${outputFile.name}")
            val cmd = listOf(
                "-y", "-i", outputFile.absolutePath,
                "-map", "0", "-c", "copy",
                "-movflags", "use_metadata_tags",
                "-metadata", "creation_time=$iso",
                metaOut.absolutePath
            )
            val session = FFmpegKit.execute(cmd.joinToString(" "))
            val rc = session.returnCode
            if (ReturnCode.isSuccess(rc)) {
                if (!outputFile.delete()) Log.w(TAG, "outputFile 삭제 실패: ${outputFile.absolutePath}")
                if (!metaOut.renameTo(outputFile)) Log.w(TAG, "renameTo 실패: ${metaOut.absolutePath}")
                outputFile.setLastModified(origTakenMs)
            } else {
                Log.e(TAG, "FFmpegKit 실패: ${session.failStackTrace}")
            }
        }

        // 6) 갤러리 반영 (비디오)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(outputFile.absolutePath),
            arrayOf("video/mp4")
        ) { path, uri -> Log.d(TAG, "✅ MediaScanner 등록 완료: $path -> $uri") }

        // 7) ✅ 디버그 JSON을 Downloads/EchoShotLogs 로 복사(SAF)
        try {
            if (debugFile.exists() && debugFile.length() > 0L) {
                val displayName = "${sessionId}_applied_crops_${ts}.jsonl"
                val dstUri = createOutputJsonInDownloads(context, displayName)
                if (dstUri != null) {
                    context.contentResolver.openOutputStream(dstUri, "w")!!.use { os ->
                        debugFile.inputStream().use { it.copyTo(os) }
                    }
                    Log.d(TAG, "✅ Debug JSON 복사 완료: $dstUri")
                } else {
                    Log.w(TAG, "Debug JSON 대상 URI 생성 실패")
                }
            } else {
                Log.w(TAG, "Debug JSON이 비어있거나 없음: ${debugFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Debug JSON 복사 실패", e)
        }

        return Uri.fromFile(outputFile)
    }

    // === Downloads/EchoShotLogs에 JSON 파일 생성(SAF) ===
    private fun createOutputJsonInDownloads(ctx: Context, displayName: String): Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/EchoShotLogs"
                )
            }
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(collection, values) ?: return null
        // 터치: 스트림 열었다 닫아 경로 생성 보장
        cr.openOutputStream(uri, "w")?.use { /* no-op */ } ?: return null
        return uri
    }

    // === 내부 유틸 ===
    private fun queryDateTakenMs(context: Context, uri: Uri): Long? {
        val proj = arrayOf(MediaStore.Video.Media.DATE_TAKEN)
        context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                return c.getLong(idx)
            }
        }
        return null
    }

    private fun toIso8601Utc(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }
}
