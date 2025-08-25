package com.echoshot.app.mp4detact

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.echoshot.app.mp4detact.GlCtx
import com.echoshot.app.mp4detact.JsonLogger
import com.echoshot.app.mp4detact.VideoDetectFacade
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

object DetectLogManager {
    private const val TAG = "DetectLogManager"

    /**
     * mp4 파일에서 detect 로그 생성
     * @param ctx Context
     * @param sessionUuid 세션 UUID
     * @param videoUri 처리할 MP4 URI
     * @param outJsonUri 출력 JSON URI
     * @param onSuccess 성공 콜백
     * @param onError 에러 콜백
     */
    fun makeDetectLogFromMp4(
        ctx: Context,
        sessionUuid: String,
        videoUri: Uri,
        outJsonUri: Uri,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        // GL 전용 스레드
        val glDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "video-gl")
        }.asCoroutineDispatcher()

        CoroutineScope(glDispatcher).launch {
            val gl = GlCtx()
            try {
                JsonLogger.open(ctx, outJsonUri).use { logger ->
                    val facade = VideoDetectFacade(
                        ctx       = ctx,
                        gl        = gl,
                        modelPath = "yolov8n_int8.tflite",
                        useGpu    = true,
                        inputSize = 640
                    )

                    facade.run(
                        uri = videoUri,
                        onProgress = { frameIdx, ptsMs ->
                            if (frameIdx % 60 == 0) {
                                Log.d(TAG, "log progress: frame=$frameIdx pts=${ptsMs}ms")
                            }
                        },
                        onDetections = { frameIdx, ptsMs, dets ->
                            logger.append(
                                frameIdx = frameIdx,
                                ptsMs    = ptsMs,
                                fps      = 0f,
                                srcW     = facade.srcWidth,
                                srcH     = facade.srcHeight,
                                dets     = dets
                            )
                        }
                    )
                }
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                Log.e(TAG, "makeDetectLogFromMp4 failed", e)
                withContext(Dispatchers.Main) { onError(e) }
            } finally {
                try { gl.release() } catch (_: Throwable) {}
                glDispatcher.close()
            }
        }
    }
}
