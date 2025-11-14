package com.echoshot.app.mp4detact.models.face

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.echoshot.app.mp4detact.data.FaceVec
import com.echoshot.app.mp4detact.engine.GlLetterboxFbo
import com.echoshot.app.mp4detact.models.FaceEmbedder as IFaceEmbedder
import org.opencv.core.Rect
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class FaceEmbedder(
    private val ctx: Context,
    private val assetModelPath: String = "MobileFaceNet_fp32.tflite",
    private val inputSize: Int = 112,
) : IFaceEmbedder, AutoCloseable {

    companion object { private const val TAG = "FaceEmbedder" }

    private var interpreter: Interpreter? = null
    private var delegate: Delegate? = null

    private lateinit var inBuffer: ByteBuffer   // [1,112,112,3] float32
    private lateinit var outBuffer: ByteBuffer  // [1,128]      float32
    private val outDim = 128

    // ---------- IFaceEmbedder ----------
    override fun open() = openWith(useGpu = false, numThreads = 4)

    override fun close() {
        // [LOG]
        Log.i(TAG, "close() called")
        try { interpreter?.close() } catch (_: Throwable) {}
        try { delegate?.close() } catch (_: Throwable) {}
        interpreter = null
        delegate = null
    }

    fun embedFromLetterbox(
        rgba: ByteBuffer,
        bufferW: Int,
        bufferH: Int,
        meta: GlLetterboxFbo.LetterboxMeta,
        headBoxOriginal: org.opencv.core.Rect
    ): FaceVec? {
        // [LOG]
        Log.d(TAG, "embedFromLetterbox() buffer=${bufferW}x${bufferH} meta(scale=${meta.scale}, padX=${meta.padX}, padY=${meta.padY}) headOrig=$headBoxOriginal")

        val t0 = System.nanoTime()
        val lbBmp = rgbaToBitmap(rgba, bufferW, bufferH)

        val x1 = (headBoxOriginal.x * meta.scale + meta.padX).toInt()
        val y1 = (headBoxOriginal.y * meta.scale + meta.padY).toInt()
        val x2 = ((headBoxOriginal.x + headBoxOriginal.width)  * meta.scale + meta.padX).toInt()
        val y2 = ((headBoxOriginal.y + headBoxOriginal.height) * meta.scale + meta.padY).toInt()

        val lbRect = org.opencv.core.Rect(
            x1.coerceIn(0, bufferW - 1),
            y1.coerceIn(0, bufferH - 1),
            (x2 - x1).coerceIn(1, bufferW),
            (y2 - y1).coerceIn(1, bufferH)
        )

        // [LOG] 얼굴 크롭 박스
        Log.d(TAG, "embedFromLetterbox() lbRect=$lbRect")

        val emb = embedBitmap(lbBmp, lbRect)
        lbBmp.recycle()

        val ms = (System.nanoTime() - t0) / 1e6
        Log.i(TAG, "embedFromLetterbox() done=${emb != null} in ${"%.2f".format(ms)} ms")
        return emb?.let { FaceVec(it) }
    }

    override fun embed(
        rgba: ByteBuffer,
        srcW: Int,
        srcH: Int,
        meta: GlLetterboxFbo.LetterboxMeta,
        headRect: Rect
    ): FaceVec? {
        // [LOG]
        Log.d(TAG, "embed() src=${srcW}x$srcH meta(scale=${meta.scale}, padX=${meta.padX}, padY=${meta.padY}) head=$headRect")

        val t0 = System.nanoTime()

        val pixels = rgba.capacity() / 4
        val side = sqrt(pixels.toDouble()).toInt().coerceAtLeast(1)
        val bufW = side
        val bufH = side
        // [LOG]
        Log.d(TAG, "embed() inferred letterbox buffer=${bufW}x$bufH (from ${rgba.capacity()} bytes)")

        val lbBmp = rgbaToBitmap(rgba, bufW, bufH)

        val x1 = (headRect.x * meta.scale + meta.padX).toInt()
        val y1 = (headRect.y * meta.scale + meta.padY).toInt()
        val x2 = ((headRect.x + headRect.width)  * meta.scale + meta.padX).toInt()
        val y2 = ((headRect.y + headRect.height) * meta.scale + meta.padY).toInt()
        val lbRect = Rect(
            x1.coerceIn(0, bufW - 1),
            y1.coerceIn(0, bufH - 1),
            (x2 - x1).coerceIn(1, bufW),
            (y2 - y1).coerceIn(1, bufH)
        )
        Log.d(TAG, "embed() lbRect=$lbRect")

        val emb = embedBitmap(lbBmp, lbRect)
        lbBmp.recycle()

        val ms = (System.nanoTime() - t0) / 1e6
        Log.i(TAG, "embed() done=${emb != null} in ${"%.2f".format(ms)} ms")
        return emb?.let { FaceVec(it) }
    }
    // ---------- IFaceEmbedder 끝 ----------

    fun openWith(useGpu: Boolean = false, numThreads: Int = 4) {
        close()

        val opts = Interpreter.Options().apply { setNumThreads(numThreads) }
        if (useGpu) {
            try {
                delegate = GpuDelegate().also { opts.addDelegate(it) }
                Log.i(TAG, "GPU delegate enabled")
            } catch (t: Throwable) {
                Log.w(TAG, "GPU delegate unavailable; fallback to CPU", t)
            }
        }

        val mapped = FileUtil.loadMappedFile(ctx, assetModelPath)
        interpreter = Interpreter(mapped, opts)

        inBuffer  = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
        outBuffer = ByteBuffer.allocateDirect(outDim * 4).order(ByteOrder.nativeOrder())

        // [LOG]
        Log.i(TAG, "openWith() model=$assetModelPath input=${inputSize}x$inputSize threads=$numThreads useGpu=$useGpu")
    }

    // ---------- 내부 구현 ----------
    private fun embedBitmap(src: Bitmap, faceBox: Rect?): FloatArray? {
        val tfl = interpreter ?: run {
            Log.w(TAG, "embedBitmap: interpreter is null")
            return null
        }

        // [LOG]
        Log.d(TAG, "embedBitmap() src=${src.width}x${src.height} faceBox=$faceBox")
        val t0 = System.nanoTime()

        val roi = cropAndResize(src, faceBox, inputSize, inputSize)
        if (roi == null) {
            Log.w(TAG, "embedBitmap: cropAndResize returned null (box too small?)")
            return null
        }
        packInput(roi, inBuffer)
        roi.recycle()

        outBuffer.clear()
        tfl.run(inBuffer, outBuffer)
        outBuffer.rewind()

        val out = FloatArray(outDim)
        outBuffer.asFloatBuffer().get(out)
        l2NormalizeInPlace(out)

        val ms = (System.nanoTime() - t0) / 1e6
        Log.d(TAG, "embedBitmap() ok in ${"%.2f".format(ms)} ms")
        return out
    }

    private fun rgbaToBitmap(rgba: ByteBuffer, w: Int, h: Int): Bitmap {
        val need = w * h * 4
        require(rgba.capacity() >= need) {
            "RGBA buffer too small: have=${rgba.capacity()} need=$need (w=$w,h=$h)"
        }
        rgba.rewind()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        var i = 0
        while (i < pixels.size) {
            val r = rgba.get().toInt() and 0xFF
            val g = rgba.get().toInt() and 0xFF
            val b = rgba.get().toInt() and 0xFF
            val a = rgba.get().toInt() and 0xFF
            pixels[i++] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return bmp.apply { setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    private fun cropAndResize(src: Bitmap, box: Rect?, dstW: Int, dstH: Int): Bitmap? {
        if (box == null) {
            Log.d(TAG, "cropAndResize: box=null → full image resize to ${dstW}x$dstH")
            return Bitmap.createScaledBitmap(src, dstW, dstH, true)
        }

        val left   = max(0, box.x)
        val top    = max(0, box.y)
        val right  = min(src.width,  box.x + box.width)
        val bottom = min(src.height, box.y + box.height)
        val cropW = right - left
        val cropH = bottom - top
        
        // 얼굴 크기 검증 (너무 작거나 큰 얼굴 제외)
        if (cropW < 16 || cropH < 16 || cropW > 300 || cropH > 300) {
            Log.w(TAG, "cropAndResize: face too small/large ${cropW}x${cropH} from src=${src.width}x${src.height}")
            return null
        }
        
        if (cropW < 2 || cropH < 2) {
            Log.w(TAG, "cropAndResize: invalid crop (${cropW}x${cropH}) from src=${src.width}x${src.height}")
            return null
        }

        return try {
            val cropped = Bitmap.createBitmap(src, left, top, cropW, cropH)
            val out = Bitmap.createScaledBitmap(cropped, dstW, dstH, true)
            if (cropped != out) cropped.recycle()
            Log.d(TAG, "cropAndResize: crop=${cropW}x${cropH} -> ${dstW}x$dstH")
            out
        } catch (t: Throwable) {
            Log.w(TAG, "cropAndResize failed: ${t.message}")
            null
        }
    }

    private fun packInput(bmp: Bitmap, buf: ByteBuffer) {
        buf.clear()
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val scale = 0.0078125f // 1/128
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = (p) and 0xFF
            buf.putFloat((r - 128f) * scale)
            buf.putFloat((g - 128f) * scale)
            buf.putFloat((b - 128f) * scale)
            i++
        }
        buf.rewind()
        // [LOG]
        Log.d(TAG, "packInput: packed ${w}x$h → NHWC float32 (${buf.capacity()} bytes)")
    }

    private fun l2NormalizeInPlace(v: FloatArray) {
        var ss = 0f
        for (x in v) ss += x * x
        val inv = if (ss > 0f) 1f / sqrt(ss) else 1f
        for (i in v.indices) v[i] *= inv
    }

    // (옵션) 유틸
    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        val n = min(a.size, b.size)
        for (i in 0 until n) { val x=a[i]; val y=b[i]; dot+=x*y; na+=x*x; nb+=y*y }
        if (na == 0f || nb == 0f) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).coerceIn(-1f, 1f)
    }

    fun l2(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        var ss = 0f
        for (i in 0 until n) { val d = a[i] - b[i]; ss += d * d }
        return sqrt(ss)
    }
}
