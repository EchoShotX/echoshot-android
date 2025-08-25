package com.echoshot.app.mp4detact

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.chaquo.python.Python
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object LogOrchestrator {

    private const val TAG = "LogOrchestrator"

    enum class Stage { START, DETECTING, PROCESSING, MERGING, FINISHED }

    data class ResultPaths(
        val detectOutUri: Uri,
        val detectLocalFile: File,
        val processedJsonFile: File,
        val mergedLocalFile: File,
        val mergedOutUri: Uri
    )

    // --------- public API (SAF 출력 URI를 내부에서 생성) ---------
    suspend fun makeBothLogsAndMerge(
        ctx: Context,
        sessionUuid: String,
        videoUriForDetect: Uri,
        trackingUri: Uri?,
        tsUri: Uri?,
        filesDir: File,
        onStage: (Stage, String) -> Unit = { _, _ -> }
    ): ResultPaths = withContext(Dispatchers.IO) {

        onStage(Stage.START, "initialize")

        // SAF 출력 2개 생성
        val detectOutUri = createOutputJsonInDownloads(
            ctx, "detect_log_${sessionUuid}_${System.currentTimeMillis()/1000}.json"
        ) ?: throw IllegalStateException("detect 출력 파일 생성 실패")

        val mergedOutUri = createOutputJsonInDownloads(
            ctx, "merged_${sessionUuid}_${System.currentTimeMillis()/1000}.jsonl"
        ) ?: throw IllegalStateException("merged 출력 파일 생성 실패")

        makeBothLogsAndMerge(
            ctx = ctx,
            sessionUuid = sessionUuid,
            videoUriForDetect = videoUriForDetect,
            outDetectUri = detectOutUri,
            outMergedUri = mergedOutUri,
            trackingUri = trackingUri,
            tsUri = tsUri,
            filesDir = filesDir,
            onStage = onStage
        )
    }

    // --------- public API (SAF 출력 URI를 호출자가 제공) ---------
    suspend fun makeBothLogsAndMerge(
        ctx: Context,
        sessionUuid: String,
        videoUriForDetect: Uri,
        outDetectUri: Uri,
        outMergedUri: Uri,
        trackingUri: Uri?,
        tsUri: Uri?,
        filesDir: File,
        onStage: (Stage, String) -> Unit = { _, _ -> }
    ): ResultPaths = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver

        // 0) 입력 검증
        if (trackingUri == null || tsUri == null) {
            throw IllegalStateException(
                "tracking/ts 로그를 찾을 수 없습니다. (trackingUri=$trackingUri, tsUri=$tsUri)"
            )
        }

        // 1) MP4 디텍션 → outDetectUri
        coroutineContext.ensureActive()
        onStage(Stage.DETECTING, "run detect on MP4")
        runDetectLogSuspend(ctx, sessionUuid, videoUriForDetect, outDetectUri)

        // SAF detect 로그를 Python이 읽을 수 있게 로컬 파일로 복사
        val detectLocal = File(filesDir, "detect_${sessionUuid}.jsonl")
        copyUriToFile(cr, outDetectUri, detectLocal)
        Log.d(TAG, "[detect] saved uri=$outDetectUri -> local=${detectLocal.absolutePath} size=${detectLocal.length()}")

        // 2) make_log_pipeline 후처리 → processed.json
        coroutineContext.ensureActive()
        onStage(Stage.PROCESSING, "run make_log_pipeline")
        val processed = generateLogFromSessionInternal(ctx, sessionUuid, trackingUri, tsUri, filesDir)
        Log.d(TAG, "[process] processed=${processed.absolutePath} size=${processed.length()}")

        if (!processed.exists() || processed.length() == 0L) {
            throw IllegalStateException("processed 로그가 비어있습니다: ${processed.absolutePath}")
        }

        // 3) 병합 → merged.jsonl (local)
        coroutineContext.ensureActive()
        onStage(Stage.MERGING, "merge logs")
        val mergedLocal = File(filesDir, "merged_${sessionUuid}.jsonl")
        runPythonMerge(detectLocal.absolutePath, processed.absolutePath, mergedLocal.absolutePath)
        Log.d(TAG, "[merge] mergedLocal=${mergedLocal.absolutePath} size=${mergedLocal.length()}")

        // 병합본을 SAF로 복사
        copyFileToUri(cr, mergedLocal, outMergedUri)
        Log.i(TAG, "병합 로그 저장 완료: $outMergedUri")

        onStage(Stage.FINISHED, "done")

        ResultPaths(
            detectOutUri = outDetectUri,
            detectLocalFile = detectLocal,
            processedJsonFile = processed,
            mergedLocalFile = mergedLocal,
            mergedOutUri = outMergedUri
        )
    }

    // -------------------- internals --------------------

    /** DetectLogManager 콜백을 suspend로 감싸기 */
    private suspend fun runDetectLogSuspend(
        ctx: Context,
        sessionUuid: String,
        videoUri: Uri,
        outDetectUri: Uri
    ) = withContext(Dispatchers.IO) {
        val done = CompletableDeferred<Unit>()
        DetectLogManager.makeDetectLogFromMp4(
            ctx = ctx,
            sessionUuid = sessionUuid,
            videoUri = videoUri,
            outJsonUri = outDetectUri,
            onSuccess = { done.complete(Unit) },
            onError = { e -> if (!done.isCompleted) done.completeExceptionally(e) }
        )
        done.await()
    }

    /** GalleryFragment 의 generateLogFromSession() 이식 */
    private fun generateLogFromSessionInternal(
        ctx: Context,
        sessionUuid: String,
        trackingUri: Uri?,
        tsUri: Uri?,
        filesDir: File
    ): File {
        val trackingFile = File(filesDir, "${sessionUuid}_tracking.json")
        val tsFile       = File(filesDir, "${sessionUuid}_frame_ts.json")
        val outputJson   = File(filesDir, "${sessionUuid}_processed.json")

        fun copyIfNotNull(u: Uri?, dst: File) {
            if (u == null) throw IllegalStateException("필수 입력 누락: $dst")
            ctx.contentResolver.openInputStream(u)?.use { inp ->
                FileOutputStream(dst).use { out -> inp.copyTo(out) }
            } ?: throw IllegalStateException("openInputStream 실패: $u")
        }

        copyIfNotNull(trackingUri, trackingFile)
        copyIfNotNull(tsUri, tsFile)

        val py  = Python.getInstance()
        val mod = py.getModule("make_log_pipeline")
        mod.callAttr(
            "process_video",
            trackingFile.absolutePath,
            tsFile.absolutePath,
            outputJson.absolutePath,
            0.2
        )
        return outputJson
    }

    /** Chaquopy로 Python merge 호출 */
    private fun runPythonMerge(detectPath: String, zoomProcessedPath: String, outPath: String) {
        val py = Python.getInstance()
        val mod = py.getModule("merge_offline_logs")
        // 내부에서 print로 "✅ 병합 로그 저장 완료: ..." 로그 출력하도록 되어 있다면 그대로 사용
        mod.callAttr("merge_offline_logs", detectPath, zoomProcessedPath, outPath)
    }

    /** Content Uri → 내부 파일 복사 */
    private fun copyUriToFile(cr: ContentResolver, src: Uri, dst: File) {
        cr.openInputStream(src)?.use { inp ->
            FileOutputStream(dst).use { out -> inp.copyTo(out) }
        } ?: throw IllegalStateException("openInputStream failed: $src")
    }

    /** 내부 파일 → Content Uri 복사 */
    private fun copyFileToUri(cr: ContentResolver, src: File, dst: Uri) {
        cr.openOutputStream(dst, "w")?.use { out ->
            src.inputStream().use { inp -> inp.copyTo(out) }
        } ?: throw IllegalStateException("openOutputStream failed: $dst")
    }

    /** Downloads/EchoShotLogs 아래에 JSON 파일 생성(SAF) */
    private fun createOutputJsonInDownloads(ctx: Context, displayName: String): Uri? {
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
        // touch
        cr.openOutputStream(uri, "w")?.use { /* no-op */ } ?: return null
        return uri
    }
}
