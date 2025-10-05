package com.echoshot.app.mp4detact.data

import kotlin.math.*
import org.opencv.core.Rect

/** area(0 허용) */
fun area(r: Rect): Int = max(0, r.width) * max(0, r.height)

/** IoU (0..1) */
fun iou(a: Rect, b: Rect): Float {
    val x1 = max(a.x, b.x)
    val y1 = max(a.y, b.y)
    val x2 = min(a.x + a.width,  b.x + b.width)
    val y2 = min(a.y + a.height, b.y + b.height)
    val inter = max(0, x2 - x1) * max(0, y2 - y1)
    val u = area(a) + area(b) - inter
    return if (u > 0) inter.toFloat() / u.toFloat() else 0f
}

/** 중심 좌표 */
fun centerX(r: Rect): Float = r.x + r.width * 0.5f
fun centerY(r: Rect): Float = r.y + r.height * 0.5f

/** 중심 간 유클리드 거리 */
fun centerDist(a: Rect, b: Rect): Float {
    val dx = centerX(a) - centerX(b)
    val dy = centerY(a) - centerY(b)
    return sqrt(dx * dx + dy * dy)
}

/** 대각선으로 정규화된 중심 거리 (0..~) */
fun centerDistNorm(a: Rect, b: Rect, imgDiag: Float): Float {
    if (imgDiag <= 0f) return centerDist(a, b)
    return centerDist(a, b) / imgDiag
}

/** 비율(w/h) */
fun aspect(r: Rect): Float = if (r.height <= 0) 0f else r.width.toFloat() / r.height.toFloat()

/** rect를 프레임 경계로 클램프 */
fun clampRect(r: Rect, W: Int, H: Int): Rect {
    val x = r.x.coerceIn(0, max(0, W - 2))
    val y = r.y.coerceIn(0, max(0, H - 2))
    val w = r.width.coerceIn(2, W - x)
    val h = r.height.coerceIn(2, H - y)
    return Rect(x, y, w, h)
}

/** 중심 유지하며 scale배 확대/축소 */
fun scaleAboutCenter(r: Rect, scale: Float, W: Int? = null, H: Int? = null): Rect {
    val cx = centerX(r)
    val cy = centerY(r)
    val nw = max(2f, r.width * scale)
    val nh = max(2f, r.height * scale)
    val out = Rect(
        (cx - nw / 2f).roundToInt(),
        (cy - nh / 2f).roundToInt(),
        nw.roundToInt(),
        nh.roundToInt()
    )
    return if (W != null && H != null) clampRect(out, W, H) else out
}

/** 교집합 면적(정수) */
fun intersectArea(a: Rect, b: Rect): Int {
    val x1 = max(a.x, b.x)
    val y1 = max(a.y, b.y)
    val x2 = min(a.x + a.width,  b.x + b.width)
    val y2 = min(a.y + a.height, b.y + b.height)
    return max(0, x2 - x1) * max(0, y2 - y1)
}

/** 코사인 유사도 (-1..1). 입력은 L2정규화되어 있지 않아도 됨. */
fun cosineSim(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "cosineSim: dim mismatch" }
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        na += a[i] * a[i]
        nb += b[i] * b[i]
    }
    val denom = sqrt(na) * sqrt(nb)
    return if (denom > 1e-8) (dot / denom).toFloat() else 0f
}

/** EMA(지수이동평균)로 임베딩 갱신: dst ← (1-α)dst + α*src */
fun emaInPlace(dst: FloatArray, src: FloatArray, alpha: Float) {
    require(dst.size == src.size)
    val a = alpha.coerceIn(0f, 1f)
    for (i in dst.indices) dst[i] = ((1f - a) * dst[i] + a * src[i])
}
