package com.echoshot.app.mp4detact.models.pose

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.echoshot.app.mp4detact.data.PoseDetection
import com.echoshot.app.mp4detact.data.Keypoint

/**
 * YOLO11n-pose TFLite 추론 엔진
 * 
 * 출력 형식: [1, 56, 8400] 또는 [1, 8400, 56]
 * - 56 = 4 (bbox: x,y,w,h) + 1 (conf) + 51 (17 keypoints × 3)
 * - 8400 = detection 후보 수
 */
class Yolo11PoseTflite(
    private val ctx: Context,
    private val modelPath: String = "yolo11n-pose_float16.tflite",
    private val inputSize: Int = 640,
    private val scoreThr: Float = 0.25f,
    private val nmsThr: Float = 0.50f,
    private val maxPersonsPerFrame: Int = 5
) {

    companion object {
        private const val TAG = "Yolo11Pose"
        private const val DEBUG = true
        
        // YOLO11-pose 출력 feature 수: 4(bbox) + 1(conf) + 51(17kp×3) = 56
        const val POSE_FEATURES = 56
        const val NUM_KEYPOINTS = 17
    }

    private inline fun logd(msg: () -> String) { if (DEBUG) Log.d(TAG, msg()) }
    private inline fun logi(msg: () -> String) { if (DEBUG) Log.i(TAG, msg()) }
    private inline fun logw(msg: () -> String) { Log.w(TAG, msg()) }

    private var interpreter: Interpreter? = null
    private var delegate: Delegate? = null

    // 입력 텐서 메타 & 버퍼
    private lateinit var inTensor: Tensor
    private var inType: DataType = DataType.FLOAT32
    private var inShape: IntArray = intArrayOf()
    private var inIsNHWC: Boolean = true
    private var inW = 0
    private var inH = 0
    private lateinit var inBuffer: ByteBuffer
    private var inQuant: Tensor.QuantizationParams? = null

    // CHW 대비 임시 RGB 채널 버퍼
    private var tmpR: ByteArray? = null
    private var tmpG: ByteArray? = null
    private var tmpB: ByteArray? = null

    // 출력 포맷 힌트
    private var outFeat: Int = POSE_FEATURES
    private var outCount: Int = 0   // 8400 등
    private var outLayout: Int = 0  // 0: [1,56,N], 1: [1,N,56]

    // --- temporal smoothing state ---
    private var prevDet: PoseDetection? = null
    private var prevAge: Int = 0
    private val holdMaxFrames = 2
    private val decayPerMiss = 0.9f

    fun open(useGpu: Boolean = true) {
        val modelBuffer = loadModelMapped(modelPath)
        val opts = Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        }
        if (useGpu) {
            try {
                delegate = GpuDelegate()
                opts.addDelegate(delegate)
                Log.i(TAG, "GPU delegate enabled")
            } catch (e: Throwable) {
                Log.w(TAG, "GPU delegate unavailable, fallback to CPU", e)
            }
        }
        val tfl = Interpreter(modelBuffer, opts)
        interpreter = tfl

        // ---- 입력 텐서 ----
        inTensor = tfl.getInputTensor(0)
        inType = inTensor.dataType()
        inShape = inTensor.shape() // [1,640,640,3] 또는 [1,3,640,640]
        inIsNHWC = inShape.size == 4 && inShape[3] == 3
        inH = if (inIsNHWC) inShape[1] else inShape[2]
        inW = if (inIsNHWC) inShape[2] else inShape[3]
        if (inH != inputSize || inW != inputSize) {
            Log.w(TAG, "Model expects ${inW}x${inH}, app configured $inputSize. Using model shape.")
        }
        val elems = inShape.fold(1) { acc, v -> acc * v }
        val bpe = when (inType) {
            DataType.FLOAT32 -> 4
            DataType.UINT8, DataType.INT8 -> 1
            else -> 4  // 기타 타입은 FLOAT32로 간주
        }
        inBuffer = ByteBuffer.allocateDirect(elems * bpe).order(ByteOrder.nativeOrder())
        inQuant = if (inType == DataType.UINT8 || inType == DataType.INT8) inTensor.quantizationParams() else null

        // CHW 대비 임시 채널 버퍼
        val pix = inW * inH
        if (!inIsNHWC) {
            tmpR = ByteArray(pix)
            tmpG = ByteArray(pix)
            tmpB = ByteArray(pix)
        }

        Log.i(TAG, "INPUT tensor -> shape=${inShape.contentToString()}, type=$inType, bytes=${elems * bpe}, layout=${if (inIsNHWC) "NHWC" else "NCHW"}")

        // ---- 출력 텐서 ----
        val out0 = tfl.getOutputTensor(0)
        val os = out0.shape() // [1,56,8400] 또는 [1,8400,56]
        logi { "OUTPUT[0] tensor -> shape=${os.contentToString()}, type=${out0.dataType()}" }
        
        // YOLO11-pose는 56 features
        outFeat = when {
            os.size == 3 && os[1] == POSE_FEATURES -> POSE_FEATURES
            os.size == 3 && os[2] == POSE_FEATURES -> POSE_FEATURES
            else -> POSE_FEATURES
        }
        if (os.size == 3) {
            if (os[1] == outFeat) { outLayout = 0; outCount = os[2] }
            else { outLayout = 1; outCount = os[1] }
        } else { outLayout = -1; outCount = 0 }
        Log.i(TAG, "OUTPUT[0] tensor -> shape=${os.contentToString()}, type=${out0.dataType()} feat=$outFeat count=$outCount layout=${if (outLayout==0) "[1,56,N]" else if (outLayout==1) "[1,N,56]" else "other"}")
    }

    fun close() {
        try { interpreter?.close() } catch (_: Throwable) {}
        try { delegate?.close() } catch (_: Throwable) {}
        interpreter = null
        delegate = null
        tmpR = null; tmpG = null; tmpB = null
    }

    /**
     * @param rgba  RGBA inW x inH (U8) ByteBuffer (position=0)
     * @param scale, padX, padY, srcW, srcH : 레터박스 파라미터(역보정)
     */
    fun infer(
        rgba: ByteBuffer,
        scale: Float,
        padX: Int,
        padY: Int,
        srcW: Int,
        srcH: Int
    ): List<PoseDetection> {
        val tfl = interpreter ?: error("Interpreter not opened")

        val t0 = System.nanoTime()
        packInputFromRGBA(rgba)
        val t1 = System.nanoTime()

        val outTensor = tfl.getOutputTensor(0)
        val os = outTensor.shape()
        val outAny: Any = when (os.size) {
            3 -> Array(os[0]) { Array(os[1]) { FloatArray(os[2]) } }
            2 -> Array(os[0]) { FloatArray(os[1]) }
            else -> FloatArray(os.last())
        }
        val outputs = hashMapOf(0 to outAny)

        val t2 = System.nanoTime()
        tfl.runForMultipleInputsOutputs(arrayOf(inBuffer), outputs)
        val t3 = System.nanoTime()

        logd { "TIMES pack=${(t1-t0)/1e6}ms prepare=${(t2-t1)/1e6}ms run=${(t3-t2)/1e6}ms" }
        logd { "OUT shape=${os.contentToString()} feat=$outFeat count=$outCount" }

        val rows: Sequence<FloatArray> = rowsFromOutput(os, outAny)

        val (useScale, usePadX, usePadY) = computeLetterboxParams(scale, padX, padY, srcW, srcH)
        logi { "Letterbox params: scale=$useScale pad=($usePadX,$usePadY) src=${srcW}x$srcH in=${inW}x$inH" }

        // 디코딩
        val candidates = decodePoseDetections(rows, srcW, srcH, useScale, usePadX, usePadY)
        logi { "decode → candidates=${candidates.size}" }

        // NMS
        val nmsed = nmsPose(candidates, nmsThr, maxPersonsPerFrame)
        logi { "FINAL persons=${nmsed.size} (thr=$scoreThr, nms=$nmsThr, max=$maxPersonsPerFrame)" }

        // Temporal stabilization
        var stable = nmsed
        if (stable.isEmpty()) {
            prevDet?.let { p ->
                if (prevAge < holdMaxFrames) {
                    val kept = p.copy(score = p.score * decayPerMiss)
                    stable = listOf(kept)
                    prevAge += 1
                    logd { "TEMP-HOLD keep prev pose age=$prevAge" }
                }
            }
        } else {
            // OKS 기반으로 가장 유사한 detection 선택
            val best = stable.maxByOrNull { d -> 
                prevDet?.let { p -> d.oksWithOther(p) } ?: d.score
            }!!
            prevDet = best
            prevAge = 0
        }

        if (stable.size > maxPersonsPerFrame) {
            stable = stable.take(maxPersonsPerFrame)
        }

        if (stable.isEmpty()) {
            logw { "No pose detections." }
        }

        return stable
    }

    /**
     * YOLO11-pose 출력 디코딩
     * row 구조: [x, y, w, h, conf, kp0_x, kp0_y, kp0_conf, ..., kp16_x, kp16_y, kp16_conf]
     */
    private fun decodePoseDetections(
        rows: Sequence<FloatArray>,
        srcW: Int,
        srcH: Int,
        scale: Float,
        padX: Float,
        padY: Float
    ): List<PoseDetection> {
        val out = ArrayList<PoseDetection>(32)
        val sc = if (scale == 0f) 1f else scale
        
        var rowCount = 0
        var maxConf = 0f
        var passedThr = 0

        for (row in rows) {
            rowCount++
            
            // 첫 몇 row 디버깅
            if (rowCount <= 3) {
                val preview = row.take(10).map { "%.3f".format(it) }
                logd { "ROW[$rowCount] size=${row.size} first10=$preview" }
            }
            
            if (row.size < POSE_FEATURES) {
                if (rowCount <= 3) logd { "ROW[$rowCount] skipped: size=${row.size} < $POSE_FEATURES" }
                continue
            }

            // Bbox: x_center, y_center, width, height (정규화된 값 0~1)
            val xcNorm = row[0]
            val ycNorm = row[1]
            val wNorm = row[2]
            val hNorm = row[3]
            
            // Confidence score
            val conf = row[4]
            if (conf > maxConf) maxConf = conf
            
            if (conf < scoreThr) continue
            passedThr++

            // 정규화된 좌표를 모델 입력 크기(640)로 변환
            val xc = xcNorm * inW
            val yc = ycNorm * inH
            val w = wNorm * inW
            val h = hNorm * inH

            // 모델 좌표 → 원본 좌표 변환
            val x1m = xc - w / 2f
            val y1m = yc - h / 2f
            val x2m = xc + w / 2f
            val y2m = yc + h / 2f

            // Unletterbox
            val x1 = ((x1m - padX) / sc).coerceIn(0f, (srcW - 1).toFloat())
            val y1 = ((y1m - padY) / sc).coerceIn(0f, (srcH - 1).toFloat())
            val x2 = ((x2m - padX) / sc).coerceIn(0f, (srcW - 1).toFloat())
            val y2 = ((y2m - padY) / sc).coerceIn(0f, (srcH - 1).toFloat())

            // 너무 작은 박스 무시
            if (x2 - x1 < 2f || y2 - y1 < 2f) continue

            // Keypoints 디코딩 (17개) - 정규화된 좌표
            val keypoints = ArrayList<Keypoint>(NUM_KEYPOINTS)
            for (i in 0 until NUM_KEYPOINTS) {
                val baseIdx = 5 + i * 3
                if (baseIdx + 2 >= row.size) {
                    keypoints.add(Keypoint(0f, 0f, 0f))
                    continue
                }
                
                // 정규화된 keypoint 좌표를 모델 크기로 변환
                val kpXm = row[baseIdx] * inW
                val kpYm = row[baseIdx + 1] * inH
                val kpConf = row[baseIdx + 2]

                // Keypoint 좌표도 unletterbox
                val kpX = ((kpXm - padX) / sc).coerceIn(0f, (srcW - 1).toFloat())
                val kpY = ((kpYm - padY) / sc).coerceIn(0f, (srcH - 1).toFloat())

                keypoints.add(Keypoint(kpX, kpY, kpConf))
            }

            out.add(PoseDetection(x1, y1, x2, y2, conf, keypoints))
        }

        logi { "DECODE STATS: rows=$rowCount maxConf=${"%.4f".format(maxConf)} passedThr=$passedThr final=${out.size}" }
        return out
    }

    private fun nmsPose(
        list: List<PoseDetection>,
        iouThr: Float,
        maxDet: Int
    ): List<PoseDetection> {
        if (list.isEmpty()) return emptyList()
        val sorted = list.sortedByDescending { it.score }
        val kept = ArrayList<PoseDetection>(min(sorted.size, maxDet))
        val removed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (removed[i]) continue
            val a = sorted[i]
            kept += a
            if (kept.size >= maxDet) break

            for (j in (i + 1) until sorted.size) {
                if (removed[j]) continue
                val b = sorted[j]
                if (iou(a, b) > iouThr) removed[j] = true
            }
        }
        return kept
    }

    private fun iou(a: PoseDetection, b: PoseDetection): Float {
        val x1 = max(a.x1, b.x1)
        val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2)
        val y2 = min(a.y2, b.y2)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = max(0f, a.x2 - a.x1) * max(0f, a.y2 - a.y1)
        val areaB = max(0f, b.x2 - b.x1) * max(0f, b.y2 - b.y1)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    // --------- 입력 패킹 ---------
    private fun packInputFromRGBA(rgba: ByteBuffer) {
        val needBytes = inW * inH * 4
        require(rgba.capacity() >= needBytes) {
            "RGBA buffer too small: have=${rgba.capacity()} need=$needBytes (in=${inW}x$inH)"
        }

        rgba.position(0)
        inBuffer.position(0)
        val pix = inW * inH

        when (inType) {
            DataType.FLOAT32 -> {
                val fb = inBuffer.asFloatBuffer()
                fb.clear()
                if (inIsNHWC) {
                    var i = 0
                    while (i < pix) {
                        val r = rgba.get().toInt() and 0xFF
                        val g = rgba.get().toInt() and 0xFF
                        val b = rgba.get().toInt() and 0xFF
                        rgba.get() // A skip
                        fb.put(r / 255f); fb.put(g / 255f); fb.put(b / 255f)
                        i++
                    }
                } else {
                    val rArr = tmpR!!; val gArr = tmpG!!; val bArr = tmpB!!
                    var i = 0
                    while (i < pix) {
                        rArr[i] = rgba.get(); gArr[i] = rgba.get(); bArr[i] = rgba.get(); rgba.get(); i++
                    }
                    var k = 0; while (k < pix) { fb.put((rArr[k].toInt() and 0xFF) / 255f); k++ }
                    k = 0; while (k < pix) { fb.put((gArr[k].toInt() and 0xFF) / 255f); k++ }
                    k = 0; while (k < pix) { fb.put((bArr[k].toInt() and 0xFF) / 255f); k++ }
                }
                inBuffer.limit(fb.position() * 4)
            }

            DataType.UINT8 -> {
                val q = inQuant
                val needQuant = q?.let { it.scale != 0f && (it.scale != 1f || it.zeroPoint != 0) } ?: false
                if (!needQuant) {
                    if (inIsNHWC) {
                        var i = 0; while (i < pix) {
                            inBuffer.put(rgba.get()); inBuffer.put(rgba.get()); inBuffer.put(rgba.get()); rgba.get(); i++
                        }
                    } else {
                        val rArr = tmpR!!; val gArr = tmpG!!; val bArr = tmpB!!
                        var i = 0; while (i < pix) { rArr[i]=rgba.get(); gArr[i]=rgba.get(); bArr[i]=rgba.get(); rgba.get(); i++ }
                        inBuffer.put(rArr); inBuffer.put(gArr); inBuffer.put(bArr)
                    }
                } else {
                    val inv = 1f / q!!.scale
                    val zp  = q.zeroPoint
                    if (inIsNHWC) {
                        var i = 0; while (i < pix) {
                            val r = (((rgba.get().toInt() and 0xFF)/255f)*inv + zp).roundToInt().coerceIn(0,255).toByte()
                            val g = (((rgba.get().toInt() and 0xFF)/255f)*inv + zp).roundToInt().coerceIn(0,255).toByte()
                            val b = (((rgba.get().toInt() and 0xFF)/255f)*inv + zp).roundToInt().coerceIn(0,255).toByte()
                            rgba.get()
                            inBuffer.put(r); inBuffer.put(g); inBuffer.put(b)
                            i++
                        }
                    } else {
                        val rArr = tmpR!!; val gArr = tmpG!!; val bArr = tmpB!!
                        var i = 0; while (i < pix) { rArr[i]=rgba.get(); gArr[i]=rgba.get(); bArr[i]=rgba.get(); rgba.get(); i++ }
                        val qByte: (Int)->Byte = { v -> (((v and 0xFF)/255f)*inv + zp).roundToInt().coerceIn(0,255).toByte() }
                        i = 0; while (i<pix) { inBuffer.put(qByte(rArr[i].toInt())); i++ }
                        i = 0; while (i<pix) { inBuffer.put(qByte(gArr[i].toInt())); i++ }
                        i = 0; while (i<pix) { inBuffer.put(qByte(bArr[i].toInt())); i++ }
                    }
                }
            }

            DataType.INT8 -> {
                val s = inQuant?.scale ?: 1f
                val zero = inQuant?.zeroPoint ?: 0
                val inv = if (s == 0f) 1f else 1f / s
                if (inIsNHWC) {
                    var i = 0
                    while (i < pix) {
                        val r = (((rgba.get().toInt() and 0xFF) / 255f) * inv + zero).roundToInt().coerceIn(-128, 127).toByte()
                        val g = (((rgba.get().toInt() and 0xFF) / 255f) * inv + zero).roundToInt().coerceIn(-128, 127).toByte()
                        val b = (((rgba.get().toInt() and 0xFF) / 255f) * inv + zero).roundToInt().coerceIn(-128, 127).toByte()
                        rgba.get()
                        inBuffer.put(r); inBuffer.put(g); inBuffer.put(b)
                        i++
                    }
                } else {
                    val rArr = tmpR!!; val gArr = tmpG!!; val bArr = tmpB!!
                    var i = 0
                    while (i < pix) { rArr[i]=rgba.get(); gArr[i]=rgba.get(); bArr[i]=rgba.get(); rgba.get(); i++ }
                    val qByte: (Int)->Byte = { v -> (((v and 0xFF)/255f)*inv + zero).roundToInt().coerceIn(-128,127).toByte() }
                    i = 0; while (i < pix) { inBuffer.put(qByte(rArr[i].toInt())); i++ }
                    i = 0; while (i < pix) { inBuffer.put(qByte(gArr[i].toInt())); i++ }
                    i = 0; while (i < pix) { inBuffer.put(qByte(bArr[i].toInt())); i++ }
                }
            }

            else -> error("Unsupported input type: $inType")
        }
        inBuffer.rewind()
    }

    private fun computeLetterboxParams(
        scaleIn: Float,
        padXIn: Int,
        padYIn: Int,
        srcW: Int,
        srcH: Int
    ): Triple<Float, Float, Float> {
        val scaleOk = scaleIn.isFinite() && scaleIn > 0f
        val padOk = padXIn >= 0 && padYIn >= 0
        if (scaleOk && padOk) return Triple(scaleIn, padXIn.toFloat(), padYIn.toFloat())

        val s = min(inW.toFloat() / srcW, inH.toFloat() / srcH)
        val newW = srcW * s
        val newH = srcH * s
        val pX = ((inW - newW) * 0.5f)
        val pY = ((inH - newH) * 0.5f)
        return Triple(s, pX, pY)
    }

    private fun rowsFromOutput(os: IntArray, outAny: Any): Sequence<FloatArray> {
        return when (os.size) {
            3 -> {
                val a = os[1]; val b = os[2]
                val out3 = outAny as Array<Array<FloatArray>>
                sequence {
                    if (a == POSE_FEATURES) {
                        // [1, 56, N] → transpose
                        val n = b
                        for (j in 0 until n) {
                            val row = FloatArray(POSE_FEATURES)
                            for (k in 0 until POSE_FEATURES) { row[k] = out3[0][k][j] }
                            yield(row)
                        }
                    } else if (b == POSE_FEATURES) {
                        // [1, N, 56]
                        val n = a
                        for (j in 0 until n) {
                            yield(out3[0][j])
                        }
                    } else {
                        // fallback
                        val feat = min(a, b); val n = max(a, b)
                        for (j in 0 until n) {
                            val row = FloatArray(feat)
                            if (a == feat) {
                                for (k in 0 until feat) { row[k] = out3[0][k][j] }
                            } else {
                                System.arraycopy(out3[0][j], 0, row, 0, min(feat, out3[0][j].size))
                            }
                            yield(row)
                        }
                    }
                }
            }
            2 -> {
                val out2 = outAny as Array<FloatArray>
                sequenceOf(out2[0])
            }
            else -> {
                val out1 = outAny as FloatArray
                sequenceOf(out1)
            }
        }
    }

    private fun loadModelMapped(pathOrAsset: String): MappedByteBuffer {
        return try {
            ctx.assets.openFd(pathOrAsset).use { afd ->
                FileInputStream(afd.fileDescriptor).use { fis ->
                    fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                }
            }
        } catch (e: Throwable) {
            val file = File(pathOrAsset)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val ch = fis.channel
                    ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                }
            }
        }
    }
}

