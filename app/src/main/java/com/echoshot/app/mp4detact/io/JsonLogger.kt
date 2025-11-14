// com.echoshot.app.mp4detact.io.JsonLogger
package com.echoshot.app.mp4detact.io

import android.content.Context
import android.net.Uri
import com.echoshot.app.mp4detact.data.Detection
import com.echoshot.app.mp4detact.data.TrackLogEntry
import org.opencv.core.Rect
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.text.DecimalFormat
import kotlin.math.max

/**
 * 프레임별 결과를 JSON Lines(ndjson)로 저장.
 *  - append(entry) 호출 시 1줄 기록
 *  - notes 안에 중첩 Map/List/Rect/Detection 등을 JSON으로 안전하게 기록
 */
class JsonLogger private constructor(
    private val bw: BufferedWriter
) : AutoCloseable {

    companion object {
        /** SAF Uri 로깅 */
        fun open(ctx: Context, outUri: Uri): JsonLogger {
            val os = ctx.contentResolver.openOutputStream(outUri, "w")
                ?: throw IllegalStateException("Cannot open OutputStream for $outUri")
            return JsonLogger(BufferedWriter(OutputStreamWriter(os)))
        }
        /** 파일 로깅 */
        fun open(outFile: File): JsonLogger {
            return JsonLogger(BufferedWriter(FileWriter(outFile, false)))
        }
    }

    private val num = DecimalFormat("0.###")

    fun append(e: TrackLogEntry) {
        val sb = StringBuilder(512)
        sb.append('{')
        kv(sb, "frame", e.frame); sb.append(',')
        kv(sb, "pts_ms", e.ptsMs); sb.append(',')
        kv(sb, "state", e.state); sb.append(',')

        // track bbox
        sb.append("\"track\":")
        e.track?.let { writeRect(sb, it) } ?: sb.append("null")
        sb.append(',')

        // velocity
        sb.append("\"vx\":"); sb.append(e.vx?.let { num.format(it) } ?: "null"); sb.append(',')
        sb.append("\"vy\":"); sb.append(e.vy?.let { num.format(it) } ?: "null"); sb.append(',')

        // detections (nullable 안전)
        sb.append("\"detections\":[")
        val dets = e.dets ?: emptyList()
        dets.forEachIndexed { i, d ->
            if (i > 0) sb.append(',')
            writeDet(sb, d)
        }
        sb.append(']')

        // notes (optional, allow nested JSON)
        if (!e.notes.isNullOrEmpty()) {
            sb.append(',')
            sb.append("\"notes\":")
            writeJsonValue(sb, e.notes)
        }

        sb.append('}')
        bw.write(sb.toString())
        bw.newLine()
    }

    private fun writeRect(sb: StringBuilder, r: Rect) {
        val x1 = r.x
        val y1 = r.y
        val x2 = r.x + max(0, r.width)
        val y2 = r.y + max(0, r.height)
        sb.append('[').append(x1).append(',').append(y1).append(',').append(x2).append(',').append(y2).append(']')
    }

    private fun writeDet(sb: StringBuilder, d: Detection) {
        sb.append('{')
        sb.append("\"x1\":").append(d.x1).append(',')
        sb.append("\"y1\":").append(d.y1).append(',')
        sb.append("\"x2\":").append(d.x2).append(',')
        sb.append("\"y2\":").append(d.y2).append(',')
        sb.append("\"score\":").append(num.format(d.score)).append(',')
        // ★ Detection의 필드명이 classId 인지 확인 (아니면 여길 프로젝트에 맞춰 수정)
        sb.append("\"class_id\":").append(d.cls).append(',')
        sb.append("\"label\":\"").append(escape(d.label ?: "person")).append('"')
        sb.append('}')
    }

    // --- 작은 JSON 유틸 ---

    private fun kv(sb: StringBuilder, k: String, v: Number) {
        sb.append('"').append(escape(k)).append('"').append(':').append(num.format(v.toDouble()))
    }

    private fun kv(sb: StringBuilder, k: String, v: String) {
        sb.append('"').append(escape(k)).append('"').append(':').append('"').append(escape(v)).append('"')
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** notes 등 임의 값(JSON) 직렬화 */
    private fun writeJsonValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> sb.append('"').append(escape(v)).append('"')
            is Number -> sb.append(num.format(v.toDouble()))
            is Boolean -> sb.append(if (v) "true" else "false")
            is Rect -> writeRect(sb, v)
            is Detection -> writeDet(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, vv) in v) {
                    if (k == null) continue
                    if (!first) sb.append(',')
                    first = false
                    sb.append('"').append(escape(k.toString())).append('"').append(':')
                    writeJsonValue(sb, vv)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeJsonValue(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> {
                sb.append('[')
                for (i in v.indices) {
                    if (i > 0) sb.append(',')
                    writeJsonValue(sb, v[i])
                }
                sb.append(']')
            }
            else -> {
                sb.append('"').append(escape(v.toString())).append('"')
            }
        }
    }

    override fun close() {
        bw.flush()
        bw.close()
    }
}
