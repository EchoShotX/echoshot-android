package com.echoshot.app.mp4detact

import android.content.Context
import android.net.Uri
import android.util.Log
import com.echoshot.app.mp4detact.io.PoseJsonLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * YOLO11n-pose 기반 포즈 감지 로그 생성 매니저
 * 
 * DetectLogManager와 동일한 인터페이스이나, 
 * keypoints가 포함된 PoseDetection을 로깅합니다.
 */
object PoseDetectLogManager {
    private const val TAG = "PoseDetectLogManager"

    /**
     * MP4 파일에서 포즈 감지 로그 생성
     * @param ctx Context
     * @param sessionUuid 세션 UUID
     * @param videoUri 처리할 MP4 URI
     * @param outJsonUri 출력 JSON URI (JSONL 형식)
     * @param onProgress 진행 콜백 (frameIdx, totalEstimated)
     * @param onSuccess 성공 콜백
     * @param onError 에러 콜백
     */
    fun makePoseLogFromMp4(
        ctx: Context,
        sessionUuid: String,
        videoUri: Uri,
        outJsonUri: Uri,
        onProgress: (frameIdx: Int, ptsMs: Long) -> Unit = { _, _ -> },
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        // GL 전용 스레드
        val glDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pose-gl")
        }.asCoroutineDispatcher()

        CoroutineScope(glDispatcher).launch {
            val gl = GlCtx()
            try {
                PoseJsonLogger.open(ctx, outJsonUri).use { logger ->
                    val facade = VideoPoseDetectFacade(
                        ctx       = ctx,
                        gl        = gl,
                        modelPath = "yolo11n-pose_float16.tflite",
                        useGpu    = true,
                        inputSize = 640
                    )

                    facade.run(
                        uri = videoUri,
                        onProgress = { frameIdx, ptsMs ->
                            onProgress(frameIdx, ptsMs)
                            if (frameIdx % 60 == 0) {
                                Log.d(TAG, "pose log progress: frame=$frameIdx pts=${ptsMs}ms")
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
                Log.e(TAG, "makePoseLogFromMp4 failed", e)
                withContext(Dispatchers.Main) { onError(e) }
            } finally {
                try { gl.release() } catch (_: Throwable) {}
                glDispatcher.close()
            }
        }
    }
}

