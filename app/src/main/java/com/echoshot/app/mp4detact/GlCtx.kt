
package com.echoshot.app.mp4detact

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20


// GlCtx.kt
class GlCtx {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null

    @Synchronized fun makeCurrent() {
        if (display == EGL14.EGL_NO_DISPLAY) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }

            val vers = IntArray(2)
            check(EGL14.eglInitialize(display, vers, 0, vers, 1)) { "eglInitialize failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }

            val cfgAttr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val cfg = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, cfgAttr, 0, cfg, 0, 1, num, 0)) { "eglChooseConfig failed" }
            config = cfg[0]

            val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttr, 0)

            val pbAttr = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, config, pbAttr, 0)
        }

        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw IllegalArgumentException("eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    @Synchronized fun release() {
        try { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } catch (_:Throwable) {}
        try { if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface) } catch (_:Throwable) {}
        try { if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context) } catch (_:Throwable) {}
        try { if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display) } catch (_:Throwable) {}
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        config = null
    }
}


