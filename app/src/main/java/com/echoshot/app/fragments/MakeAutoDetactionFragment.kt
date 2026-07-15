package com.echoshot.app.fragments

import android.app.Dialog
import android.content.ContentUris
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.echoshot.app.R
import com.echoshot.app.LogFormat
import com.echoshot.app.OutputResolution
import com.echoshot.app.VideoPipeline
import com.echoshot.app.mp4detact.LogOrchestrator
import com.echoshot.app.mp4detact.PoseLogOrchestrator
import com.echoshot.app.utils.FancamHistoryManager
import com.google.android.material.button.MaterialButtonToggleGroup
import com.chaquo.python.Python
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 갤러리에서 선택한 영상에 대해
 * - (저사양) 기존 로그 기반 즉시 크롭
 * - (고사양) 로그 2종 생성+병합 후 즉시 크롭
 * 을 수행하는 재사용 가능한 다이얼로그 프래그먼트.
 *
 * 필요 리소스 레이아웃:
 * - R.layout.dialog_locked_thumbnail  (썸네일, 토글, 시작 버튼)
 * - R.layout.dialog_progress_blocking  (진행 다이얼로그)
 *
 * 호출:
 * MakeAutoDetactionFragment.newInstance(uri).show(childFragmentManager, "autoDetect")
 */
class MakeAutoDetactionFragment : DialogFragment() {

    interface Callbacks {
        fun refreshGallery() {} // 선택: 호스트에서 갤러리 새로고침 원할 때만 구현
    }

    /** 추적 모드 (HIGH1은 내부적으로 유지, UI에서 숨김) */
    enum class TrackMode { FAST, HIGH }

    companion object {
        private const val ARG_URI = "arg_uri"
        private const val TAG = "MakeAutoDetaction"

        fun newInstance(uri: Uri): MakeAutoDetactionFragment =
            MakeAutoDetactionFragment().apply {
                arguments = Bundle().apply { putString(ARG_URI, uri.toString()) }
            }
    }

    private val targetUri: Uri by lazy {
        Uri.parse(requireArguments().getString(ARG_URI)!!)
    }

    // 진행 다이얼로그
    private var progressDialog: AlertDialog? = null
    private var progressTickerJob: Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val root = layoutInflater.inflate(R.layout.dialog_locked_thumbnail, null)

        val iv = root.findViewById<ImageView>(R.id.lockedThumbnail)
        val btnStart = root.findViewById<Button>(R.id.startButton)
        val toggleCrop = root.findViewById<MaterialButtonToggleGroup>(R.id.toggleCropMode)
        val toggleTrack = root.findViewById<MaterialButtonToggleGroup>(R.id.toggleTrackMode)
        val toggleResolution = root.findViewById<MaterialButtonToggleGroup>(R.id.toggleResolution)

        Glide.with(this).load(targetUri).centerCrop().into(iv)

        // 해상도 선택 기본값: FHD
        if (toggleResolution.checkedButtonId == View.NO_ID) {
            toggleResolution.check(R.id.btnFHD)
        }

        // 해상도 가져오기
        fun getOutputResolution(): OutputResolution = when (toggleResolution.checkedButtonId) {
            R.id.btnHD -> OutputResolution.HD
            R.id.btnFHD -> OutputResolution.FHD
            R.id.btnUHD -> OutputResolution.UHD
            else -> OutputResolution.FHD
        }

