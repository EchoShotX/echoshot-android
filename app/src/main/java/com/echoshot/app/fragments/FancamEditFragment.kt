package com.echoshot.app.fragments

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.bumptech.glide.Glide
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.echoshot.app.LogFormat
import com.echoshot.app.OutputResolution
import com.echoshot.app.R
import com.echoshot.app.VideoPipeline
import com.echoshot.app.mp4detact.PoseLogOrchestrator
import com.echoshot.app.utils.FancamHistoryManager
import com.echoshot.app.utils.setupBottomNavigationBar
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 사용자가 갤러리에서 단일 영상을 선택하여
 * 인물 트래킹 직캠 영상을 생성하는 Fragment.
 *
 * 기존 PoseLogOrchestrator + VideoPipeline 파이프라인을 재사용하며,
 * trackingUri=null, tsUri=null 로 호출하여
 * zoom=1.0 (좌표 변환 없음) 단일 영상 모드로 동작합니다.
 *
 * 히스토리는 FancamHistoryManager를 통해 앱 내부 저장소에 영구 보관됩니다.
 */
class FancamEditFragment : Fragment() {

    companion object {
        private const val TAG = "FancamEditFragment"
    }

    // Views
    private var btnStartEdit: View? = null
    private var editProgressContainer: View? = null
    private var editFileName: TextView? = null
    private var editProgressBar: ProgressBar? = null
    private var editProgressText: TextView? = null
    private var editHistoryRecyclerView: RecyclerView? = null

    // 처리 관련
    private var progressDialog: AlertDialog? = null
    private var progressTickerJob: Job? = null
    private var editHistoryAdapter: EditHistoryAdapter? = null
    private val historyEntries = mutableListOf<FancamHistoryManager.HistoryEntry>()
    private val thumbnailCache = mutableMapOf<String, Bitmap?>() // id -> bitmap
    private var visibleCount = 3
    private var btnShowMore: View? = null

