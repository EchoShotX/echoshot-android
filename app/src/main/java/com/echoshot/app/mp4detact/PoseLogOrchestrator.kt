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

/**
 * YOLO11n-pose 기반 포즈 로그 생성 및 병합 오케스트레이터
 * 
 * LogOrchestrator와 동일한 구조이나, pose 모델과 keypoints 기반 트래킹 사용
 */
object PoseLogOrchestrator {

    private const val TAG = "PoseLogOrchestrator"

    enum class Stage { START, POSE_DETECTING, PROCESSING, MERGING, FINISHED }

    data class ResultPaths(
        val poseLogUri: Uri,
        val poseLogLocalFile: File,
        val processedJsonFile: File,
        val mergedLocalFile: File,
        val mergedOutUri: Uri
    )

    /**
     * 전체 파이프라인 실행
     * 
     * 1. MP4에서 YOLO11n-pose 감지 → pose_log.jsonl
     * 2. 실시간 트래킹 로그 + 포즈 로그 병합 → merged.jsonl
     */
    suspend fun makePoseLogsAndMerge(
        ctx: Context,
        sessionUuid: String,
        videoUriForDetect: Uri,
        trackingUri: Uri?,
        tsUri: Uri?,
        filesDir: File,
        targetRect: android.graphics.RectF? = null,
        onStage: (Stage, String) -> Unit = { _, _ -> },
        onProgress: (frameIdx: Int, ptsMs: Long) -> Unit = { _, _ -> }
    ): ResultPaths = withContext(Dispatchers.IO) {

        onStage(Stage.START, "initialize")

        // SAF 출력 2개 생성
        val poseLogUri = createOutputJsonInDownloads(
            ctx, "pose_log_${sessionUuid}_${System.currentTimeMillis() / 1000}.jsonl"
        ) ?: throw IllegalStateException("pose 로그 출력 파일 생성 실패")

        val mergedOutUri = createOutputJsonInDownloads(
            ctx, "merged_pose_${sessionUuid}_${System.currentTimeMillis() / 1000}.jsonl"
        ) ?: throw IllegalStateException("merged 출력 파일 생성 실패")

        makePoseLogsAndMerge(
            ctx = ctx,
            sessionUuid = sessionUuid,
            videoUriForDetect = videoUriForDetect,
            outPoseLogUri = poseLogUri,
            outMergedUri = mergedOutUri,
            trackingUri = trackingUri,
            tsUri = tsUri,
            filesDir = filesDir,
            targetRect = targetRect,
            onStage = onStage,
            onProgress = onProgress
        )
    }

    /**
     * URI를 직접 제공하는 버전
     */
    suspend fun makePoseLogsAndMerge(
        ctx: Context,
        sessionUuid: String,
        videoUriForDetect: Uri,
        outPoseLogUri: Uri,
        outMergedUri: Uri,
        trackingUri: Uri?,
        tsUri: Uri?,
        filesDir: File,
        targetRect: android.graphics.RectF? = null,
        onStage: (Stage, String) -> Unit = { _, _ -> },
        onProgress: (frameIdx: Int, ptsMs: Long) -> Unit = { _, _ -> }
    ): ResultPaths = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver

        // 1) MP4 포즈 감지 → outPoseLogUri
        coroutineContext.ensureActive()
        onStage(Stage.POSE_DETECTING, "run YOLO11n-pose on MP4")
        runPoseLogSuspend(ctx, sessionUuid, videoUriForDetect, outPoseLogUri, onProgress)

        // SAF 로그를 Python이 읽을 수 있게 로컬 파일로 복사
        val poseLogLocal = File(filesDir, "pose_log_${sessionUuid}.jsonl")
        copyUriToFile(cr, outPoseLogUri, poseLogLocal)
        Log.d(TAG, "[pose] saved uri=$outPoseLogUri -> local=${poseLogLocal.absolutePath} size=${poseLogLocal.length()}")

        // 2) make_log_pipeline 호출 → 모든 프레임에 대한 zoom 보간된 processed.json 생성
        // (고성능추적1과 동일하게 tracking + ts → processed.json)
        coroutineContext.ensureActive()
        onStage(Stage.PROCESSING, "run make_log_pipeline for zoom interpolation")
        
        val processedJson: File
        if (trackingUri != null && tsUri != null) {
            processedJson = generateLogFromSessionInternal(ctx, sessionUuid, trackingUri, tsUri, filesDir)
            Log.d(TAG, "[process] processed=${processedJson.absolutePath} size=${processedJson.length()}")
        } else {
            // 트래킹/ts 로그가 없으면 빈 파일 생성
            processedJson = File(filesDir, "${sessionUuid}_processed.json")
            processedJson.writeText("[]")
            Log.w(TAG, "[process] tracking/ts 로그 없음, 빈 processed 생성")
        }

