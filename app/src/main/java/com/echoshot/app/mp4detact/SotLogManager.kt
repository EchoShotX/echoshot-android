// SotLogManager.kt (파사드 사용 버전)
package com.echoshot.app.mp4detact

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import org.opencv.core.Rect as CvRect
import java.util.concurrent.Executors

object SotLogManager {
    private const val TAG = "SotLogManager"

    fun makeSotLogFromMp4(
        ctx: Context,
        videoUri: Uri,
        initBboxSrc: CvRect,
        outJsonUri: Uri,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val glDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "sot-gl").apply { priority = Thread.NORM_PRIORITY + 1 }
        }.asCoroutineDispatcher()

        CoroutineScope(glDispatcher).launch {
            val gl = GlCtx()
            try {
                JsonLogger.open(ctx, outJsonUri).use { logger ->
                    val facade = VideoSotFacade(
                        ctx = ctx,
                        gl  = gl,
                        modelAssetName = "siamrpnpp_mobile.ptl",
                        inputSize = 640,
                        exemplar  = 127,
                        instance  = 255,
                        frameStride = 1
                    )

                    var wroteInit = false

                    facade.run(
                        uri = videoUri,
                        initBboxSrc = initBboxSrc,
                        onProgress = { frameIdx, ptsMs ->
                            if (frameIdx % 60 == 0) {
                                Log.d(TAG, "progress: frame=$frameIdx pts=${ptsMs}ms")
                            }
                        },
                        onTracked = { frameIdx, ptsMs, rectSrc, score ->
                            val dets = if (rectSrc != null)
                                listOf(
                                    Detection(
                                        x1 = rectSrc.x.toFloat(),
                                        y1 = rectSrc.y.toFloat(),
                                        x2 = (rectSrc.x + rectSrc.width).toFloat(),
                                        y2 = (rectSrc.y + rectSrc.height).toFloat(),
                                        score = score,
                                        classId = 0,
                                        label = "target"
                                    )
                                )
                            else emptyList()

                            logger.append(
                                frameIdx = frameIdx,
                                ptsMs = ptsMs,
                                fps = 0f, // 필요하면 facade에 EMA 넣어 전달
                                srcW = facade.srcWidth,
                                srcH = facade.srcHeight,
                                dets = dets
                            )
                        },
                        onInitLogged = { frameIdx, ptsMs ->
                            if (!wroteInit) {
                                val x1 = initBboxSrc.x
                                val y1 = initBboxSrc.y
                                val x2 = initBboxSrc.x + initBboxSrc.width
                                val y2 = initBboxSrc.y + initBboxSrc.height
                                logger.append(
                                    frameIdx = frameIdx,
                                    ptsMs = ptsMs,
                                    fps = 0f,
                                    srcW = facade.srcWidth,
                                    srcH = facade.srcHeight,
                                    dets = listOf(
                                        Detection(
                                            x1 = x1.toFloat(),
                                            y1 = y1.toFloat(),
                                            x2 = x2.toFloat(),
                                            y2 = y2.toFloat(),
                                            score = 1.0f,
                                            classId = 0,
                                            label = "target"
                                        )
                                    )
                                )
                                wroteInit = true
                            }
                        }
                    )
                }
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                Log.e(TAG, "makeSotLogFromMp4 failed", e)
                withContext(Dispatchers.Main) { onError(e) }
            } finally {
                try { gl.release() } catch (_: Throwable) {}
                glDispatcher.close()
            }
        }
    }
}
