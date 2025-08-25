package com.echoshot.app.mp4detact

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log

class MediaCodecOesDecoder(
    private val context: Context,
    private val gl: GlCtx,
) {
    companion object { private const val TAG = "MC_OES" }

    data class Frame(
        val oesTexId: Int,
        val stMatrix: FloatArray,
        val ptsMs: Long,
    )

    // extractor/codec
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null

    // OES + SurfaceTexture waiter
    private var waiter: SurfaceTextureFrameWaiter? = null
    private var oes: OesTexture? = null

    // public metas (Facade에서 사용)
    var outWidth = 0;  private set
    var outHeight = 0; private set
    var rotation = 0;  private set
    var mime: String = ""; private set
    var isEos: Boolean = false; private set

    /** 디코더 열고 SurfaceTexture(OES)에 바인딩 */
    fun open(uri: Uri) {
        close()

        gl.makeCurrent()
        oes = OesTexture()
        waiter = SurfaceTextureFrameWaiter(oes!!.texId)

        // 1) Extractor 준비 + 비디오 트랙 선택
        val ex = MediaExtractor().apply { setDataSource(context, uri, null) }
        var vTrack = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (m.startsWith("video/")) {
                vTrack = i
                fmt = f
                ex.selectTrack(i)
                mime = m
                rotation = if (f.containsKey("rotation-degrees")) f.getInteger("rotation-degrees") else 0
                // 초기 포맷에서 크기 추정(OUTPUT_FORMAT_CHANGED에서 다시 계산)
                updateSizeFromFormat(f)
                break
            }
        }
        require(vTrack >= 0 && fmt != null) { "No video track" }
        extractor = ex

        // 2) 디코더 생성/시작 (surface 출력)
        codec = MediaCodec.createDecoderByType(mime).apply {
            setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            configure(fmt, waiter!!.surface, /*crypto*/null, /*flags*/0)
            start()
        }
        isEos = false
        Log.i(TAG, "Decoder opened: $outWidth x $outHeight mime=$mime rot=$rotation")
    }

    /**
     * 한 프레임을 디코드해 반환. 타임아웃이면 null(다음 호출에서 재시도).
     * EOS 도달 시 isEos=true 로 설정하고 null 반환.
     */
    fun decodeNext(timeoutUs: Long = 50_000): Frame? {
        val ex = extractor ?: return null
        val mc = codec ?: return null
        if (isEos) return null

        // ---- 입력 큐 채우기 ----
        val inIx = mc.dequeueInputBuffer(timeoutUs)
        if (inIx >= 0) {
            val inBuf = if (Build.VERSION.SDK_INT >= 21) mc.getInputBuffer(inIx) else mc.inputBuffers[inIx]
            val read = ex.readSampleData(inBuf!!, 0)
            if (read < 0) {
                mc.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
                mc.queueInputBuffer(inIx, 0, read, ex.sampleTime, 0)
                ex.advance()
            }
        }

        // ---- 출력 드레인 ----
        val info = MediaCodec.BufferInfo()
        val outIx = mc.dequeueOutputBuffer(info, timeoutUs)

        when {
            outIx >= 0 -> {
                val render = info.size > 0
                mc.releaseOutputBuffer(outIx, render)

                if (render) {
                    // SurfaceTexture에 진짜로 도착할 때까지 대기
                    if (!waiter!!.awaitNewFrame(500)) {
                        Log.w(TAG, "awaitNewFrame timeout")
                    } else {
                        gl.makeCurrent()
                        val st = waiter!!.updateTexImage()   // ← stMatrix 배열이 직접 리턴됨
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) isEos = true
                        return Frame(
                            oesTexId = oes!!.texId,
                            stMatrix = st,                   // ← fd.stMatrix 가 아니라 st
                            ptsMs = info.presentationTimeUs / 1000
                        )
                    }
                }

                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    isEos = true
                }
            }
            outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                mc.outputFormat?.let { f ->
                    updateSizeFromFormat(f)
                    Log.d(TAG, "Output format changed: $f (eff=${outWidth}x$outHeight)")
                }
            }
            outIx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                // 타임아웃: 다음 호출에서 재시도
            }
            outIx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                // API<21 호환용. 신경쓰지 않아도 OK.
            }
        }
        return null
    }

    /** MediaFormat의 crop 정보를 고려해 실제 출력 크기 계산 */
    private fun updateSizeFromFormat(f: MediaFormat) {
        val w = f.getInteger(MediaFormat.KEY_WIDTH)
        val h = f.getInteger(MediaFormat.KEY_HEIGHT)
        val hasCrop =
            f.containsKey("crop-left") || f.containsKey("crop-right") ||
                    f.containsKey("crop-top")  || f.containsKey("crop-bottom")
        if (hasCrop) {
            val cl = f.getIntegerOrDefault("crop-left", 0)
            val cr = f.getIntegerOrDefault("crop-right", w - 1)
            val ct = f.getIntegerOrDefault("crop-top", 0)
            val cb = f.getIntegerOrDefault("crop-bottom", h - 1)
            outWidth  = cr - cl + 1
            outHeight = cb - ct + 1
        } else {
            outWidth = w
            outHeight = h
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, def: Int): Int =
        if (containsKey(key)) getInteger(key) else def

    fun close() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        try { extractor?.release() } catch (_: Throwable) {}
        extractor = null
        try { waiter?.release() } catch (_: Throwable) {}
        waiter = null
        try { oes?.release() } catch (_: Throwable) {}
        oes = null
        isEos = false
    }
}