        // 3) 병합 → merged.jsonl (local)
        coroutineContext.ensureActive()
        onStage(Stage.MERGING, "merge pose logs with zoom-interpolated data")
        val mergedLocal = File(filesDir, "merged_pose_${sessionUuid}.jsonl")
        
        if (targetRect != null) {
            // 팬캠 모드: 사용자 선택 ROI 추적
            runPythonFancamPoseMerge(poseLogLocal.absolutePath, targetRect, mergedLocal.absolutePath)
        } else {
            // 일반 모드: make_log_pipeline 처리된 json 이용
            runPythonPoseMerge(poseLogLocal.absolutePath, processedJson.absolutePath, mergedLocal.absolutePath)
        }
        
        Log.d(TAG, "[merge] mergedLocal=${mergedLocal.absolutePath} size=${mergedLocal.length()}")

        // 병합본을 SAF로 복사
        copyFileToUri(cr, mergedLocal, outMergedUri)
        Log.i(TAG, "포즈 병합 로그 저장 완료: $outMergedUri")

        onStage(Stage.FINISHED, "done")

        ResultPaths(
            poseLogUri = outPoseLogUri,
            poseLogLocalFile = poseLogLocal,
            processedJsonFile = processedJson,  // zoom 보간된 processed.json
            mergedLocalFile = mergedLocal,
            mergedOutUri = outMergedUri
        )
    }

    // -------------------- internals --------------------

    /**
     * make_log_pipeline.py 호출 → 모든 프레임에 대한 zoom 보간된 processed.json 생성
     * (LogOrchestrator의 generateLogFromSessionInternal과 동일)
     */
    private fun generateLogFromSessionInternal(
        ctx: Context,
        sessionUuid: String,
        trackingUri: Uri?,
        tsUri: Uri?,
        filesDir: File
    ): File {
        val trackingFile = File(filesDir, "${sessionUuid}_tracking.json")
        val tsFile = File(filesDir, "${sessionUuid}_frame_ts.json")
        val outputJson = File(filesDir, "${sessionUuid}_processed.json")

        fun copyIfNotNull(u: Uri?, dst: File) {
            if (u == null) throw IllegalStateException("필수 입력 누락: $dst")
            ctx.contentResolver.openInputStream(u)?.use { inp ->
                FileOutputStream(dst).use { out -> inp.copyTo(out) }
            } ?: throw IllegalStateException("openInputStream 실패: $u")
        }

        copyIfNotNull(trackingUri, trackingFile)
        copyIfNotNull(tsUri, tsFile)

        val py = Python.getInstance()
        val mod = py.getModule("make_log_pipeline")
        mod.callAttr(
            "process_video",
            trackingFile.absolutePath,
            tsFile.absolutePath,
            outputJson.absolutePath,
            0.2  // ema_alpha
        )
        return outputJson
    }

    /** PoseDetectLogManager 콜백을 suspend로 감싸기 */
    private suspend fun runPoseLogSuspend(
        ctx: Context,
        sessionUuid: String,
        videoUri: Uri,
        outPoseLogUri: Uri,
        onProgress: (frameIdx: Int, ptsMs: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val done = CompletableDeferred<Unit>()
        PoseDetectLogManager.makePoseLogFromMp4(
            ctx = ctx,
            sessionUuid = sessionUuid,
            videoUri = videoUri,
            outJsonUri = outPoseLogUri,
            onProgress = onProgress,
            onSuccess = { done.complete(Unit) },
            onError = { e -> if (!done.isCompleted) done.completeExceptionally(e) }
        )
        done.await()
    }

    /** Chaquopy로 Python merge 호출 */
    private fun runPythonPoseMerge(poseLogPath: String, trackingPath: String, outPath: String) {
        val py = Python.getInstance()
        val mod = py.getModule("merge_pose_logs")
        mod.callAttr("merge_pose_logs", poseLogPath, trackingPath, outPath)
    }

    /** Chaquopy로 Fancam 전용 Python pipeline 호출 (사용자 지정 ROI 기반) */
    private fun runPythonFancamPoseMerge(poseLogPath: String, targetRect: android.graphics.RectF, outPath: String) {
        val py = Python.getInstance()
        val mod = py.getModule("fancam_pose_pipeline")
        mod.callAttr(
            "process_fancam_video", 
            poseLogPath, 
            outPath,
            targetRect.left, 
            targetRect.top, 
            targetRect.right, 
            targetRect.bottom
        )
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
        // touch
        cr.openOutputStream(uri, "w")?.use { /* no-op */ } ?: return null
        return uri
    }
}

