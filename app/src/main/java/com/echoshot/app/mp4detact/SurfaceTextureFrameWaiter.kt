package com.echoshot.app.mp4detact

import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.Surface

class SurfaceTextureFrameWaiter(oesTexId: Int) {
    private val lock = Object()
    private var frameAvailable = false
    private val st = FloatArray(16)

    val surfaceTexture = SurfaceTexture(oesTexId).apply {
        setOnFrameAvailableListener {
            synchronized(lock) { frameAvailable = true; lock.notifyAll() }
        }
    }
    val surface: Surface = Surface(surfaceTexture)

    /** timeoutMs 안에 새 프레임 오기를 기다림 */
    fun awaitNewFrame(timeoutMs: Long): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        synchronized(lock) {
            while (!frameAvailable) {
                val wait = end - SystemClock.uptimeMillis()
                if (wait <= 0) return false
                lock.wait(wait)
            }
            frameAvailable = false
        }
        return true
    }

    /** texImage 갱신 후 ST 행렬 반환 */
    fun updateTexImage(): FloatArray {
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(st)
        return st
    }

    fun release() {
        surface.release()
        surfaceTexture.release()
    }
}
