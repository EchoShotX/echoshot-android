package com.echoshot.app

import android.graphics.SurfaceTexture
import android.media.*
import android.opengl.*
import android.os.Build
import android.util.Log
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*



// =======================================================
class MyGLRenderer {
    private val TAG = "MyGLRenderer"
    private var programId = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var samplerHandle = 0
    private var texMatrixHandle = 0 // ★ 추가: uTexMatrix

    /** OES 텍스처 생성 */
    fun createOESTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    /** EGLContext 및 셰이더 프로그램 초기화 */
    fun initGL() {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)
        programId = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vShader)
            GLES20.glAttachShader(it, fShader)
            GLES20.glLinkProgram(it)
        }
        positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
        samplerHandle  = GLES20.glGetUniformLocation(programId, "sTexture")
        texMatrixHandle= GLES20.glGetUniformLocation(programId, "uTexMatrix") // ★
        Log.d(TAG, "GL 프로그램 초기화 완료: programId=$programId")
    }

    /**
     * 프레임 렌더링
     * @param textureId OES 텍스처 ID
     * @param texMatrix 4x4 텍스처 변환행렬 (SurfaceTexture의 transform × 크롭행렬)
     */
    fun drawFrame(textureId: Int, texMatrix: FloatArray) {
        GLES20.glUseProgram(programId)

        // 표준 정사각형 (회전/뒤집기 없음)
        val vertexCoords = floatArrayOf(
            -1f, -1f,  // BL
            1f, -1f,  // BR
            -1f,  1f,  // TL
            1f,  1f   // TR
        )
        val texCoords = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        val vb = ByteBuffer.allocateDirect(vertexCoords.size*4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertexCoords).apply { position(0) }
        val tb = ByteBuffer.allocateDirect(texCoords.size*4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords).apply { position(0) }

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vb)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tb)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerHandle, 0)

        // ★ 텍스처 변환행렬 업로드
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun loadShader(type: Int, code: String): Int {
        return GLES20.glCreateShader(type).also { sh ->
            GLES20.glShaderSource(sh, code)
            GLES20.glCompileShader(sh)
        }
    }

    companion object {
        private const val VERTEX_SHADER_CODE = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;      // ★ 추가
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;  // ★ 핵심
            }
        """
        private const val FRAGMENT_SHADER_CODE = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}


class FrameCropper(
    private val srcPath: String,
    private val dstPath: String,
    private val fps: Int,
    private val paddingFactor: Float,
    private val logPath: String,          // ★ 추가
    private val logFormat: LogFormat,      // ★ 추가
    private val debugJsonPath: String? = null
) {
    private val TAG = "FrameCropper"

    data class FrameData(
        val x1: Double, val y1: Double,
        val x2: Double, val y2: Double,
        val screenW: Int, val screenH: Int
    )

    private fun Double.isBad() = this.isNaN() || this.isInfinite()

    // 파일 상단 지역변수
    var lastWrittenPtsUs: Long = Long.MIN_VALUE

    private fun make9x16KeepHeight(
        x1: Double, y1: Double, x2: Double, y2: Double,
        screenW: Int
    ): DoubleArray {
        // 높이 고정
        val h = (y2 - y1).coerceAtLeast(1.0)
        val cy = (y1 + y2) * 0.5
        var targetW = h * 9.0 / 16.0
        val cx = (x1 + x2) * 0.5

        // 기본: 중심 유지한 채 가로만 targetW로 설정
        var left = cx - targetW * 0.5
        var right = cx + targetW * 0.5
        var top = y1
        var bot = y2

        // 좌우 경계 내로 이동 (크기 유지)
        if (left < 0.0) {
            right -= left; left = 0.0
        }
        if (right > screenW) {
            val diff = right - screenW
            left -= diff; right = screenW.toDouble()
        }

        // 여전히 화면에 못 들어갈 만큼 넓다면(즉, targetW > screenW),
        // 비율 유지로 w를 화면폭에 맞추고, 그에 맞춰 h도 재산정(중심 유지)
        if (right - left > screenW) {
            left = 0.0
            right = screenW.toDouble()
        }
        if ((right - left) > screenW - 1e-6) {
            targetW = (screenW.toDouble())
            val newH = targetW * 16.0 / 9.0
            top = cy - newH * 0.5
            bot = top + newH
            // 이 경우엔 세로도 함께 조정(중심 유지). 화면 세로 클램프가 필요하면 여기에 추가.
        }

        return doubleArrayOf(left, top, right, bot)
    }

    private fun interpolate(a: Double, b: Double, t: Double) = a + (b - a) * t

    /** JSON 읽어서 프레임 시퀀스로 변환 */
    private fun loadFramesFromLog(): MutableList<FrameData> {
        val out = mutableListOf<FrameData>()
        val f = File(logPath)
        require(f.exists()) { "Log file not found: ${f.absolutePath}" }

        when (logFormat) {
            LogFormat.PROCESSED_JSON -> {
                // 배열 JSON: [{aspect_ratio_bbox:[x1,y1,x2,y2], screenWidth, screenHeight}, ...]
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val sw = o.getInt("screenWidth")
                    val sh = o.getInt("screenHeight")
                    val sb = o.getJSONArray("aspect_ratio_bbox")
                    val x1 = sb.getDouble(0);
                    val y1 = sb.getDouble(1)
                    val x2 = sb.getDouble(2);
                    val y2 = sb.getDouble(3)
                    // ✅ 보정 없이 그대로 사용
                    out += FrameData(x1, y1, x2, y2, sw, sh)
                }
            }

            LogFormat.MERGED_JSONL -> {
                // 라인별 JSON
                f.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val o = JSONObject(line)
                    val sw = o.optInt("screenWidth", o.optInt("src_w", 0))
                    val sh = o.optInt("screenHeight", o.optInt("src_h", 0))

                    var rect: DoubleArray? = null

                    // 1) crop_box_scr_xyxy: {x1,y1,x2,y2}
                    o.optJSONObject("crop_box_screen_xyxy")?.let { rb ->
                        if (rb.has("x1") && rb.has("y1") && rb.has("x2") && rb.has("y2")) {
                            val x1 = rb.getDouble("x1")
                            val y1 = rb.getDouble("y1")
                            val x2 = rb.getDouble("x2")
                            val y2 = rb.getDouble("y2")
                            rect = doubleArrayOf(x1, y1, x2, y2)
                        }
                    }

                    // 2) crop_box_screen: {x,y,w,h} → xyxy
                    if (rect == null) {
                        o.optJSONObject("crop_box_screen")?.let { rb ->
                            // 호환: x1,y1,x2,y2 로 내려오는 경우도 지원
                            if (rb.has("x1") && rb.has("y1") && rb.has("x2") && rb.has("y2")) {
                                val x1 = rb.getDouble("x1")
                                val y1 = rb.getDouble("y1")
                                val x2 = rb.getDouble("x2")
                                val y2 = rb.getDouble("y2")
                                rect = doubleArrayOf(x1, y1, x2, y2)
                            } else if (rb.has("x") && rb.has("y") && rb.has("w") && rb.has("h")) {
                                val x = rb.getDouble("x")
                                val y = rb.getDouble("y")
                                val w = rb.getDouble("w")
                                val h = rb.getDouble("h")
                                rect = doubleArrayOf(x, y, x + w, y + h)
                            }
                        }
                    }

                    // 3) fallback: aspect_ratio_bbox 그대로 (참고용이지만 없을 때만)
                    if (rect == null && o.has("aspect_ratio_bbox")) {
                        val sb = o.getJSONArray("aspect_ratio_bbox")
                        rect = doubleArrayOf(
                            sb.getDouble(0),
                            sb.getDouble(1),
                            sb.getDouble(2),
                            sb.getDouble(3)
                        )
                    }

                    rect?.let { r ->
                        out += FrameData(r[0], r[1], r[2], r[3], sw, sh)
                    } ?: run {
                        out += FrameData(Double.NaN, Double.NaN, Double.NaN, Double.NaN, sw, sh)
                    }
                }
            }
        }
        require(out.isNotEmpty()) { "Empty frame data" }
        return out
    }

    private fun isValid(f: FrameData) =
        !(f.x1.isBad() || f.y1.isBad() || f.x2.isBad() || f.y2.isBad())

    private fun fillMissing(frames: MutableList<FrameData>): List<FrameData> {
        // 앞/뒤 고정 채움
        val firstValid = frames.indexOfFirst { isValid(it) }
        require(firstValid != -1) { "No valid boxes to interpolate" }
        for (i in 0 until firstValid) frames[i] = frames[firstValid].copy()

        val lastValid = frames.indexOfLast { isValid(it) }
        for (i in lastValid + 1 until frames.size) frames[i] = frames[lastValid].copy()

        // 중간 구간 보간
        var i = firstValid
        while (i < frames.size) {
            if (isValid(frames[i])) {
                i++; continue
            }

            val start = i - 1
            var end = i + 1
            while (end < frames.size && !isValid(frames[end])) end++

            val fa = frames[start]
            val fb = frames[end]
            val span = end - start

            for (k in 1 until span) {
                val t = k.toDouble() / span
                val x1 = fa.x1 + (fb.x1 - fa.x1) * t
                val y1 = fa.y1 + (fb.y1 - fa.y1) * t
                val x2 = fa.x2 + (fb.x2 - fa.x2) * t
                val y2 = fa.y2 + (fb.y2 - fa.y2) * t

                val sw = frames[start + k].screenW
                val sh = frames[start + k].screenH
                val r = make9x16KeepHeight(x1, y1, x2, y2, sw)
                frames[start + k] = FrameData(r[0], r[1], r[2], r[3], sw, sh)
            }
            i = end
        }
        return frames
    }

    fun cropAll() {
        Log.d(TAG, "크롭 작업 시작: source=$srcPath, destination=$dstPath, log=$logPath ($logFormat)")

        val raw = loadFramesFromLog()
        val filled = fillMissing(raw)

        // paddingFactor만 적용
        val paddedFrames = if (paddingFactor == 1f) filled else filled.map { f ->
            val cx = (f.x1 + f.x2) * 0.5
            val cy = (f.y1 + f.y2) * 0.5
            val w = (f.x2 - f.x1) * paddingFactor
            val h = (f.y2 - f.y1) * paddingFactor
            f.copy(x1 = cx - w * 0.5, y1 = cy - h * 0.5, x2 = cx + w * 0.5, y2 = cy + h * 0.5)
        }

        val extractor = MediaExtractor().apply { setDataSource(srcPath) }
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                ?.startsWith("video/") == true
        }
        extractor.selectTrack(track)
        val inFmt = extractor.getTrackFormat(track)
        val inW = inFmt.getInteger(MediaFormat.KEY_WIDTH)
        val inH = inFmt.getInteger(MediaFormat.KEY_HEIGHT)
        Log.d(TAG, "입력 비디오 포맷 해상도: ${inW}x${inH}")

        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val encoder = MediaCodec.createEncoderByType(mime)
        val outFmt = MediaFormat.createVideoFormat(mime, inW, inH).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            }
        }
        encoder.configure(outFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, null, 0, null, 0)
        val cfg = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(
            eglDisplay,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1,
                EGL14.EGL_NONE
            ), 0, cfg, 0, 1, IntArray(1), 0
        )
        val eglConfig = cfg[0]!!
        val eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, inputSurface, null, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        val renderer = MyGLRenderer().apply { initGL() }
        val textureId = renderer.createOESTexture()

        val surfaceTexture = SurfaceTexture(textureId).apply { setDefaultBufferSize(inW, inH) }
        val decoderSurface = Surface(surfaceTexture)
        val decoder =
            MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(inFmt, decoderSurface, null, 0); start()
            }

        val muxer = MediaMuxer(dstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        // ★ 분리된 BufferInfo
        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()

        var muxerStarted = false
        var outTrackIdx = -1
        var renderIdx = 0       // 렌더(디코더 출력) 기준 프레임 인덱스
        var encodedIdx = 0      // 인코더가 실제 낸 프레임(CodecConfig 제외) 카운트
        var sawEOS = false

        var lastPtsUs = -1L
        var maxPresentedPtsUs = -1L
        var padStartPtsUs = Long.MAX_VALUE

        val st = FloatArray(16)
        val crop = FloatArray(16)
        val finalM = FloatArray(16)

        // 디버그 파일(선택)
        val debugWriter = try {
            debugJsonPath?.let { java.io.BufferedWriter(java.io.FileWriter(java.io.File(it))) }
        } catch (_: Throwable) {
            null
        }

        try {
            while (!sawEOS) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inputBuf = decoder.getInputBuffer(inIndex)!!
                    val sz = extractor.readSampleData(inputBuf, 0)
                    if (sz < 0) {
                        decoder.queueInputBuffer(
                            inIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sawEOS = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(decInfo, 10_000)
                if (outIndex >= 0) {
                    val isEosOutput = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val doRender = !isEosOutput

                    decoder.releaseOutputBuffer(outIndex, doRender)

                    if (doRender) {
                        surfaceTexture.updateTexImage()
                        surfaceTexture.getTransformMatrix(st)

                        val data = paddedFrames.getOrNull(renderIdx) ?: paddedFrames.last()

                        // 프레임별 스케일 (screen → decoder input)
                        val scaleX = inW.toFloat() / data.screenW
                        val scaleY = inH.toFloat() / data.screenH

                        // 스크린 → 픽셀(float)
                        val px1f = (data.x1 * scaleX).toFloat()
                        val py1f = (data.y1 * scaleY).toFloat()
                        val px2f = (data.x2 * scaleX).toFloat()
                        val py2f = (data.y2 * scaleY).toFloat()

                        // 짝수 정렬 및 9:16 미세 보정
                        var x1 = floor(px1f).toInt();
                        var y1 = floor(py1f).toInt()
                        var x2 = ceil(px2f).toInt();
                        var y2 = ceil(py2f).toInt()

                        x1 = (x1.coerceIn(0, inW - 2)) and -2
                        y1 = (y1.coerceIn(0, inH - 2)) and -2
                        x2 = ((x2.coerceIn(x1 + 2, inW)) + 1) and -2
                        y2 = ((y2.coerceIn(y1 + 2, inH)) + 1) and -2

                        val cropW = (x2 - x1).coerceAtLeast(2)
                        val cropH = (y2 - y1).coerceAtLeast(2)
                        if (kotlin.math.abs(cropW / cropH.toFloat() - 9f / 16f) > 1e-3f) {
                            val adjustedW = (cropH * 9f / 16f).roundToInt() / 2 * 2
                            val cx = (x1 + x2) / 2
                            x1 = cx - adjustedW / 2
                            x2 = cx + adjustedW / 2
                        }

                        // 픽셀 → 정규화
                        val u0 = (x1 / inW.toFloat()).coerceIn(0f, 1f)
                        val v0 = (y1 / inH.toFloat()).coerceIn(0f, 1f)
                        val u1 = (x2 / inW.toFloat()).coerceIn(0f, 1f)
                        val v1 = (y2 / inH.toFloat()).coerceIn(0f, 1f)

                        // 최종 텍스처 행렬 = CROP × ST
                        Matrix.setIdentityM(crop, 0)
                        Matrix.translateM(crop, 0, u0, v0, 0f)
                        Matrix.scaleM(crop, 0, (u1 - u0), (v1 - v0), 1f)
                        Matrix.multiplyMM(finalM, 0, crop, 0, st, 0)

                        // PTS 단조 보정
                        var ptsUs = decInfo.presentationTimeUs
                        if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1
                        lastPtsUs = ptsUs
                        maxPresentedPtsUs = maxOf(maxPresentedPtsUs, ptsUs)

                        GLES20.glViewport(0, 0, inW, inH)
                        renderer.drawFrame(textureId, finalM)
                        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsUs * 1000L)
                        EGL14.eglSwapBuffers(eglDisplay, eglSurface)


                        // 디버그 기록 (렌더 기준)
                        if (debugWriter != null) {
                            val usedScreenX1 = x1 / scaleX
                            val usedScreenY1 = y1 / scaleY
                            val usedScreenX2 = x2 / scaleX
                            val usedScreenY2 = y2 / scaleY
                            val obj = JSONObject().apply {
                                put("frame", renderIdx)
                                put("pts_us", ptsUs)
                                put("in_w", inW); put("in_h", inH)
                                put("screen_w", data.screenW); put("screen_h", data.screenH)
                                put(
                                    "requested_box_screen_xyxy", JSONArray(
                                        doubleArrayOf(
                                            data.x1, data.y1, data.x2, data.y2
                                        )
                                    )
                                )
                                put(
                                    "requested_box_px_float", JSONArray(
                                        floatArrayOf(
                                            px1f, py1f, px2f, py2f
                                        )
                                    )
                                )
                                put("applied_box_px_int", JSONArray(intArrayOf(x1, y1, x2, y2)))
                                put(
                                    "applied_box_screen_xyxy", JSONArray(
                                        doubleArrayOf(
                                            usedScreenX1.toDouble(), usedScreenY1.toDouble(),
                                            usedScreenX2.toDouble(), usedScreenY2.toDouble()
                                        )
                                    )
                                )
                                put("applied_box_norm_uv", JSONArray(floatArrayOf(u0, v0, u1, v1)))
                                put("enc_idx_so_far", encodedIdx)
                            }
                            debugWriter.write(obj.toString()); debugWriter.write("\n")
                            if (renderIdx % 60 == 0) debugWriter.flush()
                        }

                        // 렌더 프레임 인덱스 증가
                        renderIdx++
                    }
                    // 인코더 drain (별도 encInfo 사용)
                    while (true) {
                        val encIndex = encoder.dequeueOutputBuffer(encInfo, 0)
                        when (encIndex) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> break
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (!muxerStarted) {
                                    outTrackIdx = muxer.addTrack(encoder.outputFormat)
                                    muxer.start()
                                    muxerStarted = true
                                }
                            }
                            else -> if (encIndex >= 0) {
                                if (encInfo.size > 0 && muxerStarted) {
                                    val samplePts = encInfo.presentationTimeUs

                                    // padStartPtsUs 미만 = 실제 프레임 → 기록
                                    if (samplePts < padStartPtsUs) {
                                        val buf = encoder.getOutputBuffer(encIndex)!!
                                        muxer.writeSampleData(outTrackIdx, buf, encInfo)
                                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                            encodedIdx++
                                        }
                                    } else {
                                        // 패드 프레임 → 기록하지 않음 (드롭)
                                        // 필요하면 Log로 떨어진 패드 개수 추적 가능
                                        // Log.d(TAG, "drop pad frame ptsUs=$samplePts")
                                    }
                                }
                                encoder.releaseOutputBuffer(encIndex, false)
                                if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                            }
                        }
                    }
                }
            }
            // 1) 디코더 잔여 출력 끝까지 뽑아내며 렌더
            var decoderEosSeen = false
            while (!decoderEosSeen) {
                val outIndex = decoder.dequeueOutputBuffer(decInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 잠시 대기 후 계속 시도 (무한루프 방지하려면 카운터 넣어도 됨)
                        continue
                    }
                    outIndex >= 0 -> {
                        val isEosOutput = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val doRender = !isEosOutput

                        decoder.releaseOutputBuffer(outIndex, doRender)

                        if (doRender) {
                            surfaceTexture.updateTexImage()
                            surfaceTexture.getTransformMatrix(st)

                            val data = paddedFrames.getOrNull(renderIdx) ?: paddedFrames.last()

                            // --- 메인 루프와 동일한 크롭 산식 ---
                            val scaleX = inW.toFloat() / data.screenW
                            val scaleY = inH.toFloat() / data.screenH

                            val px1f = (data.x1 * scaleX).toFloat()
                            val py1f = (data.y1 * scaleY).toFloat()
                            val px2f = (data.x2 * scaleX).toFloat()
                            val py2f = (data.y2 * scaleY).toFloat()

                            var x1 = floor(px1f).toInt()
                            var y1 = floor(py1f).toInt()
                            var x2 = ceil(px2f).toInt()
                            var y2 = ceil(py2f).toInt()

                            x1 = (x1.coerceIn(0, inW - 2)) and -2
                            y1 = (y1.coerceIn(0, inH - 2)) and -2
                            x2 = ((x2.coerceIn(x1 + 2, inW)) + 1) and -2
                            y2 = ((y2.coerceIn(y1 + 2, inH)) + 1) and -2

                            val cropW = (x2 - x1).coerceAtLeast(2)
                            val cropH = (y2 - y1).coerceAtLeast(2)
                            if (kotlin.math.abs(cropW / cropH.toFloat() - 9f / 16f) > 1e-3f) {
                                val adjustedW = (cropH * 9f / 16f).roundToInt() / 2 * 2
                                val cx = (x1 + x2) / 2
                                x1 = cx - adjustedW / 2
                                x2 = cx + adjustedW / 2
                            }

                            val u0 = (x1 / inW.toFloat()).coerceIn(0f, 1f)
                            val v0 = (y1 / inH.toFloat()).coerceIn(0f, 1f)
                            val u1 = (x2 / inW.toFloat()).coerceIn(0f, 1f)
                            val v1 = (y2 / inH.toFloat()).coerceIn(0f, 1f)

                            Matrix.setIdentityM(crop, 0)
                            Matrix.translateM(crop, 0, u0, v0, 0f)
                            Matrix.scaleM(crop, 0, (u1 - u0), (v1 - v0), 1f)
                            Matrix.multiplyMM(finalM, 0, crop, 0, st, 0)
                            // --- 메인 루프와 동일한 크롭 산식 ---

                            // 🔒 PTS 단조 보정
                            var ptsUs = decInfo.presentationTimeUs
                            if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1
                            lastPtsUs = ptsUs
                            maxPresentedPtsUs = maxOf(maxPresentedPtsUs, ptsUs)

                            GLES20.glViewport(0, 0, inW, inH)
                            renderer.drawFrame(textureId, finalM)
                            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsUs * 1000L)
                            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

                            renderIdx++
                        }
                        if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderEosSeen = true
                        }
                    }
                    else -> { /* INFO_OUTPUT_FORMAT_CHANGED 등 무시 */ }
                }
            }

            // 2) 인코더에 EOS 신호 (Surface 입력)
            if (Build.VERSION.SDK_INT >= 18) {
                // 마지막 "실제" 프레임 다음 PTS를 패드 시작점으로 기록
                padStartPtsUs = lastPtsUs + 1

                GLES20.glFinish()
                encoder.signalEndOfInputStream()
            }

            // 3) 인코더를 EOS까지 드레인 (muxer start는 INFO_OUTPUT_FORMAT_CHANGED에서)
            while (true) {
                val encIndex = encoder.dequeueOutputBuffer(encInfo, 10_000)
                when {
                    encIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            outTrackIdx = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    encIndex >= 0 -> {
                        val isConfig = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isEos    = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val samplePts = encInfo.presentationTimeUs

                        // padStartPtsUs 이전의 "실제" 프레임만 기록
                        // 변경:
                        if (
                            encInfo.size > 0 &&
                            muxerStarted &&
                            !isConfig &&
                            samplePts < padStartPtsUs &&           // 패드 프레임 드롭
                            samplePts > lastWrittenPtsUs           // ⬅️ 중복/역행 PTS 드롭
                        ) {
                            encoder.getOutputBuffer(encIndex)?.let { buf ->
                                muxer.writeSampleData(outTrackIdx, buf, encInfo)
                                lastWrittenPtsUs = samplePts        // ⬅️ 갱신
                                encodedIdx++
                            }
                        } else {
                            // 패드 프레임 또는 CONFIG → 기록/집계 안함
                            // Log.d(TAG, "drop: pts=$samplePts flags=${encInfo.flags}")
                        }

                        encoder.releaseOutputBuffer(encIndex, false)
                        if (isEos) break
                    }
                }
            }
        } finally {
            try {
                debugWriter?.flush(); debugWriter?.close()
            } catch (_: Throwable) {
            }
        }

        muxer.stop(); muxer.release()
        decoder.stop(); decoder.release()
        encoder.stop(); encoder.release()
        extractor.release()
        Log.d(TAG, "크롭 작업 완료: 렌더=${renderIdx}, 인코딩=${encodedIdx}")
    }
}
