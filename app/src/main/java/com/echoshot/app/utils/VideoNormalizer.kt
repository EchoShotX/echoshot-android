package com.echoshot.app.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object VideoNormalizer {
    private const val TAG = "VideoNormalizer"

    /**
     * 외부 영상의 메타데이터 회전값(Rotation)이 존재할 경우,
     * 해당 회전값을 픽셀 자체에 베이크(Bake)하여 물리적인 세로 영상으로 재인코딩(정규화)합니다.
     * 에코샷 자체 촬영 영상처럼 Rotation=0 상태로 만듭니다.
     */
    suspend fun normalizeVideoIfNeeded(context: Context, inputUri: Uri): Uri = withContext(Dispatchers.IO) {
        try {
            var rotation = 0
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, inputUri)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            if (rotationStr != null) {
                rotation = rotationStr.toIntOrNull() ?: 0
            }
            retriever.release()

            if (rotation == 0) {
                Log.d(TAG, "영상 회전값이 0입니다. 정규화 패스.")
                return@withContext inputUri
            }

            Log.d(TAG, "영상 회전값이 \$rotation 입니다. 에코샷 규격으로 정규화 시작...")

            // 1. 입력 파일을 임시 파일로 복사
            val inputTempFile = File(context.filesDir, "temp_norm_input.mp4")
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                FileOutputStream(inputTempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val outputFile = File(context.filesDir, "normalized_${System.currentTimeMillis()}.mp4")
            if (outputFile.exists()) outputFile.delete()

            // 2. FFmpeg 실행 
            // 기본 하드웨어 인코더(h264_mediacodec) 시도
            val cmd = "-y -i ${inputTempFile.absolutePath} -c:v h264_mediacodec -b:v 15M -c:a copy ${outputFile.absolutePath}"
            Log.d(TAG, "FFmpeg 정규화 시도: $cmd")
            var session = FFmpegKit.execute(cmd)
            var rc = session.returnCode

            // 하드웨어 인코더 실패 시, mpeg4 소프트웨어 인코딩으로 Fallback
            if (!ReturnCode.isSuccess(rc)) {
                Log.w(TAG, "h264_mediacodec 실패. mpeg4로 재시도...")
                val cmdFallback = "-y -i ${inputTempFile.absolutePath} -c:v mpeg4 -q:v 2 -c:a copy ${outputFile.absolutePath}"
                session = FFmpegKit.execute(cmdFallback)
                rc = session.returnCode
            }

            // 임시 원본 삭제
            if (inputTempFile.exists()) inputTempFile.delete()

            if (ReturnCode.isSuccess(rc)) {
                Log.d(TAG, "정규화 성공: \${outputFile.absolutePath}")
                return@withContext Uri.fromFile(outputFile)
            } else {
                Log.e(TAG, "정규화 실패: \${session.failStackTrace}")
                return@withContext inputUri
            }
        } catch (e: Exception) {
            Log.e(TAG, "정규화 중 에러 발생", e)
            return@withContext inputUri
        }
    }
}
