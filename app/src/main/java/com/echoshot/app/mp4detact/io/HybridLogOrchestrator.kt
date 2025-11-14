// HybridLogOrchestrator.kt
package com.echoshot.app.mp4detact.io

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object HybridLogOrchestrator {

    private const val TAG = "HybridLogOrch"

    enum class Stage { START, PROCESSING, MERGING, EXPORTING, FINISHED }

    data class Result(
        val detectLocalFile: File,     // detect 로그의 로컬 복사본
        val processedJsonFile: File,   // make_log_pipeline 결과
        val mergedLocalFile: File,     // 병합본(크롭 입력용)
        val mergedOutUri: Uri          // SAF로 내보낸 병합본
    )

    /**
     * 하이브리드 디텍트 결과(detectLogUri, jsonl)를 입력으로 받아
     * 1) make_log_pipeline으로 processed.json 생성
     * 2) 파이썬 병합 모듈로 detect + processed → merged.jsonl
     * 3) Downloads/EchoShotLogs 로 내보냄
     *
     * @param mergeModule 파이썬 모듈명 (예: "merge_offline_logs_v2")
     * @param mergeFunc   파이썬 함수명 (예: "merge")
     */
    suspend fun processAndMerge(
        ctx: Context,
        sessionUuid: String,
        detectLogUri: Uri,              // HybridPicker가 만든 jsonl
        trackingUri: Uri?,              // tracking_log_<uuid>.json
        tsUri: Uri?,                    // tracking_log_<uuid>_frame_ts.json
        filesDir: File,
        mergeModule: String = "merge_tracks_pipeline", // 교체 가능
        mergeFunc: String = "merge_offline_logs_from_tracks_min",
        onStage: (Stage, String) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        onStage(Stage.START, "initialize")

        // --- 0) 입력 검증 ---
        if (trackingUri == null || tsUri == null) {
            throw IllegalStateException("tracking/ts 로그가 없습니다. tracking=$trackingUri, ts=$tsUri")
        }

        // --- 1) detect SAF → 로컬로 복사 ---
        coroutineContext.ensureActive()
        onStage(Stage.START, "copy detect log to local")
        val detectLocal = File(filesDir, "detect_${sessionUuid}.jsonl")
        copyUriToFile(cr, detectLogUri, detectLocal)
        Log.d(TAG, "[detect] local=${detectLocal.absolutePath} size=${detectLocal.length()}")

        // --- 2) make_log_pipeline → processed.json ---
        coroutineContext.ensureActive()
        onStage(Stage.PROCESSING, "run make_log_pipeline.process_video")
        val processed = runMakeLogPipeline(
            ctx = ctx,
            sessionUuid = sessionUuid,
            trackingUri = trackingUri,
            tsUri = tsUri,
            filesDir = filesDir
        )
        Log.d(TAG, "[process] processed=${processed.absolutePath} size=${processed.length()}")

        if (!processed.exists() || processed.length() == 0L) {
            throw IllegalStateException("processed 로그가 비었습니다: ${processed.absolutePath}")
        }

        // --- 3) 병합 → merged.jsonl (로컬, 타임스탬프 포함) ---
        coroutineContext.ensureActive()
        onStage(Stage.MERGING, "merge detect + processed")
        val mergedLocal = File(filesDir, "merged_${sessionUuid}_${System.currentTimeMillis()/1000}.jsonl")
        runPythonMerge(
            module = mergeModule,
            func = mergeFunc,
            detectPath = detectLocal.absolutePath,
            processedPath = processed.absolutePath,
            outPath = mergedLocal.absolutePath
        )
        Log.d(TAG, "[merge] mergedLocal=${mergedLocal.absolutePath} size=${mergedLocal.length()}")

        // --- 4) SAF로 내보내기 ---
        coroutineContext.ensureActive()
        onStage(Stage.EXPORTING, "export merged to SAF")
        val mergedOutUri = createOutputJsonInDownloads(
            ctx, "merged_${sessionUuid}_${System.currentTimeMillis()/1000}.jsonl"
        ) ?: throw IllegalStateException("merged SAF 출력 생성 실패")
        copyFileToUri(cr, mergedLocal, mergedOutUri)
        Log.i(TAG, "병합 로그 저장 완료: $mergedOutUri")

        onStage(Stage.FINISHED, "done")

        Result(
            detectLocalFile = detectLocal,
            processedJsonFile = processed,
            mergedLocalFile = mergedLocal,
            mergedOutUri = mergedOutUri
        )
    }

    // -------------------- internals --------------------

    private fun runMakeLogPipeline(
        ctx: Context,
        sessionUuid: String,
        trackingUri: Uri,
        tsUri: Uri,
        filesDir: File
    ): File {
        val trackingFile = File(filesDir, "${sessionUuid}_tracking.json")
        val tsFile       = File(filesDir, "${sessionUuid}_frame_ts.json")
        val outputJson   = File(filesDir, "${sessionUuid}_processed.json")

        fun copy(src: Uri, dst: File) {
            ctx.contentResolver.openInputStream(src)?.use { inp ->
                FileOutputStream(dst).use { out -> inp.copyTo(out) }
            } ?: throw IllegalStateException("openInputStream 실패: $src")
        }

        copy(trackingUri, trackingFile)
        copy(tsUri, tsFile)

        val py  = Python.getInstance()
        val mod = py.getModule("make_log_pipeline")
        mod.callAttr(
            "process_video",
            trackingFile.absolutePath,
            tsFile.absolutePath,
            outputJson.absolutePath,
            0.2  // 필요시 파라미터 조정
        )
        return outputJson
    }

    /** 병합 모듈/함수명 교체 가능 (ex. module="merge_v2", func="merge") */
    private fun runPythonMerge(
        module: String,
        func: String,
        detectPath: String,
        processedPath: String,
        outPath: String
    ) {
        val py = Python.getInstance()
        val mod = py.getModule(module)
        mod.callAttr(func, detectPath, processedPath, outPath)
    }

    private fun copyUriToFile(cr: ContentResolver, src: Uri, dst: File) {
        cr.openInputStream(src)?.use { inp ->
            FileOutputStream(dst).use { out -> inp.copyTo(out) }
        } ?: throw IllegalStateException("openInputStream failed: $src")
    }

    private fun copyFileToUri(cr: ContentResolver, src: File, dst: Uri) {
        cr.openOutputStream(dst, "w")?.use { out ->
            src.inputStream().use { inp -> inp.copyTo(out) }
        } ?: throw IllegalStateException("openOutputStream failed: $dst")
    }

    /** Downloads/EchoShotLogs 아래에 JSON/JSONL 파일 생성(SAF) */
    fun createOutputJsonInDownloads(ctx: Context, displayName: String): Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/EchoShotLogs"
                )
            }
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(collection, values) ?: return null
        cr.openOutputStream(uri, "w")?.use { /* touch */ } ?: return null
        return uri
    }
}
