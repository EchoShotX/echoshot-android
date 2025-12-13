package com.echoshot.app.mp4detact.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.echoshot.app.mp4detact.data.PoseDetection
import com.echoshot.app.mp4detact.data.Keypoint
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.util.Locale
import kotlin.text.Charsets

/**
 * YOLO11n-pose 결과를 JSONL로 로깅
 * 
 * 출력 형식 예:
 * {
 *   "frame": 0,
 *   "pts_ms": 33,
 *   "fps": 30.0,
 *   "src_w": 1920,
 *   "src_h": 1080,
 *   "detections": [
 *     {
 *       "x1": 100.0, "y1": 200.0, "x2": 300.0, "y2": 600.0,
 *       "score": 0.85,
 *       "torso_box": {"x1": 120, "y1": 220, "x2": 280, "y2": 450},
 *       "keypoints": [[x, y, conf], [x, y, conf], ... 17개]
 *     }
 *   ]
 * }
 */
class PoseJsonLogger private constructor(
    private val os: OutputStream,
    private val bos: BufferedOutputStream
) : Closeable {

    companion object {
        /** ContentResolver + Uri 로 바로 연 뒤 PoseJsonLogger 반환 */
        fun open(resolver: ContentResolver, outUri: Uri): PoseJsonLogger {
            val stream = resolver.openOutputStream(outUri, "w")
                ?: error("Failed to open output: $outUri")
            return PoseJsonLogger(stream, BufferedOutputStream(stream, 1 shl 20)) // 1MB 버퍼
        }

        /** Context 편의 오버로드 */
        fun open(ctx: Context, outUri: Uri): PoseJsonLogger =
            open(ctx.contentResolver, outUri)
    }

    // StringBuilder 재사용(메모리/GC 절감)
    private val scratch = StringBuilder(8192)

    /** JSON 한 줄 추가 (JSONL) */
    @Synchronized
    fun append(
        frameIdx: Int,
        ptsMs: Long,
        fps: Float,
        srcW: Int,
        srcH: Int,
        dets: List<PoseDetection>,
    ) {
        val sb = scratch
        sb.setLength(0)

        sb.append('{')
            .append("\"frame\":").append(frameIdx).append(',')
            .append("\"pts_ms\":").append(ptsMs).append(',')
            .append("\"fps\":").append(String.format(Locale.US, "%.3f", fps)).append(',')
            .append("\"src_w\":").append(srcW).append(',')
            .append("\"src_h\":").append(srcH).append(',')
            .append("\"detections\":[")

        dets.forEachIndexed { i, d ->
            if (i > 0) sb.append(',')
            sb.append('{')
                .append("\"x1\":").append(String.format(Locale.US, "%.1f", d.x1)).append(',')
                .append("\"y1\":").append(String.format(Locale.US, "%.1f", d.y1)).append(',')
                .append("\"x2\":").append(String.format(Locale.US, "%.1f", d.x2)).append(',')
                .append("\"y2\":").append(String.format(Locale.US, "%.1f", d.y2)).append(',')
                .append("\"score\":").append(String.format(Locale.US, "%.3f", d.score)).append(',')
            
            // 상체 박스 (torso_box)
            val torso = d.getTorsoBox(0.3f)
            if (torso != null) {
                sb.append("\"torso_box\":{")
                    .append("\"x\":").append(torso.x).append(',')
                    .append("\"y\":").append(torso.y).append(',')
                    .append("\"w\":").append(torso.width).append(',')
                    .append("\"h\":").append(torso.height)
                    .append("},")
            }
            
            // 상체 중심점
            val center = d.getTorsoCenter(0.3f)
            if (center != null) {
                sb.append("\"torso_center\":{")
                    .append("\"x\":").append(String.format(Locale.US, "%.1f", center.first)).append(',')
                    .append("\"y\":").append(String.format(Locale.US, "%.1f", center.second))
                    .append("},")
            }
            
            // Keypoints: [[x, y, conf], ...]
            sb.append("\"keypoints\":[")
            d.keypoints.forEachIndexed { j, kp ->
                if (j > 0) sb.append(',')
                sb.append('[')
                    .append(String.format(Locale.US, "%.1f", kp.x)).append(',')
                    .append(String.format(Locale.US, "%.1f", kp.y)).append(',')
                    .append(String.format(Locale.US, "%.3f", kp.conf))
                    .append(']')
            }
            sb.append(']')
            
            sb.append('}')
        }

        sb.append("]}\n")

        // UTF-8 명시하여 바이트 변환
        bos.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    /** 필요 시 수동 flush */
    @Synchronized fun flush() {
        bos.flush()
    }

    override fun close() {
        try { flush() } catch (_: Throwable) {}
        try { bos.close() } catch (_: Throwable) {}
        try { os.close() } catch (_: Throwable) {}
    }
}

