package com.echoshot.app.mp4detact.models.yolo

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
import com.echoshot.app.mp4detact.data.Detection
import com.echoshot.app.mp4detact.models.Detector

// Detection 클래스는 data/Types.kt에 정의되어 있음

class YoloTflite(
    private val ctx: Context,
    private val modelPath: String = "yolov8n_int8.tflite", // assets 기본값(원하면 수정)
    private val inputSize: Int = 640,
    private val scoreThr: Float = 0.25f,
    private val nmsThr: Float = 0.50f,
    private val labels: List<String>? = null,
    private val personOnly: Boolean = true,
    private val personClassId: Int = 0,
    private val maxPersonsPerFrame: Int = 3
) : Detector {

    companion object {
        private const val TAG = "YoloTflite"
        private const val DEBUG = true
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
    private var outFeat: Int = 84   // 84 또는 85
    private var outCount: Int = 0   // 8400 등
    private var outLayout: Int = 0  // 0: [1,84,N], 1: [1,N,84]

    // --- temporal smoothing state ---
    private var prevDet: Detection? = null
    private var prevAge: Int = 0
    private val holdMaxFrames = 2           // 공백일 때 최대 유지 프레임
    private val decayPerMiss = 0.9f         // 유지 시 점수 감쇠

    private enum class BoxGuess { XYWH_PX, XYWH_NORM, X1Y1X2Y2_PX, X1Y1X2Y2_NORM }

    /** 모델 출력 row[0..3]을 다양한 포맷으로 가정해서 "모델좌표(640x640 등)"의 x1y1x2y2로 변환. */
    private fun guessModelCorners(row: FloatArray): Pair<Quad, BoxGuess>? {
        if (row.size < 4) return null
        val a = row[0]; val b = row[1]; val c = row[2]; val d = row[3]

        fun quad(x1: Float, y1: Float, x2: Float, y2: Float) = Quad(x1, y1, x2, y2)
        fun valid(q: Quad): Boolean {
            val w = q.x2 - q.x1; val h = q.y2 - q.y1
            if (w <= 0f || h <= 0f) return false
            if (!w.isFinite() || !h.isFinite()) return false
            if (w > inW * 1.5f || h > inH * 1.5f) return false
            return true
        }
        fun area(q: Quad) = (q.x2 - q.x1) * (q.y2 - q.y1)

        val cands = arrayListOf<Pair<Quad, BoxGuess>>()
        cands += quad(a - c*0.5f, b - d*0.5f, a + c*0.5f, b + d*0.5f) to BoxGuess.XYWH_PX
        cands += quad((a - c*0.5f) * inW, (b - d*0.5f) * inH, (a + c*0.5f) * inW, (b + d*0.5f) * inH) to BoxGuess.XYWH_NORM
        cands += quad(a, b, c, d) to BoxGuess.X1Y1X2Y2_PX
        cands += quad(a * inW, b * inH, c * inW, d * inH) to BoxGuess.X1Y1X2Y2_NORM

        var best: Pair<Quad, BoxGuess>? = null
        var bestArea = -1f
        for (p in cands) {
            val q = p.first
            if (!valid(q)) continue
            val ar = area(q)
            if (ar > bestArea) { bestArea = ar; best = p }
        }
        return best
    }

    /** Detector 인터페이스: 무파라미터 open */
    override fun open() {
        open(useGpu = true)
    }

    /** 옵션 지정 가능한 open */
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
            else -> error("Unsupported input type: $inType")
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
        val os = out0.shape() // [1,84,8400] 또는 [1,8400,84]
        logi { "OUTPUT[0] tensor -> shape=${os.contentToString()}, type=${out0.dataType()}" }
        outFeat = when {
            os.size == 3 && (os[1] == 84 || os[1] == 85) -> os[1]
            os.size == 3 && (os[2] == 84 || os[2] == 85) -> os[2]
            else -> 84
        }
        if (os.size == 3) {
            if (os[1] == outFeat) { outLayout = 0; outCount = os[2] }
            else { outLayout = 1; outCount = os[1] }
        } else { outLayout = -1; outCount = 0 }
        Log.i(TAG, "OUTPUT[0] tensor -> shape=${os.contentToString()}, type=${out0.dataType()} feat=$outFeat count=$outCount layout=${if (outLayout==0) "[1,84,N]" else if (outLayout==1) "[1,N,84]" else "other"}")
    }

    override fun close() {
        try { interpreter?.close() } catch (_: Throwable) {}
        try { delegate?.close() } catch (_: Throwable) {}
        interpreter = null
        delegate = null
        tmpR = null; tmpG = null; tmpB = null
    }

    /**
     * @param rgba  RGBA inW x inH (U8) ByteBuffer (position=0)
     * @param scale, padX, padY, srcW, srcH : 레터박스 파라미터(역보정). 값이 비정상일 경우 자동 계산.
     */
    override fun infer(
        rgba: ByteBuffer,
        scale: Float,
        padX: Int,
        padY: Int,
        srcW: Int,
        srcH: Int
    ): List<Detection> {
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
        logd { "Letterbox params: scale=$useScale pad=($usePadX,$usePadY) src=${srcW}x$srcH in=${inW}x$inH" }

        // 정상 경로
        var candidates: List<Detection> =
            decodeRowsPersonOnly(
                rows, srcW, srcH,
                coordMode = CoordMode.MODEL_THEN_UNLETTERBOX,
                scale = useScale, padXf = usePadX, padYf = usePadY
            )

        logd { "decode MODE_MODEL_THEN_UNLETTERBOX → cand=${candidates.size}  sample=${sampleBox(candidates)}" }

        // 대체 경로
        if (candidates.isEmpty() || allTiny(candidates)) {
            val altA = decodeRowsPersonOnly(
                rowsFromOutput(os, outAny), srcW, srcH,
                coordMode = CoordMode.ASSUME_ORIGINAL
            )
            logd { "decode MODE_ASSUME_ORIGINAL → cand=${altA.size}  sample=${sampleBox(altA)}" }

            val sx = srcW.toFloat() / inW
            val sy = srcH.toFloat() / inH
            val altB = decodeRowsPersonOnly(
                rowsFromOutput(os, outAny), srcW, srcH,
                coordMode = CoordMode.MODEL_LINEAR_SCALE,
                scaleX = sx, scaleY = sy
            )
            logd { "decode MODEL_LINEAR_SCALE(sx=$sx, sy=$sy) → cand=${altB.size}  sample=${sampleBox(altB)}" }

            candidates = pickBetter(altA, altB)
            logd { "pickBetter → cand=${candidates.size}  sample=${sampleBox(candidates)}" }
        }

        val nmsed = nms(candidates, nmsThr, classAgnostic = true, maxDet = maxPersonsPerFrame)
        logi { "FINAL persons=${nmsed.size} (thr=$scoreThr, nms=$nmsThr, max=$maxPersonsPerFrame) sample=${sampleBox(nmsed)}" }

        // --- temporal stabilization ---
        var stable = nmsed

        if (stable.isEmpty()) {
            // 직전 박스가 있고, 너무 오래 안 지났다면 일정 프레임 유지
            prevDet?.let { p ->
                if (prevAge < holdMaxFrames) {
                    val kept = p.copy(score = (p.score * decayPerMiss))
                    stable = listOf(kept)
                    prevAge += 1
                    logd { "TEMP-HOLD keep prev box age=$prevAge ${sampleBox(stable)}" }
                }
            }
        } else {
            // 결과가 있으면 prev를 업데이트(이전과 IoU가 가장 큰 박스 선택)
            val best = stable.maxByOrNull { d -> iou(prevDet ?: d, d) }!!
            prevDet = best
            prevAge = 0
        }

        // 상한 적용은 안정화 후 결과에 적용
        if (stable.size > maxPersonsPerFrame) {
            stable = stable.take(maxPersonsPerFrame)
        }

        if (stable.isEmpty()) {
            logw { "No detections. Tips: check personClassId=$personClassId labelsPerson=${labels?.getOrNull(personClassId)}; try scoreThr=0.25; verify letterbox params." }
        }

        return stable
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
                val scale = inQuant?.scale ?: 1f
                val zero  = inQuant?.zeroPoint ?: 0
                val inv   = if (scale == 0f) 1f else 1f / scale
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

    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-x)))

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = max(a.x1.toFloat(), b.x1.toFloat())
        val y1 = max(a.y1.toFloat(), b.y1.toFloat())
        val x2 = min(a.x2.toFloat(), b.x2.toFloat())
        val y2 = min(a.y2.toFloat(), b.y2.toFloat())
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = max(0f, (a.x2 - a.x1).toFloat()) * max(0f, (a.y2 - a.y1).toFloat())
        val areaB = max(0f, (b.x2 - b.x1).toFloat()) * max(0f, (b.y2 - b.y1).toFloat())
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun nms(
        list: List<Detection>,
        iouThr: Float,
        classAgnostic: Boolean = true,
        maxDet: Int = 50
    ): List<Detection> {
        if (list.isEmpty()) return emptyList()
        val sorted = list.sortedByDescending { it.score }
        val kept = ArrayList<Detection>(min(sorted.size, maxDet))
        val removed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (removed[i]) continue
            val a = sorted[i]
            kept += a
            if (kept.size >= maxDet) break

            for (j in (i + 1) until sorted.size) {
                if (removed[j]) continue
                val b = sorted[j]
                val same = classAgnostic || (a.cls == b.cls)   // ← cls 로 수정
                if (same && iou(a, b) > iouThr) removed[j] = true
            }
        }
        return kept
    }

    /** 넘어온 scale/pad가 비정상이면 srcW/srcH와 inW/inH로 자동 계산 */
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

    private fun personClassIndex(): Int {
        if (!personOnly) return 0
        if (personClassId >= 0) return personClassId
        val idx = labels?.indexOfFirst { it.equals("person", ignoreCase = true) } ?: -1
        return if (idx >= 0) idx else 0
    }

    private enum class CoordMode {
        MODEL_THEN_UNLETTERBOX,   // (기본) 모델공간 → 역레터박스
        ASSUME_ORIGINAL,          // 이미 원본 좌표라고 가정 (그대로 clamp)
        MODEL_LINEAR_SCALE        // 모델공간을 srcW/srcH로 선형 스케일만 적용
    }

    private fun allTiny(list: List<Detection>, minSide: Float = 2f): Boolean {
        if (list.isEmpty()) return true
        for (d in list) {
            if ((d.x2 - d.x1) > minSide && (d.y2 - d.y1) > minSide) return false
        }
        return true
    }

    private fun pickBetter(a: List<Detection>, b: List<Detection>): List<Detection> {
        fun score(ls: List<Detection>): Pair<Int, Float> {
            val cnt = ls.size
            var areaSum = 0f
            for (d in ls) areaSum += (d.x2 - d.x1) * (d.y2 - d.y1)
            return cnt to areaSum
        }
        val sa = score(a); val sb = score(b)
        return when {
            sa.first != sb.first -> if (sa.first > sb.first) a else b
            else -> if (sa.second >= sb.second) a else b
        }
    }

    private fun rowsFromOutput(os: IntArray, outAny: Any): Sequence<FloatArray> {
        val featGuess = if (outFeat == 84 || outFeat == 85) outFeat else 84
        return when (os.size) {
            3 -> {
                val a = os[1]; val b = os[2]
                val out3 = outAny as Array<Array<FloatArray>>
                sequence {
                    if (a == featGuess) {
                        val n = b
                        for (j in 0 until n) {
                            val row = FloatArray(featGuess)
                            var k = 0
                            while (k < featGuess) { row[k] = out3[0][k][j]; k++ }
                            yield(row)
                        }
                    } else if (b == featGuess) {
                        val n = a
                        for (j in 0 until n) {
                            val src = out3[0][j]
                            yield(if (src.size == featGuess) src else src.copyOf(featGuess))
                        }
                    } else {
                        val feat = min(a, b); val n = max(a, b)
                        for (j in 0 until n) {
                            val row = FloatArray(feat)
                            if (a == feat) {
                                var k = 0; while (k < feat) { row[k] = out3[0][k][j]; k++ }
                            } else {
                                val src = out3[0][j]
                                System.arraycopy(src, 0, row, 0, min(feat, src.size))
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

    private fun decodeRowsPersonOnly(
        rows: Sequence<FloatArray>,
        srcW: Int,
        srcH: Int,
        coordMode: CoordMode,
        scale: Float = 1f,
        padXf: Float = 0f,
        padYf: Float = 0f,
        scaleX: Float = 1f,
        scaleY: Float = 1f
    ): ArrayList<Detection> {

        val out = ArrayList<Detection>(32)
        val personIdx = personClassIndex()
        if (personIdx < 0) { logw { "personClassIndex() < 0" }; return out }

        var tot = 0
        var overThr = 0
        var tinyDrop = 0

        for (row in rows) {
            tot++
            if (row.size < 5) continue
            val feat = row.size

            val hasObj = feat >= 85
            val objProb = if (hasObj) {
                val v = row[4]
                if (v in 0f..1f) v else sigmoid(v)
            } else 1f
            val clsStart = if (hasObj) 5 else 4

            val v = row[clsStart + personIdx]
            val cp = if (v in 0f..1f) v else sigmoid(v)
            val score = objProb * cp
            if (score < scoreThr) continue
            overThr++

            val guessed = guessModelCorners(row) ?: continue
            val (modelQuad, guessType) = guessed
            if (out.isEmpty() && tot == 1) logd { "box guess = $guessType" }

            val x1m = modelQuad.x1
            val y1m = modelQuad.y1
            val x2m = modelQuad.x2
            val y2m = modelQuad.y2

            val (x1, y1, x2, y2) = when (coordMode) {
                CoordMode.MODEL_THEN_UNLETTERBOX -> {
                    val sc = if (scale == 0f) 1f else scale
                    val X1 = (x1m - padXf) / sc
                    val Y1 = (y1m - padYf) / sc
                    val X2 = (x2m - padXf) / sc
                    val Y2 = (y2m - padYf) / sc
                    Quad(
                        X1.coerceIn(0f, (srcW - 1).toFloat()),
                        Y1.coerceIn(0f, (srcH - 1).toFloat()),
                        X2.coerceIn(0f, (srcW - 1).toFloat()),
                        Y2.coerceIn(0f, (srcH - 1).toFloat())
                    )
                }
                CoordMode.ASSUME_ORIGINAL -> {
                    Quad(
                        x1m.coerceIn(0f, (srcW - 1).toFloat()),
                        y1m.coerceIn(0f, (srcH - 1).toFloat()),
                        x2m.coerceIn(0f, (srcW - 1).toFloat()),
                        y2m.coerceIn(0f, (srcH - 1).toFloat())
                    )
                }
                CoordMode.MODEL_LINEAR_SCALE -> {
                    Quad(
                        (x1m * scaleX).coerceIn(0f, (srcW - 1).toFloat()),
                        (y1m * scaleY).coerceIn(0f, (srcH - 1).toFloat()),
                        (x2m * scaleX).coerceIn(0f, (srcW - 1).toFloat()),
                        (y2m * scaleY).coerceIn(0f, (srcH - 1).toFloat())
                    )
                }
            }

            val bw = x2 - x1
            val bh = y2 - y1
            if (bw <= 1f || bh <= 1f) { tinyDrop++; continue }

            out += Detection(
                x1.roundToInt(), y1.roundToInt(), x2.roundToInt(), y2.roundToInt(),
                score,
                cls = personClassId,
                label = labels?.getOrNull(personClassId) ?: "person"
            )
        }

        logd { "decode[${coordMode.name}] rows=$tot overThr=$overThr tinyDrop=$tinyDrop keep=${out.size}" }
        return out
    }

    private data class Quad(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    private fun sampleBox(list: List<Detection>, k: Int = 1): String {
        if (list.isEmpty()) return "[]"
        val sb = StringBuilder()
        val n = min(list.size, k)
        sb.append('[')
        for (i in 0 until n) {
            val d = list[i]
            if (i > 0) sb.append(',')
            sb.append(String.format("(%d,%d)-(%d,%d) s=%.2f", d.x1, d.y1, d.x2, d.y2, d.score))
        }
        sb.append(']')
        return sb.toString()
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
