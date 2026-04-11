package com.echoshot.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 팬캠(직캠) 편집 히스토리를 앱 내부 저장소에 영구 보관하는 매니저.
 * SharedPreferences + JSON 직렬화 방식.
 */
object FancamHistoryManager {

    private const val TAG = "FancamHistoryManager"
    private const val PREFS_NAME = "fancam_history_prefs"
    private const val KEY_HISTORY = "history_json_array"
    private const val THUMBNAIL_DIR = "fancam_thumbnails"

    data class HistoryEntry(
        val id: String,                  // UUID
        val fileName: String,            // 원본 파일명
        val createdAt: Long,             // 생성 시각 (ms)
        val status: String,              // "processing" | "complete" | "failed"
        val originalWidth: Int,
        val originalHeight: Int,
        val paddingFactor: Float,        // 크롭 모드 (3.5 = center, 5.0 = wide)
        val outputResolution: String,    // "HD" | "FHD" | "UHD"
        val outputFilePath: String?,     // 생성된 영상 경로
        val thumbnailPath: String?,      // 썸네일 이미지 경로
        val editMode: String = "single"  // "single" | "auto_fast" | "auto_high" | "hybrid"
    )

    // ---- 저장/불러오기 ----

    fun loadHistory(context: Context): MutableList<HistoryEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_HISTORY, null) ?: return mutableListOf()

        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<HistoryEntry>()
            for (i in 0 until arr.length()) {
                list.add(fromJson(arr.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "히스토리 로드 실패", e)
            mutableListOf()
        }
    }

    fun hasEntry(context: Context, id: String): Boolean {
        val history = loadHistory(context)
        return history.any { it.id == id }
    }

    fun saveHistory(context: Context, history: List<HistoryEntry>) {
        val arr = JSONArray()
        history.forEach { arr.put(toJson(it)) }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, arr.toString())
            .apply()
    }

    // ---- 개별 항목 조작 ----

    fun addEntry(context: Context, entry: HistoryEntry) {
        val history = loadHistory(context)
        history.add(0, entry) // 최신 항목을 맨 위에
        saveHistory(context, history)
    }

    fun updateStatus(context: Context, id: String, newStatus: String, outputFilePath: String? = null) {
        val history = loadHistory(context)
        val idx = history.indexOfFirst { it.id == id }
        if (idx >= 0) {
            history[idx] = history[idx].copy(
                status = newStatus,
                outputFilePath = outputFilePath ?: history[idx].outputFilePath
            )
            saveHistory(context, history)
        }
    }

    fun deleteEntry(context: Context, id: String) {
        val history = loadHistory(context)
        val entry = history.find { it.id == id }

        // 썸네일 파일 삭제
        entry?.thumbnailPath?.let { path ->
            try { File(path).delete() } catch (_: Exception) {}
        }

        history.removeAll { it.id == id }
        saveHistory(context, history)
    }

    // ---- 썸네일 파일 저장 ----

    fun saveThumbnail(context: Context, id: String, bitmap: Bitmap): String? {
        return try {
            val dir = File(context.filesDir, THUMBNAIL_DIR)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "thumb_$id.jpg")
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "썸네일 저장 실패", e)
            null
        }
    }

    fun loadThumbnail(path: String?): Bitmap? {
        if (path == null) return null
        return try {
            val file = File(path)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)
            else null
        } catch (e: Exception) {
            Log.e(TAG, "썸네일 로드 실패", e)
            null
        }
    }

    // ---- JSON 직렬화 ----

    private fun toJson(entry: HistoryEntry): JSONObject {
        return JSONObject().apply {
            put("id", entry.id)
            put("fileName", entry.fileName)
            put("createdAt", entry.createdAt)
            put("status", entry.status)
            put("originalWidth", entry.originalWidth)
            put("originalHeight", entry.originalHeight)
            put("paddingFactor", entry.paddingFactor.toDouble())
            put("outputResolution", entry.outputResolution)
            put("outputFilePath", entry.outputFilePath ?: "") // null 대신 빈 문자열 권장
            put("thumbnailPath", entry.thumbnailPath ?: "")
            put("editMode", entry.editMode)
        }
    }

    private fun fromJson(obj: JSONObject): HistoryEntry {
        return HistoryEntry(
            id = obj.getString("id"),
            fileName = obj.getString("fileName"),
            createdAt = obj.getLong("createdAt"),
            status = obj.getString("status"),
            originalWidth = obj.optInt("originalWidth", 0),
            originalHeight = obj.optInt("originalHeight", 0),
            paddingFactor = obj.optDouble("paddingFactor", 3.5).toFloat(),
            outputResolution = obj.optString("outputResolution", "FHD"),
            outputFilePath = obj.optString("outputFilePath", "").takeIf { it.isNotEmpty() },
            thumbnailPath = obj.optString("thumbnailPath", "").takeIf { it.isNotEmpty() },
            editMode = obj.optString("editMode", "single")
        )
    }
}
