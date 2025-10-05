package com.echoshot.app.mp4detact.engine

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * OES 텍스처를 640×640 RGBA FBO로 렌더(레터박스 포함)하고,
 * 필요에 따라 RGB(U8) 또는 RGB(F32, NHWC) 버퍼를 만들어 반환.
 */
class GlLetterboxFbo(
    private val gl: GlCtx,
    private val dstW: Int = 640,
    private val dstH: Int = 640,
) {
    data class LetterboxMeta(
        val scale: Float,
        val padX: Int,
        val padY: Int,
        val srcW: Int,
        val srcH: Int,
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

    // 읽기 스크래치: RGBA(8bit) 640x640
    private val rgbaScratch: ByteBuffer =
        ByteBuffer.allocateDirect(dstW * dstH * 4).order(ByteOrder.nativeOrder())

    // 선택적 재사용 버퍼
    private val rgbU8: ByteBuffer =
        ByteBuffer.allocateDirect(dstW * dstH * 3).order(ByteOrder.nativeOrder())

    private val rgbF32: ByteBuffer =
        ByteBuffer.allocateDirect(dstW * dstH * 3 * 4).order(ByteOrder.nativeOrder())

    init {
        gl.makeCurrent()

        // 컬러 텍스처
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
            dstW, dstH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // FBO (깊이/스텐실 없음)
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

        // 셰이더/프로그램
        program = buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")

        // 풀스크린 사각형(-1..1)
        quad = floatBuf(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f,  1f,
                1f,  1f,
            )
        )
        // 기본 UV (0..1)
        uv = floatBuf(
            floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f,
            )
        )

        // 깊이 사용 안 함
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    fun render(oesTexId: Int, stMatrix: FloatArray, srcW: Int, srcH: Int): LetterboxMeta {
        gl.makeCurrent()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)

        // 배경(레터박스 영역) 클리어
        GLES20.glViewport(0, 0, dstW, dstH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 레터박스 스케일 & 패딩
        val srcAR = srcW.toFloat() / srcH
        val dstAR = dstW.toFloat() / dstH
        val drawW: Int
        val drawH: Int
        val scale: Float
        val padX: Int
        val padY: Int
        if (srcAR > dstAR) {
            scale = dstW / srcW.toFloat()
            drawW = dstW
            drawH = (srcH * scale).roundToInt()
            padX = 0
            padY = (dstH - drawH) / 2
        } else {
            scale = dstH / srcH.toFloat()
            drawH = dstH
            drawW = (srcW * scale).roundToInt()
            padX = (dstW - drawW) / 2
            padY = 0
        }
        GLES20.glViewport(padX, padY, drawW, drawH)

        // 드로우
        GLES20.glUseProgram(program)

        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)

        uv.position(0)
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

        // FBO 유지: read 호출에서 unbind
        return LetterboxMeta(scale, padX, padY, srcW, srcH)
    }

    /** RGBA 8bit 그대로 읽기(디버그용). */
    fun readRgbaU8(): ByteBuffer {
        gl.makeCurrent()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        // 패킹 정렬 1바이트(행 패딩 제거)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        rgbaScratch.position(0)
        GLES20.glReadPixels(0, 0, dstW, dstH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaScratch)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        rgbaScratch.position(0)
        return rgbaScratch
    }

    /**
     * RGB(U8)로 변환해 반환. (640*640*3 = 1,228,800 bytes)
     * TFLite uint8 입력용.
     */
    fun readRgbU8(): ByteBuffer {
        val rgba = readRgbaU8()
        rgbU8.position(0)
        val total = dstW * dstH
        var i = 0
        while (i < total) {
            val r = rgba.get().toInt() and 0xFF
            val g = rgba.get().toInt() and 0xFF
            val b = rgba.get().toInt() and 0xFF
            rgba.get() // skip A
            rgbU8.put(r.toByte())
            rgbU8.put(g.toByte())
            rgbU8.put(b.toByte())
            i++
        }
        rgbU8.position(0)
        return rgbU8
    }

    /**
     * RGB(F32, NHWC)로 변환해 반환. (640*640*3*4 = 4,915,200 bytes)
     * TFLite float32 입력 모델에 바로 사용 가능.
     * @param normalize true면 [0..255] → [0..1]로 스케일
     */
    fun readRgbFloat(normalize: Boolean = true): ByteBuffer {
        val rgba = readRgbaU8()
        rgbF32.position(0)
        val scale = if (normalize) (1.0f / 255.0f) else 1.0f
        val total = dstW * dstH
        var i = 0
        while (i < total) {
            val r = (rgba.get().toInt() and 0xFF) * scale
            val g = (rgba.get().toInt() and 0xFF) * scale
            val b = (rgba.get().toInt() and 0xFF) * scale
            rgba.get() // skip A
            rgbF32.putFloat(r)
            rgbF32.putFloat(g)
            rgbF32.putFloat(b)
            i++
        }
        rgbF32.position(0)
        return rgbF32
    }

    fun release() {
        gl.makeCurrent()
        val ids = IntArray(1)
        if (fboId != 0) {
            ids[0] = fboId
            GLES20.glDeleteFramebuffers(1, ids, 0)
            fboId = 0
        }
        if (colorTex != 0) {
            ids[0] = colorTex
            GLES20.glDeleteTextures(1, ids, 0)
            colorTex = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
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
            .apply {
                put(arr)
                position(0)
            }

    private fun buildShader(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw RuntimeException("Shader compile error: $log")
        }
        return id
    }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = buildShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = buildShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Program link error: $log")
        }
        // 셰이더 객체는 링크 후 삭제
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
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