        // 1) DISPLAY_NAME → sessionUuid
        val fileName = ctx.contentResolver
            .query(targetUri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst())
                    c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))
                        .substringBeforeLast('.')
                else null
            } ?: run {
            Toast.makeText(ctx, getString(R.string.cannot_get_filename), Toast.LENGTH_SHORT).show()
            return AlertDialog.Builder(ctx).create()
        }
        val parts = fileName.split('_')
        if (parts.size < 2) {
            Toast.makeText(ctx, "${getString(R.string.invalid_filename)}: $fileName", Toast.LENGTH_SHORT).show()
            return AlertDialog.Builder(ctx).create()
        }
        val sessionUuid = parts[1]

        // 2) 자를 대상: zoomed 우선, 없으면 클릭한 uri
        val zoomedPrefix = fileName.substringBefore("_original_") + "_zoomed_"
        val baseUriToProcess = findMediaUri(ctx, zoomedPrefix, "mp4") ?: targetUri

        // 3) 감지/로그용: original 우선(ETA/고사양)
        val originalPrefix = fileName.substringBefore("_zoomed_") + "_original_"
        val originalUri = findMediaUri(ctx, originalPrefix, "mp4")

        // 기본 토글값: 인물중심 + 빠른추적
        if (toggleCrop.checkedButtonId == View.NO_ID)  toggleCrop.check(R.id.btnCenterMode)
        if (toggleTrack.checkedButtonId == View.NO_ID) toggleTrack.check(R.id.btnFastTrack)

        // 추적 모드 가져오기
        fun getTrackMode(): TrackMode = when (toggleTrack.checkedButtonId) {
            R.id.btnFastTrack -> TrackMode.FAST
            R.id.btnHighSpec -> TrackMode.HIGH
            else -> TrackMode.FAST
        }

        // ETA + 버튼 라벨
        fun updateStartLabel() {
            val mode = getTrackMode()
            val isHigh = mode != TrackMode.FAST
            val detectTarget =
                if (isHigh) findMediaUri(ctx, "VID_${sessionUuid}_original_", "mp4")
                    ?: findMediaUri(ctx, "VID_${sessionUuid}_zoomed_", "mp4")
                    ?: baseUriToProcess
                else baseUriToProcess

            val etaMsTarget = getVideoDurationMs(ctx, detectTarget)
            val etaSec = if (isHigh) estimateSecondsHigh(etaMsTarget)
            else estimateSeconds(etaMsTarget)

            val modeLabel = when (mode) {
                TrackMode.FAST -> getString(R.string.fast_track)
                TrackMode.HIGH -> getString(R.string.high_spec_track)
            }
            btnStart.text = getString(R.string.start_with_mode, modeLabel, etaSec)
        }
        updateStartLabel()
        toggleCrop.addOnButtonCheckedListener { group, checkedId, isChecked -> updateStartLabel() }
        toggleTrack.addOnButtonCheckedListener { group, checkedId, isChecked -> updateStartLabel() }

        // 시작 버튼
        btnStart.setOnClickListener {
            com.echoshot.app.AnalyticsTracker.log(requireContext(), "composition_start", "composition_type" to "auto")
            val paddingFactor = when (toggleCrop.checkedButtonId) {
                R.id.btnCenterMode -> 3.5f   // 인물 중심 (상체 기준)
                R.id.btnWideMode   -> 5f   // 와이드 (상체 기준)
                else               -> 3f
            }
            val trackMode = getTrackMode()

            when (trackMode) {
                TrackMode.FAST -> {
                    // ===== 빠른 추적: 기존 로그로 즉시 크롭 =====
                    val trackingUri = findMediaUri(ctx, "tracking_log_${sessionUuid}", "json")
                    val tsUri       = findMediaUri(ctx, "tracking_log_${sessionUuid}_frame_ts", "json")
                    
                    // ✅ 추적 데이터 없음 체크 (파일 삭제됨 또는 저사양 기기)
                    if (trackingUri == null || tsUri == null) {
                        Toast.makeText(ctx, getString(R.string.error_log_file_not_found), Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    
                    val durMs = getVideoDurationMs(ctx, baseUriToProcess)
                    val (vWidth, vHeight) = getVideoSize(ctx, baseUriToProcess)
                    showBlockingProgress(estimateSeconds(durMs))
                    
                    // ✅ 히스토리 등록: 빠른추적 (auto_fast)
                    val historyEntry = FancamHistoryManager.HistoryEntry(
                        id = sessionUuid,
                        fileName = fileName,
                        createdAt = System.currentTimeMillis(),
                        status = "processing",
                        originalWidth = vWidth,
                        originalHeight = vHeight,
                        paddingFactor = paddingFactor,
                        outputResolution = getOutputResolution().name,
                        outputFilePath = null,
                        thumbnailPath = null,
                        editMode = "auto_fast"
                    )
                    FancamHistoryManager.addEntry(ctx, historyEntry)
                    
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            // ① 로그 생성(파이썬 파이프라인)
                            val outJson = generateLogFromSession(ctx, sessionUuid, trackingUri, tsUri, ctx.filesDir)
                            // ② 크롭
                            val croppedUri = cropVideoFromLog(
                                ctx = ctx,
                                sessionUuid = sessionUuid,
                                outputJson = outJson,
                                videoUriToProcess = baseUriToProcess,
                                fps = getVideoFps(ctx, baseUriToProcess) ?: 30,
                                paddingFactor = paddingFactor,
                                logFormat = LogFormat.PROCESSED_JSON,
                                outputResolution = getOutputResolution()
                            )
                            withContext(Dispatchers.Main) {
                                dismissBlockingProgress()
                                if (croppedUri != null) {
                                    // 히스토리 상태 업데이트: 완료
                                    FancamHistoryManager.updateStatus(ctx, sessionUuid, "complete", croppedUri.toString())
                                    
                                    Toast.makeText(ctx, getString(R.string.fast_crop_complete), Toast.LENGTH_SHORT).show()
                                    (parentFragment as? Callbacks ?: activity as? Callbacks)?.refreshGallery()
                                    dismissAllowingStateLoss()
                                } else {
                                    Toast.makeText(ctx, getString(R.string.crop_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                // 히스토리 상태 업데이트: 실패
                                FancamHistoryManager.updateStatus(ctx, sessionUuid, "failed")
                                
                                dismissBlockingProgress()
                                AlertDialog.Builder(ctx)
                                    .setMessage("${getString(R.string.error_occurred)}:\n${e.message}")
                                    .setPositiveButton(getString(R.string.close), null)
                                    .show()
                            }
                        }
                    }
                }

                /* HIGH1 (YOLOv8 기반) - UI에서 숨김, 나중에 필요시 복원 가능
                TrackMode.HIGH1 -> {
                    // ===== 고성능 추적1: YOLOv8 기반 로그 2종 생성+병합 후 크롭 =====
                    val videoUriForDetect =
                        findMediaUri(ctx, "VID_${sessionUuid}_original_", "mp4")
                            ?: findMediaUri(ctx, "VID_${sessionUuid}_zoomed_", "mp4")
                            ?: baseUriToProcess

                    val trackingUri = findMediaUri(ctx, "tracking_log_${sessionUuid}", "json")
                    val tsUri       = findMediaUri(ctx, "tracking_log_${sessionUuid}_frame_ts", "json")

                    val durMs = getVideoDurationMs(ctx, videoUriForDetect)
                    showBlockingProgress(estimateSecondsHigh(durMs))

                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val res = LogOrchestrator.makeBothLogsAndMerge(
                                ctx = ctx,
                                sessionUuid = sessionUuid,
                                videoUriForDetect = videoUriForDetect,
                                trackingUri = trackingUri,
                                tsUri = tsUri,
                                filesDir = ctx.filesDir,
                                onStage = { stage, note -> Log.d(TAG, "[HIGH1] stage=$stage note=$note") }
                            )
                            val mergedLogUri = res.mergedOutUri

                            val fpsForCrop =
                                getVideoFps(ctx, baseUriToProcess)
                                    ?: getVideoFps(ctx, videoUriForDetect)
                                    ?: 30

                            val mergedFile: File = when (mergedLogUri.scheme) {
                                "file" -> File(mergedLogUri.path!!)
                                else   -> File(ctx.filesDir, "${sessionUuid}_merged.jsonl")
                                    .also { copyUriToFile(ctx, mergedLogUri, it) }
                            }

                            @Suppress("UNUSED_VARIABLE")
                            val outUri = VideoPipeline.processSessionFromLog(
                                context = ctx,
                                sessionId = sessionUuid,
                                srcVideoUri = baseUriToProcess,
                                fps = fpsForCrop,
                                paddingFactor = paddingFactor,
                                logFile = mergedFile,
                                format = LogFormat.MERGED_JSONL,
                                outputResolution = getOutputResolution()
                            )

                            withContext(Dispatchers.Main) {
                                dismissBlockingProgress()
                                Toast.makeText(ctx, getString(R.string.high_spec_crop_complete), Toast.LENGTH_SHORT).show()
                                (parentFragment as? Callbacks ?: activity as? Callbacks)?.refreshGallery()
                                dismissAllowingStateLoss()
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "HIGH1 failed", e)
                            withContext(Dispatchers.Main) {
                                dismissBlockingProgress()
                                AlertDialog.Builder(ctx)
                                    .setMessage("${getString(R.string.high_spec_failed)}:\n${e.message}")
                                    .setPositiveButton(getString(R.string.close), null)
                                    .show()
                            }
                        }
                    }
                }
                END OF HIGH1 BLOCK */

                TrackMode.HIGH -> {
                    // ===== 고성능 추적2: YOLO11n-pose 기반 관절 트래킹 =====
                    val videoUriForDetect =
                        findMediaUri(ctx, "VID_${sessionUuid}_original_", "mp4")
                            ?: findMediaUri(ctx, "VID_${sessionUuid}_zoomed_", "mp4")
                            ?: baseUriToProcess

                    val trackingUri = findMediaUri(ctx, "tracking_log_${sessionUuid}", "json")
                    val tsUri       = findMediaUri(ctx, "tracking_log_${sessionUuid}_frame_ts", "json")
                    
                    // ✅ 추적 데이터 없음 체크 (파일 삭제됨 또는 저사양 기기)
                    if (trackingUri == null || tsUri == null) {
                        Toast.makeText(ctx, getString(R.string.error_log_file_not_found), Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }

                    val durMs = getVideoDurationMs(ctx, videoUriForDetect)
                    val (vWidth, vHeight) = getVideoSize(ctx, videoUriForDetect)
                    showBlockingProgress(estimateSecondsHigh(durMs))

                    // ✅ 히스토리 등록: 고성능추적 (auto_high)
                    val historyEntry = FancamHistoryManager.HistoryEntry(
                        id = sessionUuid,
                        fileName = fileName,
                        createdAt = System.currentTimeMillis(),
                        status = "processing",
                        originalWidth = vWidth,
                        originalHeight = vHeight,
                        paddingFactor = paddingFactor,
                        outputResolution = getOutputResolution().name,
                        outputFilePath = null,
                        thumbnailPath = null,
                        editMode = "auto_high"
                    )
                    FancamHistoryManager.addEntry(ctx, historyEntry)

                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val res = PoseLogOrchestrator.makePoseLogsAndMerge(
                                ctx = ctx,
                                sessionUuid = sessionUuid,
                                videoUriForDetect = videoUriForDetect,
                                trackingUri = trackingUri,
                                tsUri = tsUri,
                                filesDir = ctx.filesDir,
                                onStage = { stage, note -> Log.d(TAG, "[HIGH-POSE] stage=$stage note=$note") },
                                onProgress = { frameIdx, ptsMs -> 
                                    if (frameIdx % 100 == 0) Log.d(TAG, "[HIGH] frame=$frameIdx pts=$ptsMs")
                                }
                            )
                            val mergedLogUri = res.mergedOutUri

                            val fpsForCrop =
                                getVideoFps(ctx, baseUriToProcess)
                                    ?: getVideoFps(ctx, videoUriForDetect)
                                    ?: 30

                            val mergedFile: File = when (mergedLogUri.scheme) {
                                "file" -> File(mergedLogUri.path!!)
                                else   -> File(ctx.filesDir, "${sessionUuid}_pose_merged.jsonl")
                                    .also { copyUriToFile(ctx, mergedLogUri, it) }
                            }

                            val outUri = VideoPipeline.processSessionFromLog(
                                context = ctx,
                                sessionId = sessionUuid,
                                srcVideoUri = baseUriToProcess,
                                fps = fpsForCrop,
                                paddingFactor = paddingFactor,
                                logFile = mergedFile,
                                format = LogFormat.MERGED_JSONL,
                                outputResolution = getOutputResolution()
                            )

                            withContext(Dispatchers.Main) {
                                dismissBlockingProgress()
                                if (outUri != null) {
                                    // 히스토리 상태 업데이트: 완료
                                    FancamHistoryManager.updateStatus(ctx, sessionUuid, "complete", outUri.toString())
                                    
                                    Toast.makeText(ctx, getString(R.string.high_spec_crop_complete), Toast.LENGTH_SHORT).show()
                                    (parentFragment as? Callbacks ?: activity as? Callbacks)?.refreshGallery()
                                    dismissAllowingStateLoss()
                                } else {
                                    // 히스토리 상태 업데이트: 실패
                                    FancamHistoryManager.updateStatus(ctx, sessionUuid, "failed")
                                    Toast.makeText(ctx, getString(R.string.crop_failed), Toast.LENGTH_SHORT).show()
                                    dismissAllowingStateLoss()
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "HIGH-POSE failed", e)
                            withContext(Dispatchers.Main) {
                                // 히스토리 상태 업데이트: 실패
                                FancamHistoryManager.updateStatus(ctx, sessionUuid, "failed")
                                
                                dismissBlockingProgress()
                                AlertDialog.Builder(ctx)
                                    .setMessage("${getString(R.string.high_spec_failed)}:\n${e.message}")
                                    .setPositiveButton(getString(R.string.close), null)
                                    .show()
                            }
                        }
                    }
                }
            }
        }

        return AlertDialog.Builder(ctx)
            .setView(root)
            .create()
            .apply { window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (300 * resources.displayMetrics.density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }
    }

    // ------------------ 유틸 & 내부 파이프라인 ------------------

    private fun getVideoDurationMs(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L)
        } finally { r.release() }
    }

    private fun getVideoSize(ctx: Context, uri: Uri): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1080
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1920
            w to h
        } catch (e: Exception) {
            1080 to 1920
        } finally { r.release() }
    }

    private fun getVideoFps(ctx: Context, uri: Uri): Int? = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(ctx, uri)
        val fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
        (fps ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull()?.let { fc ->
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            if (durMs > 0) fc * 1000f / durMs else null
        })?.roundToInt()
    } catch (_: Throwable) { null }

    private fun estimateSeconds(durationMs: Long): Int {
        val sec = durationMs / 1000.0
        return (2.0 + sec / 3.0).roundToInt()
    }

    private fun estimateSecondsHigh(durationMs: Long): Int {
        val sec = durationMs / 1000.0
        return (2.0 + sec * 5.0).roundToInt()
    }

    private fun copyUriToFile(ctx: Context, src: Uri, dst: File) {
        ctx.contentResolver.openInputStream(src)!!.use { inp ->
            FileOutputStream(dst).use { out -> inp.copyTo(out) }
        }
    }

    private fun findMediaUri(context: Context, prefix: String, extension: String): Uri? {
        val (collection, nameCol, relativePath) = when (extension.lowercase()) {
            "mp4" -> Triple(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.DISPLAY_NAME,
                "${Environment.DIRECTORY_DCIM}/EchoShot"
            )
            "json", "jsonl" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                Triple(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    "${Environment.DIRECTORY_DOWNLOADS}/EchoShotLogs"
                )
            else
                Triple(
                    MediaStore.Files.getContentUri("external"),
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    "${Environment.DIRECTORY_DOWNLOADS}/EchoShotLogs"
                )
            else -> return null
        }

        // API 29+ 에서는 RELATIVE_PATH로 폴더 필터링
        val sel: String
        val selArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sel = "$nameCol LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            selArgs = arrayOf("${prefix}%.$extension", "$relativePath%")
        } else {
            sel = "$nameCol LIKE ?"
            selArgs = arrayOf("${prefix}%.$extension")
        }
        val proj = arrayOf(MediaStore.MediaColumns._ID)

        return context.contentResolver.query(collection, proj, sel, selArgs, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    ContentUris.withAppendedId(collection, id)
                } else null
            }
    }

    private suspend fun generateLogFromSession(
        ctx: Context,
        sessionUuid: String,
        trackingUri: Uri?,
        tsUri: Uri?,
        filesDir: File
    ): File {
        val trackingFile = File(filesDir, "${sessionUuid}_tracking.json")
        val tsFile       = File(filesDir, "${sessionUuid}_frame_ts.json")
        val outputJson   = File(filesDir, "${sessionUuid}_processed.json")

        fun copyIfExists(src: Uri?, dst: File) {
            if (src == null) return
            ctx.contentResolver.openInputStream(src)?.use { inp ->
                FileOutputStream(dst).use { out -> inp.copyTo(out) }
            }
        }

        copyIfExists(trackingUri, trackingFile)
        copyIfExists(tsUri, tsFile)

        // Python(Chaquopy) 파이프라인 호출
        // 큰 움직임은 따라가고 작은 움직임은 무시하도록 파라미터 조정
        val py  = Python.getInstance()
        val mod = py.getModule("make_log_pipeline")
        mod.callAttr(
            "process_video",
            trackingFile.absolutePath,
            tsFile.absolutePath,
            outputJson.absolutePath,
            20.0,  // q_pos_base: 과정잡음(위치) - 더 크면 더 부드러움
            150.0, // q_vel_base: 과정잡음(속도) - 더 크면 더 부드러움
            80.0   // r_meas_base: 관측잡음 - 더 크면 작은 움직임 무시, 큰 움직임만 따라감
        )
        return outputJson
    }

    private suspend fun cropVideoFromLog(
        ctx: Context,
        sessionUuid: String,
        outputJson: File,
        videoUriToProcess: Uri,
        fps: Int,
        paddingFactor: Float,
        logFormat: LogFormat,
        outputResolution: OutputResolution
    ): Uri? = withContext(Dispatchers.IO) {
        VideoPipeline.processSessionFromLog(
            context = ctx,
            sessionId = sessionUuid,
            srcVideoUri = videoUriToProcess,
            fps = fps,
            paddingFactor = paddingFactor,
            logFile = outputJson,
            format = logFormat,
            outputResolution = outputResolution
        )
    }

    // --------------- 진행 다이얼로그 ---------------

    private fun showBlockingProgress(etaSec: Int) {
        dismissBlockingProgress()

        val v = layoutInflater.inflate(R.layout.dialog_progress_blocking, null)
        val tvTitle = v.findViewById<TextView>(R.id.tvTitle)
        val tvSubtitle = v.findViewById<TextView>(R.id.tvSubtitle)
        val bar = v.findViewById<ProgressBar>(R.id.progressDeterminate)
        val spin = v.findViewById<ProgressBar>(R.id.progressIndeterminate)

        bar.visibility = View.VISIBLE
        spin.visibility = View.GONE
        tvSubtitle.text = getString(R.string.estimated_time, etaSec)

        progressDialog = AlertDialog.Builder(requireContext())
            .setView(v)
            .setCancelable(false)
            .create().apply {
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
                show()
                window?.setLayout(
                    (300 * resources.displayMetrics.density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

        val start = System.currentTimeMillis()
        progressTickerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                val elapsedSec = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - start).toInt()
                if (elapsedSec <= etaSec && etaSec > 0) {
                    val pct = ((elapsedSec.toDouble() / etaSec) * 100).coerceIn(0.0, 99.0).toInt()
                    bar.progress = pct
                    tvSubtitle.text = getString(R.string.estimated_time_remaining, (etaSec - elapsedSec).coerceAtLeast(0))
                } else {
                    bar.visibility = View.GONE
                    spin.visibility = View.VISIBLE
                    tvSubtitle.text = getString(R.string.please_wait)
                }
                delay(1000)
            }
        }
    }

    private fun dismissBlockingProgress() {
        progressTickerJob?.cancel()
        progressTickerJob = null
        progressDialog?.dismiss()
        progressDialog = null
    }
}