    // 파일 선택기
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { showVideoPreviewDialog(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_fancam_edit, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // View 바인딩
        btnStartEdit = view.findViewById(R.id.btnStartEdit)
        editProgressContainer = view.findViewById(R.id.editProgressContainer)
        editFileName = view.findViewById(R.id.editFileName)
        editProgressBar = view.findViewById(R.id.editProgressBar)
        editProgressText = view.findViewById(R.id.editProgressText)
        editHistoryRecyclerView = view.findViewById(R.id.editHistoryRecyclerView)

        // 네비게이션 바 설정
        setupBottomNavigationBar(
            currentPage = "upload",
            onHomeClick = {
                val action = FancamEditFragmentDirections.actionFancamEditFragmentToHomeFragment()
                findNavController().navigate(action)
            },
            onGalleryClick = {
                navigateToGallery()
            },
            onCameraClick = {
                navigateToCamera()
            },
            onArchiveClick = {
                // 현재 페이지이므로 아무 동작 없음
            },
            onProfileClick = {
                if (!com.echoshot.app.utils.DeploymentModeManager.isDeploymentMode()) {
                    val action = FancamEditFragmentDirections.actionFancamEditFragmentToProfileFragment()
                    findNavController().navigate(action)
                }
            }
        )

        // 동영상 편집 시작 버튼 클릭
        btnStartEdit?.setOnClickListener {
            videoPickerLauncher.launch("video/*")
        }

        // RecyclerView 설정
        editHistoryAdapter = EditHistoryAdapter()
        editHistoryRecyclerView?.layoutManager = LinearLayoutManager(requireContext())
        editHistoryRecyclerView?.adapter = editHistoryAdapter

        // 더보기 버튼 설정
        btnShowMore = view.findViewById(R.id.btnShowMore)
        btnShowMore?.setOnClickListener {
            visibleCount += 10
            updateShowMoreButton()
            editHistoryAdapter?.notifyDataSetChanged()
        }

        // 저장된 히스토리 로드
        loadPersistedHistory()
    }

    override fun onResume() {
        super.onResume()
        loadPersistedHistory()
    }

    override fun onPause() {
        super.onPause()
        progressTickerJob?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        progressTickerJob?.cancel()
        progressDialog?.dismiss()
        btnStartEdit = null
        editProgressContainer = null
        editFileName = null
        editProgressBar = null
        editProgressText = null
        editHistoryRecyclerView = null
    }

    /**
     * SharedPreferences에서 히스토리를 로드하여 리스트에 표시
     */
    private fun loadPersistedHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext()
            val loaded = FancamHistoryManager.loadHistory(ctx)

            // 썸네일 사전 로드
            loaded.forEach { entry ->
                thumbnailCache[entry.id] = FancamHistoryManager.loadThumbnail(entry.thumbnailPath)
            }

            withContext(Dispatchers.Main) {
                historyEntries.clear()
                historyEntries.addAll(loaded)
                updateShowMoreButton()
                editHistoryAdapter?.notifyDataSetChanged()
            }
        }
    }

    private fun updateShowMoreButton() {
        if (visibleCount < historyEntries.size) {
            btnShowMore?.visibility = View.VISIBLE
        } else {
            btnShowMore?.visibility = View.GONE
        }
    }

    /**
     * 동영상 미리보기 + 크롭모드/해상도 선택 다이얼로그
     */
    private fun showVideoPreviewDialog(videoUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(requireContext(), videoUri)

                val thumbnail = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val fps = extractFps(retriever) ?: 30

                retriever.release()

                withContext(Dispatchers.Main) {
                    val root = layoutInflater.inflate(R.layout.dialog_locked_thumbnail, null)

                    val iv = root.findViewById<ImageView>(R.id.lockedThumbnail)
                    val btnStart = root.findViewById<Button>(R.id.startButton)
                    val toggleCrop = root.findViewById<MaterialButtonToggleGroup>(R.id.toggleCropMode)
                    val toggleTrack = root.findViewById<MaterialButtonToggleGroup>(R.id.toggleTrackMode)
                    val toggleResolution = root.findViewById<MaterialButtonToggleGroup>(R.id.toggleResolution)

                    // 썸네일 설정
                    thumbnail?.let { iv.setImageBitmap(it) }

                    // 해상도 선택 기본값
                    if (toggleResolution.checkedButtonId == View.NO_ID) {
                        toggleResolution.check(R.id.btnFHD)
                    }

                    // 트래킹 모드 토글 숨김 (단일 영상에서는 항상 고성능 포즈 트래킹만 사용)
                    toggleTrack.visibility = View.GONE

                    // 크롭 모드 기본값
                    if (toggleCrop.checkedButtonId == View.NO_ID) {
                        toggleCrop.check(R.id.btnCenterMode)
                    }

                    // 해상도 가져오기
                    fun getOutputResolution(): OutputResolution = when (toggleResolution.checkedButtonId) {
                        R.id.btnHD -> OutputResolution.HD
                        R.id.btnFHD -> OutputResolution.FHD
                        R.id.btnUHD -> OutputResolution.UHD
                        else -> OutputResolution.FHD
                    }

                    fun getOutputResolutionStr(): String = when (toggleResolution.checkedButtonId) {
                        R.id.btnHD -> "HD"
                        R.id.btnFHD -> "FHD"
                        R.id.btnUHD -> "UHD"
                        else -> "FHD"
                    }

                    // ETA
                    val etaSec = estimateSecondsHigh(durationMs)
                    btnStart.text = getString(R.string.start_with_mode, getString(R.string.high_spec_track), etaSec)

                    val dialog = AlertDialog.Builder(requireContext())
                        .setView(root)
                        .create()
                        .apply { window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }

                    btnStart.setOnClickListener {
                        dialog.dismiss()

                        val paddingFactor = when (toggleCrop.checkedButtonId) {
                            R.id.btnCenterMode -> 3.5f
                            R.id.btnWideMode -> 5f
                            else -> 3f
                        }

                        startFancamProcessing(
                            videoUri = videoUri,
                            thumbnail = thumbnail,
                            width = width,
                            height = height,
                            durationMs = durationMs,
                            fps = fps,
                            paddingFactor = paddingFactor,
                            outputResolution = getOutputResolution(),
                            outputResolutionStr = getOutputResolutionStr(),
                            fileName = getFileName(videoUri) ?: "video.mp4"
                        )
                    }

                    dialog.show()
                    dialog.window?.apply {
                        setLayout(
                            (300 * resources.displayMetrics.density).toInt(),
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setGravity(Gravity.CENTER)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "동영상 정보 가져오기 실패", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.fancam_video_info_error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 직캠 생성 파이프라인 실행
     * PoseLogOrchestrator + VideoPipeline 재사용
     */
    private fun startFancamProcessing(
        videoUri: Uri,
        thumbnail: Bitmap?,
        width: Int,
        height: Int,
        durationMs: Long,
        fps: Int,
        paddingFactor: Float,
        outputResolution: OutputResolution,
        outputResolutionStr: String,
        fileName: String
    ) {
        val ctx = requireContext()
        val sessionUuid = UUID.randomUUID().toString()
        val etaSec = estimateSecondsHigh(durationMs)

        // 썸네일을 파일로 저장 (영구 보관)
        val thumbnailPath = thumbnail?.let {
            FancamHistoryManager.saveThumbnail(ctx, sessionUuid, it)
        }

        // 원본 영상 해상도 획득
        var originalWidth = width
        var originalHeight = height
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(ctx, videoUri)
            originalWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: width
            originalHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: height
            retriever.release()
        } catch (e: Exception) {
            Log.e("FancamEditFragment", "Failed to get video resolution", e)
        }

        // 히스토리 엔트리 생성 + 영구 저장
        val entry = FancamHistoryManager.HistoryEntry(
            id = sessionUuid,
            fileName = fileName,
            createdAt = System.currentTimeMillis(),
            status = "processing",
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            paddingFactor = paddingFactor,
            outputResolution = outputResolutionStr,
            outputFilePath = null,
            thumbnailPath = thumbnailPath,
            editMode = "single"
        )
        FancamHistoryManager.addEntry(ctx, entry)

        // 메모리 리스트에도 추가
        thumbnailCache[sessionUuid] = thumbnail
        historyEntries.add(0, entry)
        editHistoryAdapter?.notifyItemInserted(0)

        // 프로그레스 다이얼로그 표시
        showBlockingProgress(etaSec)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1) PoseLogOrchestrator 호출 (trackingUri=null, tsUri=null)
                Log.d(TAG, "[FANCAM] 파이프라인 시작: session=$sessionUuid")
                val result = PoseLogOrchestrator.makePoseLogsAndMerge(
                    ctx = ctx,
                    sessionUuid = sessionUuid,
                    videoUriForDetect = videoUri,
                    trackingUri = null,
                    tsUri = null,
                    filesDir = ctx.filesDir,
                    onStage = { stage, note -> Log.d(TAG, "[FANCAM] stage=$stage note=$note") },
                    onProgress = { frameIdx, ptsMs ->
                        if (frameIdx % 100 == 0) Log.d(TAG, "[FANCAM] frame=$frameIdx pts=$ptsMs")
                    }
                )

                // 2) VideoPipeline으로 크롭 (같은 영상에서 크롭)
                Log.d(TAG, "[FANCAM] 크롭 시작: mergedFile=${result.mergedLocalFile.absolutePath}")
                val croppedUri = VideoPipeline.processSessionFromLog(
                    context = ctx,
                    sessionId = sessionUuid,
                    srcVideoUri = videoUri,
                    fps = fps,
                    paddingFactor = paddingFactor,
                    logFile = result.mergedLocalFile,
                    format = LogFormat.MERGED_JSONL,
                    outputResolution = outputResolution
                )

                // 결과에 따라 영구 저장소 업데이트
                val newStatus = if (croppedUri != null) "complete" else "failed"
                FancamHistoryManager.updateStatus(
                    ctx, sessionUuid, newStatus,
                    outputFilePath = croppedUri?.toString()
                )

                withContext(Dispatchers.Main) {
                    dismissBlockingProgress()

                    // 메모리 리스트도 업데이트
                    val idx = historyEntries.indexOfFirst { it.id == sessionUuid }
                    if (idx >= 0) {
                        historyEntries[idx] = historyEntries[idx].copy(
                            status = newStatus,
                            outputFilePath = croppedUri?.toString()
                        )
                        editHistoryAdapter?.notifyItemChanged(idx)
                    }

                    if (croppedUri != null) {
                        Toast.makeText(ctx, getString(R.string.fancam_complete), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, getString(R.string.fancam_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "FANCAM 파이프라인 실패", e)

                FancamHistoryManager.updateStatus(ctx, sessionUuid, "failed")

                withContext(Dispatchers.Main) {
                    dismissBlockingProgress()

                    val idx = historyEntries.indexOfFirst { it.id == sessionUuid }
                    if (idx >= 0) {
                        historyEntries[idx] = historyEntries[idx].copy(status = "failed")
                        editHistoryAdapter?.notifyItemChanged(idx)
                    }

                    AlertDialog.Builder(ctx)
                        .setMessage("${getString(R.string.fancam_failed)}:\n${e.message}")
                        .setPositiveButton(getString(R.string.close), null)
                        .show()
                }
            }
        }
    }

    // ---- 유틸 ----

    private fun extractFps(retriever: MediaMetadataRetriever): Int? {
        return try {
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            (fps ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull()?.let { fc ->
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
                if (durMs > 0) fc * 1000f / durMs else null
            })?.roundToInt()
        } catch (_: Throwable) { null }
    }

    private fun estimateSecondsHigh(durationMs: Long): Int {
        val sec = durationMs / 1000.0
        return (2.0 + sec * 5.0).roundToInt()
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.let {
                val cut = it.lastIndexOf('/')
                if (cut != -1) it.substring(cut + 1) else it
            }
        }
        return result
    }

    private fun statusToDisplayText(status: String): String {
        return when (status) {
            "processing" -> getString(R.string.fancam_status_processing)
            "complete" -> getString(R.string.fancam_status_complete)
            "failed" -> getString(R.string.fancam_status_failed)
            else -> getString(R.string.fancam_status_waiting)
        }
    }

    @SuppressLint("MissingPermission")
    private fun navigateToGallery() {
        val context = requireContext()
        val cameraManager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val selectedCameraId = cameraManager.cameraIdList.firstOrNull() ?: "0"

        val action = FancamEditFragmentDirections.actionFancamEditFragmentToGalleryFragment(
            selectedCameraId, 1920, 1080, 30, 0L, 0,
            false, false, 0, false, 0, true, "hybrid"
        ).apply {
            startBasic = false
        }
        findNavController().navigate(action)
    }

    @SuppressLint("MissingPermission")
    private fun navigateToCamera() {
        val context = requireContext()
        val cameraManager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val selectedCameraId = cameraManager.cameraIdList.firstOrNull() ?: return

        val action = FancamEditFragmentDirections.actionFancamEditFragmentToCustomPreviewFragment(
            selectedCameraId, 1920, 1080, 30,
            android.hardware.camera2.params.DynamicRangeProfiles.STANDARD,
            android.hardware.camera2.params.ColorSpaceProfiles.UNSPECIFIED,
            false, false, 0, false, 0, true, "hybrid"
        )
        findNavController().navigate(action)
    }

    // ---- 프로그레스 다이얼로그 ----

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

    // ---- RecyclerView 어댑터 ----

    private inner class EditHistoryAdapter :
        RecyclerView.Adapter<EditHistoryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardRoot: View = view // CardView 자체
            val thumbnail: ImageView = view.findViewById(R.id.itemThumbnail)
            val resolution: TextView = view.findViewById(R.id.itemResolution)
            val status: TextView = view.findViewById(R.id.itemStatus)
            val modeBadge: TextView = view.findViewById(R.id.itemMode)
            val date: TextView = view.findViewById(R.id.itemDate)
            val btnDelete: View = view.findViewById(R.id.btnDeleteItem)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_fancam_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = historyEntries[position]

            // 썸네일 로딩 개선 (로그 추가 및 로직 정밀화)
            val cachedBitmap = thumbnailCache[entry.id]
            if (cachedBitmap != null) {
                holder.thumbnail.setImageBitmap(cachedBitmap)
            } else {
                holder.thumbnail.setImageResource(android.R.color.darker_gray) // 로딩 전 초기화
                lifecycleScope.launch(Dispatchers.IO) {
                    val thumbSource: Uri? = when {
                        !entry.thumbnailPath.isNullOrEmpty() && java.io.File(entry.thumbnailPath).exists() -> Uri.fromFile(java.io.File(entry.thumbnailPath))
                        !entry.outputFilePath.isNullOrEmpty() -> {
                            // 저장된 경로가 content:// URI 형태인지 확인
                            if (entry.outputFilePath.startsWith("content://")) Uri.parse(entry.outputFilePath)
                            else queryVideoUriByName(requireContext(), entry.fileName)
                        }
                        else -> queryVideoUriByName(requireContext(), entry.fileName)
                    }

                    Log.d("FancamHistory", "Thumbnail source for ${entry.id}: $thumbSource")

                    if (thumbSource != null) {
                        try {
                            val retriever = MediaMetadataRetriever()
                            retriever.setDataSource(requireContext(), thumbSource)
                            val bmp = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            retriever.release()

                            withContext(Dispatchers.Main) {
                                if (bmp != null) {
                                    holder.thumbnail.setImageBitmap(bmp)
                                    thumbnailCache[entry.id] = bmp
                                    Log.d("FancamHistory", "Successfully extracted frame for ${entry.id}")
                                } else {
                                    Log.w("FancamHistory", "Frame extraction returned null, trying Glide...")
                                    Glide.with(holder.itemView.context)
                                        .load(thumbSource)
                                        .centerCrop()
                                        .into(holder.thumbnail)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("FancamHistory", "Error extracting thumbnail for ${entry.id}: ${e.message}")
                            withContext(Dispatchers.Main) {
                                Glide.with(holder.itemView.context)
                                    .load(thumbSource)
                                    .centerCrop()
                                    .into(holder.thumbnail)
                            }
                        }
                    }
                }
            }

            // 타이틀 (제거됨)

            // 해상도 + 설정 정보
            val cropMode = when {
                entry.paddingFactor <= 3.5f -> getString(R.string.center_mode)
                entry.paddingFactor >= 5f -> getString(R.string.wide_mode)
                else -> "Custom"
            }
            holder.resolution.text = "${entry.originalWidth}x${entry.originalHeight} · ${entry.outputResolution} · $cropMode"

            // 상태
            holder.status.text = statusToDisplayText(entry.status)

            // 모드 딱지 설정
            when (entry.editMode) {
                "auto_fast" -> {
                    holder.modeBadge.text = getString(R.string.fancam_mode_auto_fast)
                    holder.modeBadge.setBackgroundColor(Color.parseColor("#FF9800")) // Orange
                }
                "auto_high" -> {
                    holder.modeBadge.text = getString(R.string.fancam_mode_auto_high)
                    holder.modeBadge.setBackgroundColor(Color.parseColor("#4A90E2")) // Blue
                }
                "hybrid" -> {
                    holder.modeBadge.text = getString(R.string.fancam_mode_hybrid)
                    holder.modeBadge.setBackgroundColor(Color.parseColor("#9C27B0")) // Purple
                }
                else -> {
                    holder.modeBadge.text = getString(R.string.fancam_mode_single)
                    holder.modeBadge.setBackgroundColor(Color.parseColor("#757575")) // Gray
                }
            }

            // 편집 시간
            val timeFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            holder.date.text = timeFormat.format(java.util.Date(entry.createdAt))

            // 아이템 클릭 - 영상 재생 (완료된 경우에만)
            holder.cardRoot.setOnClickListener {
                if (entry.status == "complete" && entry.outputFilePath != null) {
                    val action = FancamEditFragmentDirections.actionFancamEditFragmentToPreviewPlayerFragment(entry.outputFilePath)
                    findNavController().navigate(action)
                } else if (entry.status == "failed") {
                    Toast.makeText(requireContext(), getString(R.string.fancam_failed), Toast.LENGTH_SHORT).show()
                } else if (entry.status == "processing") {
                    Toast.makeText(requireContext(), getString(R.string.fancam_processing), Toast.LENGTH_SHORT).show()
                }
            }

            // 삭제 버튼 클릭
            holder.btnDelete.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.fancam_edit_label))
                    .setMessage(getString(R.string.fancam_history_delete_confirm))
                    .setPositiveButton(getString(R.string.delete_button)) { _, _ ->
                        val ctx = requireContext()
                        FancamHistoryManager.deleteEntry(ctx, entry.id)
                        thumbnailCache.remove(entry.id)
                        historyEntries.removeAt(holder.bindingAdapterPosition)
                        notifyItemRemoved(holder.bindingAdapterPosition)
                    }
                    .setNegativeButton(getString(R.string.close_button), null)
                    .show()
            }
        }

        override fun getItemCount() = if (historyEntries.isEmpty()) 0 else Math.min(visibleCount, historyEntries.size)

        private fun queryVideoUriByName(context: android.content.Context, fileName: String?): Uri? {
            if (fileName == null) return null
            val projection = arrayOf(android.provider.MediaStore.Video.Media._ID)
            
            // 파일명에서 확장자를 제외한 베이스 이름 추출 (검색 성공률을 높이기 위함)
            val baseName = if (fileName.contains(".")) fileName.substringBeforeLast(".") else fileName
            
            val selection = "${android.provider.MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$baseName%")
            
            return try {
                context.contentResolver.query(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, selectionArgs, null
                )?.use { cursor: android.database.Cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID))
                        val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        Log.d("FancamHistory", "Found real MediaStore Uri for $fileName: $uri")
                        uri
                    } else {
                        Log.w("FancamHistory", "MediaStore query returned empty for $fileName (query: $baseName)")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e("FancamHistory", "MediaStore query error for $fileName", e)
                null
            }
        }
    }
}
