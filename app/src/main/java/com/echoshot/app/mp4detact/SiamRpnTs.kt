package com.echoshot.app.mp4detact

import android.content.Context
import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import kotlin.math.*

/**
 * SiamRPN TorchScript 백엔드 (안정화 + 비율 고정):
 * - ImageNet 정규화
 * - Hanning window + scale/ratio penalty
 * - LR 스무딩
 * - 템플릿 갱신 gating 강화
 * - 초기 박스 비율(w/h) 고정
 */
class SiamRpnTs(
    private val ctx: Context,
    private val modelAssetName: String = "siamrpnpp_mobile.ptl", //siamrpn_mobilev2_l234_dwxcorr
    private val EXEMPLAR: Int = 127,
    private val INSTANCE: Int = 255,
    private val STRIDE: Float = 8f,
    private val BASE_SIZE: Float = 8f,
    private val SCALES: FloatArray = floatArrayOf(8f),
    private val RATIOS: FloatArray = floatArrayOf(0.33f, 0.5f, 1f, 2f, 3f),
    private val CONTEXT_AMT: Float = 0.3f,

    // 안정화 하이퍼파라미터
    private val penaltyK: Float = 0.04f,
    private val windowInfluence: Float = 0.40f,
    private val lrBBox: Float = 0.50f,

    private val EMA_ALPHA: Double = 0.10,
) {
    companion object { private const val TAG = "SiamRpnTs" }

    // --- state ---
    private var module: Module? = null
    private var zTensor: Tensor? = null
    private var templBbox: Rect? = null
    private var frameIdx = 0
    private var lowScoreStreak = 0

    // 초기 비율 고정용
    private var initAspect: Float = 1f // width/height

    // window cache
    private var cachedWinH = -1
    private var cachedWinW = -1
    private var cachedWindow: FloatArray? = null

    // ImageNet 정규화
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std  = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun open() {
        val path = assetFilePath(ctx, modelAssetName)
        module = LiteModuleLoader.load(path)
        Log.i(TAG, "TorchScript loaded: $path")
    }

    fun close() {
        module = null
        zTensor = null
        templBbox = null
        cachedWindow = null
        cachedWinH = -1
        cachedWinW = -1
    }

    // --- utils ---
    private fun grayToFloat3CHWTensor(gray: Mat, size: Int): Tensor {
        val resized = Mat()
        Imgproc.resize(gray, resized, Size(size.toDouble(), size.toDouble()))
        val plane = ByteArray(size * size)
        resized.get(0, 0, plane)
        resized.release()

        val area = size * size
        val floats = FloatArray(3 * area)
        var i = 0
        while (i < area) {
            // 0..255 범위 float32 그대로 (정규화 없음)
            val v = (plane[i].toInt() and 0xFF).toFloat()
            floats[i]          = v  // C0
            floats[i + area]   = v  // C1
            floats[i + 2*area] = v  // C2
            i++
        }
        return Tensor.fromBlob(floats, longArrayOf(1, 3, size.toLong(), size.toLong()))
    }

    private fun clampRect(x: Int, y: Int, w: Int, h: Int, W: Int, H: Int): Rect {
        val nx = x.coerceIn(0, (W - 2).coerceAtLeast(0))
        val ny = y.coerceIn(0, (H - 2).coerceAtLeast(0))
        val nw = w.coerceIn(2, W - nx)
        val nh = h.coerceIn(2, H - ny)
        return Rect(nx, ny, nw, nh)
    }

    private fun iou(a: Rect, b: Rect): Float {
        val x1 = max(a.x, b.x)
        val y1 = max(a.y, b.y)
        val x2 = min(a.x + a.width,  b.x + b.width)
        val y2 = min(a.y + a.height, b.y + b.height)
        val inter = max(0, x2 - x1) * max(0, y2 - y1)
        val union = a.width * a.height + b.width * b.height - inter
        return if (union > 0) inter.toFloat() / union.toFloat() else 0f
    }

    private fun getCosineWindow(H: Int, W: Int): FloatArray {
        if (H == cachedWinH && W == cachedWinW && cachedWindow != null) return cachedWindow!!
        val wy = FloatArray(H)
        val wx = FloatArray(W)
        for (y in 0 until H) wy[y] = 0.5f * (1f - cos((2.0 * Math.PI * y / (H - 1)).toFloat()))
        for (x in 0 until W) wx[x] = 0.5f * (1f - cos((2.0 * Math.PI * x / (W - 1)).toFloat()))
        val win = FloatArray(H * W)
        var k = 0
        for (y in 0 until H) for (x in 0 until W) win[k++] = wy[y] * wx[x]
        cachedWinH = H; cachedWinW = W; cachedWindow = win
        return win
    }

    // --- public API ---
    /** 초기 템플릿 세팅: 640 gray, 초기 bbox(640 좌표) */
    fun setTemplate(initBox640: Rect, gray640: Mat) {
        val sz = (max(initBox640.width, initBox640.height) * 1.5).toInt().coerceAtLeast(16)
        val cx = initBox640.x + initBox640.width / 2
        val cy = initBox640.y + initBox640.height / 2
        val x = (cx - sz / 2).coerceAtLeast(0)
        val y = (cy - sz / 2).coerceAtLeast(0)
        val w = sz.coerceAtMost(gray640.width() - x)
        val h = sz.coerceAtMost(gray640.height() - y)
        val roi = Rect(x, y, w, h)
        val crop = Mat(gray640, roi)
        val zGray = Mat()
        Imgproc.resize(crop, zGray, Size(EXEMPLAR.toDouble(), EXEMPLAR.toDouble()))
        crop.release()

        zTensor = grayToFloat3CHWTensor(zGray, EXEMPLAR)
        zGray.release()
        templBbox = initBox640
        frameIdx = 0
        lowScoreStreak = 0

        initAspect = max(1f, initBox640.width.toFloat().coerceAtLeast(1f)) /
                max(1f, initBox640.height.toFloat().coerceAtLeast(1f))

        Log.d(TAG, "template set: bbox=$initBox640 aspect=%.4f".format(initAspect))
        Log.d(TAG, "template set: initBox=$initBox640 tplRoi=$roi exemplar=${EXEMPLAR}x${EXEMPLAR}")
    }

    /**
     * 640 gray 한 프레임에서 추적.
     * @return (bbox640, score) 또는 (null, 0f)
     */
    fun track(gray640: Mat): Pair<Rect?, Float> {
        val z = zTensor ?: return null to 0f
        val prev = templBbox ?: return null to 0f
        val m = module ?: return null to 0f

        // 1) 검색 윈도우
        val cx = prev.x + prev.width / 2f
        val cy = prev.y + prev.height / 2f
        val w = prev.width.toFloat().coerceAtLeast(2f)
        val h = prev.height.toFloat().coerceAtLeast(2f)
        val wc = w + CONTEXT_AMT * (w + h)
        val hc = h + CONTEXT_AMT * (w + h)
        val sz = sqrt(wc * hc)

        val dynamicMinSide = if (lowScoreStreak >= 5) 224f else 160f
        val maxSide = 640f
        var side = sz * (INSTANCE.toFloat() / EXEMPLAR.toFloat())
        side = side.coerceIn(dynamicMinSide, maxSide)

        val fx1 = max(0f, cx - side / 2f)
        val fy1 = max(0f, cy - side / 2f)
        val fx2 = min(gray640.cols().toFloat(), fx1 + side)
        val fy2 = min(gray640.rows().toFloat(), fy1 + side)
        val roi = clampRect(
            fx1.toInt(), fy1.toInt(),
            (fx2 - fx1).toInt().coerceAtLeast(2),
            (fy2 - fy1).toInt().coerceAtLeast(2),
            gray640.cols(), gray640.rows()
        )
        Log.d(TAG, "SOT#ROI prev=$prev side=%.2f roi=$roi (src=${gray640.cols()}x${gray640.rows()})".format(side))

        // 2) 255 → xTensor
        val crop = Mat(gray640, roi)
        val xGray = Mat()
        Imgproc.resize(crop, xGray, Size(INSTANCE.toDouble(), INSTANCE.toDouble()))
        crop.release()
        val xTensor = grayToFloat3CHWTensor(xGray, INSTANCE)
        xGray.release()

        // 3) forward
        val outs = try {
            m.forward(IValue.from(z), IValue.from(xTensor)).toTuple()
        } catch (t: Throwable) {
            Log.e(TAG, "forward failed: ${t.message}", t)
            return null to 0f
        }
        val clsT = outs[0].toTensor()
        val locT = outs[1].toTensor()
        val H = clsT.shape()[2].toInt()
        val W = clsT.shape()[3].toInt()
        val A = (clsT.shape()[1] / 2).toInt()
        Log.d(TAG, "SOT#OUT cls=${clsT.shape().contentToString()} loc=${locT.shape().contentToString()} A=$A HxW=${H}x$W")
        if (A != RATIOS.size) Log.w(TAG, "Anchor count mismatch: model=$A config=${RATIOS.size}")

        val cls = clsT.dataAsFloatArray
        val loc = locT.dataAsFloatArray
        if (cls.isEmpty() || loc.isEmpty()) {
            Log.w(TAG, "empty tensors: cls=${cls.size} loc=${loc.size}")
            return null to 0f
        }

        val dynStride = (INSTANCE - EXEMPLAR).toFloat() / max(1, (H - 1))
        if (abs(dynStride - STRIDE) > 0.5f) {
            Log.w(TAG, "SOT#STRIDE using dynamic=%.3f (config=%.1f, H=%d)".format(dynStride, STRIDE, H))
        }
        val window = getCosineWindow(H, W)

        // 4) 후보 스코어(softmax→penalty→window)
        fun cIdx(c: Int, y: Int, x: Int) = ((c * H) + y) * W + x
        fun lIdx(c: Int, y: Int, x: Int) = ((c * H) + y) * W + x

        val base = BASE_SIZE * SCALES[0] // 64
        var bestScore = -1f
        var bestA = 0; var bestX = 0; var bestY = 0
        var bestAx = 0f; var bestAy = 0f
        var bestWp = 0f; var bestHp = 0f

        val scoreCenter = (H - 1) / 2f
        val searchCenter = INSTANCE / 2f

        for (a in 0 until A) {
            val r = RATIOS[a.coerceIn(0, RATIOS.lastIndex)]
            val sr = sqrt(r.toDouble()).toFloat()
            val aw = base / sr
            val ah = base * sr

            val cNeg = 2 * a; val cPos = 2 * a + 1
            for (y in 0 until H) {
                for (x in 0 until W) {
                    val neg = cls[cIdx(cNeg, y, x)]
                    val pos = cls[cIdx(cPos, y, x)]
                    val mmax = max(neg, pos)
                    val eNeg = kotlin.math.exp((neg - mmax).toDouble()).toFloat()
                    val ePos = kotlin.math.exp((pos - mmax).toDouble()).toFloat()
                    val p = ePos / (ePos + eNeg + 1e-6f)

                    val ax = (x - scoreCenter) * dynStride + searchCenter
                    val ay = (y - scoreCenter) * dynStride + searchCenter

                    val tx = loc[lIdx(4 * a + 0, y, x)]
                    val ty = loc[lIdx(4 * a + 1, y, x)]
                    val tw = loc[lIdx(4 * a + 2, y, x)]
                    val th = loc[lIdx(4 * a + 3, y, x)]
                    val wp = (kotlin.math.exp(tw.toDouble()).toFloat() * aw).coerceAtLeast(8f)
                    val hp = (kotlin.math.exp(th.toDouble()).toFloat() * ah).coerceAtLeast(8f)

                    val prevW = max(prev.width.toFloat(), 2f)
                    val prevH = max(prev.height.toFloat(), 2f)
                    val s_c = sqrt((wp * hp) / (prevW * prevH))
                    val r_c = (prevW / prevH) / (wp / hp)
                    val change = r_c * s_c
                    val penalty = exp(-penaltyK * (change - 1f) * (change - 1f)).toFloat()

                    val pPenalized = p * penalty
                    val wVal = window[y * W + x]
                    val finalScore = (1f - windowInfluence) * pPenalized + windowInfluence * wVal

                    if (finalScore > bestScore) {
                        bestScore = finalScore
                        bestA = a; bestX = x; bestY = y
                        bestAx = ax + tx * aw
                        bestAy = ay + ty * ah
                        bestWp = wp; bestHp = hp
                    }
                }
            }
        }

        Log.d(TAG, "SOT#BEST a=$bestA xy=($bestX,$bestY) final=%.4f center=(%.1f,%.1f) stride=%.3f"
            .format(bestScore, bestAx, bestAy, dynStride))

        // 5) search 좌표 → 박스 (INSTANCE 좌표)
        val bx = (bestAx - bestWp / 2f).roundToInt().coerceIn(0, INSTANCE - 1)
        val by = (bestAy - bestHp / 2f).roundToInt().coerceIn(0, INSTANCE - 1)
        val bw = bestWp.roundToInt().coerceAtLeast(2)
        val bh = bestHp.roundToInt().coerceAtLeast(2)
        val boxSearch = clampRect(bx, by, bw, bh, INSTANCE, INSTANCE)

        // 6) search → 640 복원
        val scaleX = roi.width.toFloat()  / INSTANCE.toFloat()
        val scaleY = roi.height.toFloat() / INSTANCE.toFloat()
        val out = Rect(
            (roi.x + boxSearch.x * scaleX).roundToInt(),
            (roi.y + boxSearch.y * scaleY).roundToInt(),
            (boxSearch.width  * scaleX).roundToInt().coerceAtLeast(2),
            (boxSearch.height * scaleY).roundToInt().coerceAtLeast(2)
        )
        val clamped = clampRect(out.x, out.y, out.width, out.height, gray640.cols(), gray640.rows())
        Log.d(TAG, "SOT#DECODE searchBox=$boxSearch scaleXY=(%.3f,%.3f) out640=$clamped".format(scaleX, scaleY))

        // 7) LR 스무딩
        val prevCx = prev.x + prev.width / 2f
        val prevCy = prev.y + prev.height / 2f
        val newCx  = clamped.x + clamped.width / 2f
        val newCy  = clamped.y + clamped.height / 2f

        var smCx = prevCx * (1 - lrBBox) + newCx * lrBBox
        var smCy = prevCy * (1 - lrBBox) + newCy * lrBBox

        // --- 비율 고정 스케일링 ---
        // 폭/높이 스케일을 계산해 기하평균으로 단일 스케일 s를 잡고 범위 클램프
        val sW = clamped.width.toFloat()  / max(prev.width.toFloat(), 1f)
        val sH = clamped.height.toFloat() / max(prev.height.toFloat(), 1f)
        var s = sqrt(max(0.01f, sW * sH))
        s = s.coerceIn(0.60f, 1.40f)

        var smW = max(2f, prev.width * s)
        var smH = max(2f, smW / initAspect) // ← 비율 고정
        // 픽셀 정수화
        smW = round(smW); smH = round(smH)

        val locked = clampRect(
            (smCx - smW / 2f).roundToInt(),
            (smCy - smH / 2f).roundToInt(),
            smW.toInt().coerceAtLeast(2),
            smH.toInt().coerceAtLeast(2),
            gray640.cols(), gray640.rows()
        )
        Log.d(TAG, "SOT#LOCK aspect=%.4f w/h=%.4f rect=$locked"
            .format(initAspect, locked.width.toFloat() / max(1f, locked.height.toFloat())))

        // 8) 신뢰도 기반 컨트롤
        if (bestScore < 0.35f) lowScoreStreak++ else lowScoreStreak = 0

        // 9) 템플릿 갱신 (더 보수적으로)
        frameIdx++
        val iouOk = iou(prev, locked) > 0.30f
        val aspectNow = locked.width.toFloat() / max(1f, locked.height.toFloat())
        val aspectDrift = abs(aspectNow / initAspect - 1f)
        val aspectOk = aspectDrift < 0.10f
        val scoreOk = bestScore > 0.80f
        val periodic = (frameIdx % 5 == 0)

        if (scoreOk && iouOk && aspectOk && periodic) {
            updateTemplateEma(locked, gray640)
            Log.d(TAG, "SOT#TPL update (scoreOk=$scoreOk iouOk=$iouOk aspectOk=$aspectOk)")
        } else {
            Log.d(TAG, "SOT#TPL skip (scoreOk=$scoreOk iouOk=$iouOk aspectOk=$aspectOk periodic=$periodic)")
        }

        templBbox = locked
        return locked to bestScore
    }

    private fun updateTemplateEma(newBox: Rect, gray640: Mat) {
        val sz = (max(newBox.width, newBox.height) * 1.5).toInt().coerceAtLeast(16)
        val cx = newBox.x + newBox.width / 2
        val cy = newBox.y + newBox.height / 2
        val x = (cx - sz / 2).coerceAtLeast(0)
        val y = (cy - sz / 2).coerceAtLeast(0)
        val w = sz.coerceAtMost(gray640.width() - x)
        val h = sz.coerceAtMost(gray640.height() - y)
        val crop = Mat(gray640, Rect(x, y, w, h))
        val zNew = Mat()
        Imgproc.resize(crop, zNew, Size(EXEMPLAR.toDouble(), EXEMPLAR.toDouble()))
        crop.release()

        // 간단 교체 (EMA 혼합 필요시 float 버퍼 혼합 구현)
        zTensor = grayToFloat3CHWTensor(zNew, EXEMPLAR)
        zNew.release()
    }

    // --- assets → filesDir 복사 ---
    private fun assetFilePath(context: Context, assetName: String): String {
        val out = File(context.filesDir, assetName)
        if (out.exists() && out.length() > 0) return out.absolutePath
        context.assets.open(assetName).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out.absolutePath
    }
}
