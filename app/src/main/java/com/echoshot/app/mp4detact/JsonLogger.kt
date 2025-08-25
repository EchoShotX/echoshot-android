package com.echoshot.app.mp4detact

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.util.Locale
import kotlin.text.Charsets

class JsonLogger private constructor(
    private val os: OutputStream,
    private val bos: BufferedOutputStream
) : Closeable {

    companion object {
        /** ContentResolver + Uri 로 바로 연 뒤 JsonLogger 반환 */
        fun open(resolver: ContentResolver, outUri: Uri): JsonLogger {
            val stream = resolver.openOutputStream(outUri, "w")
                ?: error("Failed to open output: $outUri")
            return JsonLogger(stream, BufferedOutputStream(stream, 1 shl 20)) // 1MB 버퍼
        }

        /** Context 편의 오버로드 */
        fun open(ctx: Context, outUri: Uri): JsonLogger =
            open(ctx.contentResolver, outUri)
    }

    // StringBuilder 재사용(메모리/GC 절감)
    private val scratch = StringBuilder(4096)

    /** JSON 한 줄 추가 (JSONL) */
    @Synchronized
    fun append(
        frameIdx: Int,
        ptsMs: Long,
        fps: Float,
        srcW: Int,
        srcH: Int,
        dets: List<Detection>,
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
                .append("\"class_id\":").append(d.classId)
            d.label?.let {
                sb.append(',').append("\"label\":\"").append(escape(it)).append('"')
            }
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

    /** JSON 안전 이스케이프(제어문자 포함) */
    private fun escape(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"'  -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') {
                    out.append(String.format(Locale.US, "\\u%04x", ch.code))
                } else out.append(ch)
            }
        }
        return out.toString()
    }
}
