package com.echoshot.app.mp4detact

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * OES 텍스처에서 (cx, cy, side) 정사각형 ROI를 잘라
 * outSize × outSize 로 리사이즈해 RGBA FBO에 그려주는 전용 FBO.
 *
 * - aTex(uv) 버퍼를 매 프레임 "크롭 사각형"으로 갱신 → uStMatrix로 최종 보정
 * - Letterbox 없이 패치만 생성 → SOT(127/255) 입력에 최적
 */
class GlCropSquareFbo(
    private val gl: GlCtx,
    private val outSize: Int = 255,
) {
    data class PatchMeta(
        val cx: Float,   // 원본 픽셀 기준 중심
        val cy: Float,
        val side: Float, // 원본 픽셀 기준 정사각형 한 변
        val out: Int     // 패치 해상도 (보통 127 또는 255)
    )

    private var fboId = 0
    private var colorTex = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTex = 0
    private var uStMatrix = 0

    private val quad: FloatBuffer
    private val uv: FloatBuffer

    // 읽기 스크래치(재사용)
    private val rgbaScratch: ByteBuffer =
        ByteBuffer.allocateDirect(outSize * outSize * 4).order(ByteOrder.nativeOrder())

    init {
        gl.makeCurrent()

        // 출력 텍스처 생성
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        colorTex = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            outSize, outSize, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // FBO
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        fboId = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            colorTex,
            0
        )
        checkFramebuffer()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // 셰이더
        program = buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")

        // 풀스크린 사각형
        quad = floatBuf(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f,  1f,
                1f,  1f
            )
        )
        // uv 버퍼는 매 호출마다 "크롭 사각형"으로 갱신한다.
        uv = floatBuf(FloatArray(8)) // 자리만 할당

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    /**
     * @param cx,cy,side : 원본 픽셀 좌표계
     * @param srcW,srcH  : 원본 프레임 해상도
     * @return 패치 메타(나중에 좌표 복원에 사용)
     */
    fun render(
        oesTexId: Int,
        stMatrix: FloatArray,
        srcW: Int,
        srcH: Int,
        cx: Float,
        cy: Float,
        side: Float
    ): PatchMeta {
        gl.makeCurrent()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)

        // 출력은 패치 크기로 고정
        GLES20.glViewport(0, 0, outSize, outSize)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // ----- 크롭 사각형을 uv로 변환 (0..1)
        val half = side * 0.5f
        val left   = ((cx - half) / srcW).coerceIn(0f, 1f)
        val right  = ((cx + half) / srcW).coerceIn(0f, 1f)
        val top    = ((cy - half) / srcH).coerceIn(0f, 1f)
        val bottom = ((cy + half) / srcH).coerceIn(0f, 1f)

        // 주의: SurfaceTexture의 좌표계는 stMatrix로 보정되므로, 여기선 "사전 uv"만 구성
        // (x,y) 매핑: aTex = (u,v)
        uv.position(0)
        uv.put(left);  uv.put( bottom) // (0) 좌하
        uv.put(right); uv.put( bottom) // (1) 우하
        uv.put(left);  uv.put( top)    // (2) 좌상
        uv.put(right); uv.put( top)    // (3) 우상
        uv.position(0)

        // ----- 렌더
        GLES20.glUseProgram(program)

        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)

        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, uv)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glUniform1i(uTex, 0)

        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 정리
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glUseProgram(0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        return PatchMeta(cx, cy, side, outSize)
    }

    /** RGBA(8bit) 패치 읽기 */
    fun readRgbaU8(): ByteBuffer {
        gl.makeCurrent()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        rgbaScratch.position(0)
        GLES20.glReadPixels(0, 0, outSize, outSize, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaScratch)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        rgbaScratch.position(0)
        return rgbaScratch
    }

    /** Gray(U8)로 패킹해서 반환 (간단·빠름: R 채널만 추출) */
    fun readGrayU8(): ByteBuffer {
        val rgba = readRgbaU8()
        val out = ByteBuffer.allocateDirect(outSize * outSize).order(ByteOrder.nativeOrder())
        var i = 0
        val total = outSize * outSize
        while (i < total) {
            val r = rgba.get().toInt() and 0xFF
            rgba.get(); rgba.get(); rgba.get() // skip G,B,A
            out.put(r.toByte())
            i++
        }
        out.position(0)
        return out
    }

    fun release() {
        gl.makeCurrent()
        val ids = IntArray(1)
        if (fboId != 0) {
            ids[0] = fboId; GLES20.glDeleteFramebuffers(1, ids, 0); fboId = 0
        }
        if (colorTex != 0) {
            ids[0] = colorTex; GLES20.glDeleteTextures(1, ids, 0); colorTex = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program); program = 0
        }
    }

    private fun checkFramebuffer() {
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        require(status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "FBO incomplete: 0x${Integer.toHexString(status)}"
        }
    }

    private fun floatBuf(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(arr); position(0) }

    private fun buildShader(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src); GLES20.glCompileShader(id)
        val ok = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw RuntimeException("Shader compile error: $log")
        }
        return id
    }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = buildShader(GLES20.GL_VERTEX_SHADER, VS)
        val fs = buildShader(GLES20.GL_FRAGMENT_SHADER, FS)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs); GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1); GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Program link error: $log")
        }
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        return prog
    }

    companion object {
        private const val VS = """
            attribute vec2 aPos;
            attribute vec2 aTex;
            uniform mat4 uStMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vec4 uv = uStMatrix * vec4(aTex, 0.0, 1.0);
                vTex = uv.xy;
            }
        """

        private const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex);
            }
        """
    }
}
