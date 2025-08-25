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
                private val btn   = v.findViewById<MaterialButton>(R.id.btnPlay)

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

            /** 새로 만들기 페이지 (기존 showLockedOverlay 재사용) */
            inner class AddVH(v: View) : RecyclerView.ViewHolder(v) {
                fun bind(uuid: String) {
                    val iv     = itemView.findViewById<ImageView>(R.id.preview)
                    val btnNew = itemView.findViewById<Button>(R.id.btnNew)
                    val toggle = itemView.findViewById<MaterialButtonToggleGroup>(R.id.toggleCropMode)

                    val base = findFirstVideoByPrefix(itemView.context, "VID_${uuid}_zoomed_")
                        ?: findFirstVideoByPrefix(itemView.context, "VID_${uuid}_original_")

                    if (base != null) {
                        Glide.with(iv).load(base).centerCrop().into(iv)

                        // ✨ 여기 추가: 길이 → 예상시간 → 버튼에 표시
                        val durMs = getVideoDurationMs(itemView.context, base)
                        val est   = estimateSeconds(durMs) // 2초 + (길이/3) 반올림
                        btnNew.text = "시작\n예상시간: ${est}초"
                    } else {
                        iv.setImageDrawable(null)
                        btnNew.text = "시작"
                    }

                    btnNew.setOnClickListener {
                        if (base == null) {
                            Toast.makeText(itemView.context, "기준 영상이 없습니다.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val paddingFactor = when (toggle?.checkedButtonId) {
                            R.id.btnCenterMode -> 2f
                            R.id.btnWideMode   -> 3f
                            else               -> 2f
                        }
                        (parentFragment as? GalleryFragment)?.startCropFromBase(base, paddingFactor)
                        dismissAllowingStateLoss()
                    }

                    // ▼▼▼ 여기 추가: 로그 생성/병합/즉시 크롭 ▼▼▼
                    itemView.findViewById<Button>(R.id.btn_new_from_mp4)?.setOnClickListener {
                        if (base == null) {
                            Toast.makeText(itemView.context, "기준 영상이 없습니다.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        // base(zoomed 우선) 파일명에서 sessionUuid 추출
                        val fileName = requireContext().contentResolver
                            .query(base, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
                            ?.use { c ->
                                if (c.moveToFirst())
                                    c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))
                                        .substringBeforeLast('.')
                                else null
                            } ?: run {
                            Toast.makeText(requireContext(), "파일명을 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val parts = fileName.split('_')
                        if (parts.size < 2) {
                            Toast.makeText(requireContext(), "잘못된 파일명: $fileName", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val sessionUuid = parts[1]

                        // 감지용 비디오: original → zoomed → base
                        val videoUriForDetect =
                            findMediaUriViaParent("VID_${sessionUuid}_original_", "mp4")
                                ?: findMediaUriViaParent("VID_${sessionUuid}_zoomed_", "mp4")
                                ?: base

                        // 로그 파일들 (있으면 복사에 사용)
                        val trackingUri = findMediaUriViaParent("tracking_log_${sessionUuid}", "json")
                        val tsUri       = findMediaUriViaParent("tracking_log_${sessionUuid}_frame_ts", "json")

                        // ETA
                        val durMs  = getVideoDurationMs(requireContext(), videoUriForDetect)
                        val etaSec = estimateSeconds(durMs)
                        showBlockingProgress(etaSec)

                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                // 1) 두 로그 생성(or 재활용) + 병합
                                val res = LogOrchestrator.makeBothLogsAndMerge(
                                    ctx = requireContext(),
                                    sessionUuid = sessionUuid,
                                    videoUriForDetect = videoUriForDetect,
                                    trackingUri = trackingUri,
                                    tsUri = tsUri,
                                    filesDir = requireContext().filesDir,
                                    onStage = { stage, note -> Log.d("LogOrchestrator","stage=$stage note=$note") }
                                )
                                val mergedLogUri = res.mergedOutUri

                                // 2) 실제 자를 영상 FPS(zoomed → detect대상 → 30)
                                val fpsForCrop =
                                    getVideoFps(requireContext(), base)
                                        ?: getVideoFps(requireContext(), videoUriForDetect)
                                        ?: 30

                                // 3) 병합 로그를 File로 준비
                                val mergedFile: File = when (mergedLogUri.scheme) {
                                    "file" -> File(mergedLogUri.path!!)
                                    else   -> File(requireContext().filesDir, "${sessionUuid}_merged.jsonl")
                                        .also { copyUriToFile(requireContext(), mergedLogUri, it) }
                                }

                                // 4) 즉시 크롭 (padding은 병합 로그 기준 1.00f)
                                val outVideoUri = cropVideoFromLog(
                                    sessionUuid       = sessionUuid,
                                    outputJson        = mergedFile,
                                    videoUriToProcess = base,          // zoomed 우선 선택된 base를 실제로 자름
                                    fps               = fpsForCrop,
                                    paddingFactor     = 1.00f,
                                    logFormat         = LogFormat.MERGED_JSONL
                                )

                                withContext(Dispatchers.Main) {
                                    dismissBlockingProgress()
                                    Toast.makeText(requireContext(), "로그 2종+병합+크롭 완료!", Toast.LENGTH_SHORT).show()
                                    // 필요하면 여기서 미리보기/공유/갤러리 리프레시 트리거
                                    // 예) (parentFragment as? GalleryFragment)?.reloadForMode()
                                }
                            } catch (e: Throwable) {
                                Log.e("LogOrchestrator", "failed", e)
                                withContext(Dispatchers.Main) {
                                    dismissBlockingProgress()
                                    AlertDialog.Builder(requireContext())
                                        .setMessage("로그 생성/병합 실패:\n${e.message}")
                                        .setPositiveButton("닫기", null)
                                        .show()
                                }
                            }
                        }
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

    private fun showBlockingProgress(etaSec: Int) {
        if (blockingDialog?.isShowing == true) return
        blockingDialog = AlertDialog.Builder(requireContext())
            .setCancelable(false)
            .setMessage("처리 중...\n예상 ${etaSec}초")
            .create().apply { show() }
    }

    private fun dismissBlockingProgress() {
        blockingDialog?.dismiss()
        blockingDialog = null
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
