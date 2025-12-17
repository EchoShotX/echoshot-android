package com.echoshot.app.fragments

import android.app.Dialog
import android.content.ContentUris
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.echoshot.app.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import android.media.MediaMetadataRetriever

import android.util.Log
import android.widget.ProgressBar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

import com.echoshot.app.VideoPipeline
import com.echoshot.app.LogFormat
import com.echoshot.app.mp4detact.LogOrchestrator

class CroppedPagerDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_UUID = "uuid"

        fun newInstance(uuid: String) = CroppedPagerDialogFragment().apply {
            arguments = Bundle().apply { putString(ARG_UUID, uuid) }
        }
    }

    /** 페이지 모델 */
    private sealed class Page {
        data class Video(val uri: Uri, val durationMs: Long) : Page()
        object AddNew : Page()
    }

    private fun getVideoDurationMs(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L)
        } finally { r.release() }
    }

    private fun estimateSeconds(durationMs: Long): Int {
        val sec = durationMs / 1000.0
        return (2.0 + sec / 3.0).roundToInt()
    }

    private fun estimateSecondsHigh(durationMs: Long): Int {
        val sec = durationMs / 1000.0
        return (2.0 + sec * 5.0).roundToInt()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val root = layoutInflater.inflate(R.layout.dialog_cropped_pager, null)
        val pager = root.findViewById<ViewPager2>(R.id.viewPager)

        val uuid = requireArguments().getString(ARG_UUID)!!
        val pages = loadPages(requireContext(), uuid)

        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

            override fun getItemViewType(position: Int) =
                when (pages[position]) {
                    is Page.Video -> 0
                    is Page.AddNew -> 1
                }

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int
            ): RecyclerView.ViewHolder {
                val inf = LayoutInflater.from(parent.context)
                return if (viewType == 0) {
                    val v = inf.inflate(R.layout.item_cropped_pager_video, parent, false)
                    VideoVH(v)
                } else {
                    val v = inf.inflate(R.layout.item_cropped_pager_add, parent, false)
                    AddVH(v)
                }
            }

            override fun getItemCount() = pages.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                when (val p = pages[position]) {
                    is Page.Video  -> (holder as VideoVH).bind(p)
                    is Page.AddNew -> (holder as AddVH).bind(uuid)
                }
            }

            /** 크롭 결과 페이지 */
            inner class VideoVH(v: View) : RecyclerView.ViewHolder(v) {
                private val thumb = v.findViewById<ImageView>(R.id.thumb)
                private val tvDur = v.findViewById<TextView>(R.id.tvDuration)
                private val btn   = v.findViewById<ImageView>(R.id.btnPlay)

                fun bind(p: Page.Video) {
                    Glide.with(thumb).load(p.uri).centerCrop().into(thumb)
                    val m = TimeUnit.MILLISECONDS.toMinutes(p.durationMs)
                    val s = TimeUnit.MILLISECONDS.toSeconds(p.durationMs) % 60
                    tvDur.text = String.format("%02d:%02d", m, s)

                    // 바로 재생
                    btn.setOnClickListener {
                        val action = GalleryFragmentDirections
                            .actionGalleryFragmentToPreviewPlayerFragment(p.uri.toString())
                        requireParentFragment().findNavController().navigate(action)
                        dismissAllowingStateLoss()
                    }
                }
            }

            /** 새로 만들기 페이지 (dialog_pick_crop_mode와 동일한 선택 UI) */
            inner class AddVH(v: View) : RecyclerView.ViewHolder(v) {
                fun bind(uuid: String) {
                    val thumb = itemView.findViewById<ImageView>(R.id.thumb)
                    val btnAuto = itemView.findViewById<Button>(R.id.btnAuto)
                    val btnSot = itemView.findViewById<Button>(R.id.btnSot)

                    // 기준 영상: zoomed 우선 → original
                    val base = findFirstVideoByPrefix(itemView.context, "VID_${uuid}_zoomed_")
                        ?: findFirstVideoByPrefix(itemView.context, "VID_${uuid}_original_")

                    if (base != null) Glide.with(thumb).load(base).centerCrop().into(thumb)
                    else thumb.setImageDrawable(null)

                    // 파일명에서 sessionUuid 추출
                    val fileName = if (base != null)
                        requireContext().contentResolver
                            .query(base, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
                            ?.use { c ->
                                if (c.moveToFirst())
                                    c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))
                                        .substringBeforeLast('.')
                                else null
                            } else null

                    if (base == null || fileName == null) {
                        Toast.makeText(requireContext(), getString(R.string.base_video_not_found), Toast.LENGTH_SHORT).show()
                        return
                    }

                    val parts = fileName.split('_')
                    if (parts.size < 2) {
                        Toast.makeText(requireContext(), "${getString(R.string.invalid_filename)}: $fileName", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val sessionUuid = parts[1]

                    // original만 찾고, 없으면 클릭한 uri로 폴백
                    val originalPrefix = fileName.substringBefore("_zoomed_") + "_original_"
                    val originalUri = findMediaUriViaParent(originalPrefix, "mp4")
                    val videoUriForSot = originalUri ?: base

                    // 자동 구도 생성 버튼
                    btnAuto.setOnClickListener {
                        // MakeAutoDetactionFragment 실행
                        MakeAutoDetactionFragment
                            .newInstance(base)
                            .show(parentFragmentManager, "autoDetect")
                        dismissAllowingStateLoss()
                    }

                    // 인물 선택 구도 생성(SOT) 버튼
                    btnSot.setOnClickListener {
                        // HybridPickerDialogFragment 실행
                        HybridPickerDialogFragment
                            .newInstance(videoUriForSot, sessionUuid)
                            .show(parentFragmentManager, "hybridPicker")
                        dismissAllowingStateLoss()
                    }
                }
            }

        }

        return AlertDialog.Builder(requireContext())
            .setView(root)
            .create()
            .apply { window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
    }

    /** 다이얼로그를 카드처럼 중앙에 뜨게 */
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
    }

    /** uuid에 해당하는 크롭 결과(최신순) + 마지막에 새로 만들기 페이지 추가 */
    private fun loadPages(ctx: Context, uuid: String): List<Page> {
        val out = mutableListOf<Page>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_TAKEN
        )
        val sel = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val selArgs = arrayOf("VID_${uuid}_cropped_%")
        val sort = "${MediaStore.Video.Media.DATE_TAKEN} DESC"

        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            proj, sel, selArgs, sort
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) {
                val id  = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                )
                val dur = c.getLong(durCol)
                out += Page.Video(uri, dur)
            }
        }

        out += Page.AddNew
        return out
    }

    // ===== 진행 다이얼로그 (아주 간단한 버전) =====
    private var blockingDialog: AlertDialog? = null
    private var progressDialog: android.app.AlertDialog? = null
    private var progressTickerJob: kotlinx.coroutines.Job? = null

    private fun showBlockingProgress(etaSec: Int) {
        dismissBlockingProgress() // 혹시 남아 있으면 정리

        if (!isAdded) return

        val v = layoutInflater.inflate(R.layout.dialog_progress_blocking, null)
        val tvTitle = v.findViewById<TextView>(R.id.tvTitle)
        val tvSubtitle = v.findViewById<TextView>(R.id.tvSubtitle)
        val tvNote = v.findViewById<TextView>(R.id.tvNote)
        val bar = v.findViewById<ProgressBar>(R.id.progressDeterminate)
        val spin = v.findViewById<ProgressBar>(R.id.progressIndeterminate)

        // 초기 상태: ETA 기반 가변 진행률
        bar.visibility = View.VISIBLE
        spin.visibility = View.GONE
        tvSubtitle.text = getString(R.string.estimated_time, etaSec)

        progressDialog = android.app.AlertDialog.Builder(requireContext())
            .setView(v)
            .setCancelable(false)
            .create().apply {
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                // Back 키로도 닫히지 않게
                setOnKeyListener { _, keyCode, _ ->
                    keyCode == android.view.KeyEvent.KEYCODE_BACK
                }
                show()
                // 사이즈
                window?.setLayout(
                    (300 * resources.displayMetrics.density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

        // 1초마다 진행률 업데이트 (ETA를 넘기면 무한 로딩으로 전환)
        val start = System.currentTimeMillis()
        progressTickerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                val elapsedSec = ((System.currentTimeMillis() - start) / 1000.0).toInt()
                if (elapsedSec <= etaSec && etaSec > 0) {
                    val pct = ((elapsedSec.toDouble() / etaSec) * 100).coerceIn(0.0, 99.0).toInt()
                    bar.progress = pct
                    val remain = (etaSec - elapsedSec).coerceAtLeast(0)
                    tvSubtitle.text = getString(R.string.estimated_time_remaining, remain)
                } else {
                    // ETA 초과 → 무한 로딩으로
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

    // ===== 부모의 findMediaUri 사용 헬퍼 (없으면 null) =====
    private fun findMediaUriViaParent(prefix: String, extension: String): Uri? {
        val parent = parentFragment as? GalleryFragment ?: return null
        return parent.findMediaUri(requireContext(), prefix, extension)
    }

    // ===== FPS 추출 (간단 폴백) =====
    private fun getVideoFps(ctx: Context, uri: Uri): Int? {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(ctx, uri)
            val fr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            val fps = if (fr != null && fr > 0f) fr
            else {
                val frameCount = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull()
                val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (frameCount != null && durMs != null && durMs > 0) (frameCount * 1000f / durMs) else null
            }
            fps?.roundToInt()
        } catch (_: Throwable) { null }
    }

    // ===== Uri -> File 복사 =====
    private fun copyUriToFile(ctx: Context, src: Uri, dst: File) {
        ctx.contentResolver.openInputStream(src).use { ins ->
            FileOutputStream(dst).use { outs -> if (ins != null) ins.copyTo(outs) }
        }
    }

    // ===== 병합 로그로 즉시 크롭 =====
    private suspend fun cropVideoFromLog(
        sessionUuid: String,
        outputJson: File,
        videoUriToProcess: Uri,
        fps: Int,
        paddingFactor: Float,
        logFormat: LogFormat = LogFormat.MERGED_JSONL
    ): Uri? {
        return VideoPipeline.processSessionFromLog(
            context = requireContext(),
            sessionId = sessionUuid,
            srcVideoUri = videoUriToProcess,
            fps = fps,
            paddingFactor = paddingFactor,
            logFile = outputJson,
            format = logFormat
        )
    }




    private fun findFirstVideoByPrefix(ctx: Context, prefix: String): Uri? {
        val proj = arrayOf(MediaStore.Video.Media._ID)
        val sel = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val selArgs = arrayOf("${prefix}%")
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            proj, sel, selArgs, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                return ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                )
            }
        }
        return null
    }
}
