package com.echoshot.app.fragments

import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.AppCompatImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.chaquo.python.Python
import com.echoshot.app.R
import com.echoshot.app.VideoPipeline
import com.echoshot.app.databinding.FragmentGalleryBinding
import com.echoshot.app.databinding.GalleryItemBinding
import com.echoshot.app.databinding.ItemDateHeaderBinding
import com.echoshot.app.fragments.GalleryFragment.SectionedAdapter.Companion.TYPE_HEADER
import com.echoshot.app.fragments.GalleryFragment.SectionedAdapter.Companion.TYPE_VIDEO
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import android.annotation.SuppressLint
import androidx.documentfile.provider.DocumentFile
import com.echoshot.app.LogFormat
import com.echoshot.app.mp4detact.DetectLogManager
import com.echoshot.app.mp4detact.GlCtx
import com.echoshot.app.mp4detact.JsonLogger
import com.echoshot.app.mp4detact.LogOrchestrator
import com.echoshot.app.mp4detact.VideoDetectFacade
import java.io.FileNotFoundException
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors


// 1) 리스트에 들어갈 두 가지 타입
private sealed class ListItem {
    data class Header(val label: String) : ListItem()
    data class Video(
        val uri: Uri,
        val durationMs: Long,
        val uuid: String,
        val type: String,      // "zoomed" | "original" | "cropped"
        val locked: Boolean = false,
        val croppedCount: Int = 0
    ) : ListItem()
    object Placeholder : ListItem()
}

// 파일 상단 class 바깥 or 클래스 내부 상단에 추가
private enum class GalleryMode { BASIC, EXTENDED }

class GalleryFragment : Fragment() {

    private var sectionedItems: List<ListItem> = emptyList()
    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!
    private val args: GalleryFragmentArgs by navArgs()

    // Fragment 필드로 한 번만 만들기(재사용)
    private lateinit var glCtx: GlCtx

    private var mode: GalleryMode = GalleryMode.BASIC

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


    private fun copyUriToFile(ctx: Context, src: Uri, dst: File) {
        ctx.contentResolver.openInputStream(src)!!.use { inp ->
            FileOutputStream(dst).use { out -> inp.copyTo(out) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?) : View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        glCtx = GlCtx()

        binding.backIcon.setOnClickListener { 
            // ✅ 방법 1: 명시적으로 CustomPreviewFragment로 네비게이션하여 모든 파라미터 전달
            navigateBackToCustomPreview()
        }
        
        // 시스템 백 버튼도 처리
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    navigateBackToCustomPreview()
                }
            }
        )

        // 1) 초기 모드: 네비게이션 인자대로
        val startBasic = args.startBasic
        mode = if (startBasic) GalleryMode.BASIC else GalleryMode.EXTENDED
        setActiveTab(isBasic = startBasic)   // 버튼 색/테두리 갱신
        reloadForMode(startBasic)            // 리스트 로딩

        // 2) 그리드 레이아웃 (헤더 span은 확장 모드일 때만 3칸)
        val glm = GridLayoutManager(requireContext(), 3)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val item = sectionedItems.getOrNull(position)
                return when (mode) {
                    GalleryMode.BASIC -> 1
                    GalleryMode.EXTENDED -> when (item) {
                        is ListItem.Header      -> 3
                        is ListItem.Video       -> 1
                        is ListItem.Placeholder -> 1
                        else                    -> 1
                    }
                }
            }
        }
        binding.galleryRecyclerView.layoutManager = glm

        // 3) 탭 전환
        binding.btnGallery.setOnClickListener {
            if (mode != GalleryMode.BASIC) {
                mode = GalleryMode.BASIC
                setActiveTab(isBasic = true)
                reloadForMode(true)
            }
        }
        binding.btnExtendedGallery.setOnClickListener {
            if (mode != GalleryMode.EXTENDED) {
                mode = GalleryMode.EXTENDED
                setActiveTab(isBasic = false)
                reloadForMode(false)
            }
        }
    }


    private fun reloadForMode(isBasic: Boolean? = null) {
        // 전달된 파라미터가 있으면 mode 업데이트
        isBasic?.let { mode = if (it) GalleryMode.BASIC else GalleryMode.EXTENDED }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val items: List<ListItem> = when (mode) {
                GalleryMode.BASIC    -> loadBasicItems()   // 시간순 단순 리스트
                GalleryMode.EXTENDED -> loadVideoItems()   // 기존(섹션/잠금/크롭) 리스트
            }

            withContext(Dispatchers.Main) {
                sectionedItems = items

                binding.galleryRecyclerView.adapter = SectionedAdapter(
                    items,
                    onItemClick = { v ->
                        // BASIC: 바로 플레이어 / EXTENDED: 기존 동작 유지
                        if (mode == GalleryMode.EXTENDED && v.type == "cropped") {
                            CroppedPagerDialogFragment
                                .newInstance(v.uuid)
                                .show(childFragmentManager, "croppedPager")
                        } else {
                            findNavController().navigate(
                                GalleryFragmentDirections
                                    .actionGalleryFragmentToPreviewPlayerFragment(v.uri.toString())
                            )
                        }
                    },
                    onLockedClick = { v ->
                        if (mode == GalleryMode.EXTENDED) showLockedOverlay(v.uri)
                        // BASIC에선 잠금 개념 없음
                    }
                )
            }
        }
    }

    private fun setActiveTab(isBasic: Boolean) {
        val white = Color.WHITE
        val gray  = Color.GRAY
        binding.btnGallery.setTextColor(if (isBasic) white else gray)
        binding.btnExtendedGallery.setTextColor(if (isBasic) gray else white)
        binding.btnGallery.strokeColor        = ColorStateList.valueOf(if (isBasic) white else gray)
        binding.btnExtendedGallery.strokeColor= ColorStateList.valueOf(if (isBasic) gray else white)
    }

    private fun loadBasicItems(): List<ListItem> {
        val result = mutableListOf<Pair<ListItem, Long>>() // 날짜순 정렬을 위해 Pair 사용
        
        // 동영상 쿼리
        val videoProj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATA
        )
        val videoSel = "${MediaStore.Video.Media.DATA} LIKE ?"
        val videoSelArgs = arrayOf("%/DCIM/Camera2App/%")
        val videoSort = "${MediaStore.Video.Media.DATE_TAKEN} DESC"

        requireContext().contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProj, videoSel, videoSelArgs, videoSort
        )?.use { c ->
            val idCol  = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) {
                val id  = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                val dur = c.getLong(durCol)
                val dateTaken = c.getLong(dateCol)
                // 어댑터 재사용: uuid는 빈값, type은 임의(재생만 하면 되니까 "original")
                result += Pair(ListItem.Video(uri = uri, durationMs = dur, uuid = "", type = "original"), dateTaken)
            }
        }
        
        // 사진 쿼리 (DCIM/EchoShot 경로 포함)
        val imageProj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATA
        )
        val imageSel = "${MediaStore.Images.Media.DATA} LIKE ? OR ${MediaStore.Images.Media.DATA} LIKE ?"
        val imageSelArgs = arrayOf("%/DCIM/Camera2App/%", "%/DCIM/EchoShot/%")
        val imageSort = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        requireContext().contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProj, imageSel, imageSelArgs, imageSort
        )?.use { c ->
            val idCol  = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                val id  = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val dateTaken = c.getLong(dateCol)
                // 사진은 durationMs를 0으로 설정 (어댑터에서 0이면 재생 시간 숨김)
                result += Pair(ListItem.Video(uri = uri, durationMs = 0, uuid = "", type = "original"), dateTaken)
            }
        }
        
        // 날짜순으로 정렬 (최신순)
        result.sortByDescending { it.second }
        
        return result.map { it.first }
    }


    override fun onDestroyView() {
        try { glCtx.release() } catch (_: Throwable) {}
        super.onDestroyView()
        _binding = null
    }

    /** MediaStore 에서 DATe_TAKEN 도 함께 가져와 ListItem.Video 로 변환 */
    private fun loadVideoItems(): List<ListItem> {
        data class MediaItem(
            val uri: Uri,
            val dateTaken: Long,
            val durationMs: Long,
            val uuid: String,
            val type: String, // "zoomed" | "original" | "cropped"
            val croppedCount: Int = 0
        )

        val items = mutableListOf<MediaItem>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DURATION
        )

        // 기존: val sel = "${MediaStore.Video.Media.DATA} LIKE ?"
        // ↓ 전면(파일명 시작이 VID_front_)을 제외
        val sel = "${MediaStore.Video.Media.DATA} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} NOT LIKE ?"
        val selArgs = arrayOf("%/DCIM/Camera2App/%", "VID_front_%")

        val sort = "${MediaStore.Video.Media.DATE_TAKEN} DESC"

        requireContext().contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            proj, sel, selArgs, sort
        )?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val durCol  = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) {
                val id   = c.getLong(idCol)
                val name = c.getString(nameCol) // e.g. "VID_<uuid>_cropped_..."
                val parts = name.split('_')
                if (parts.size < 3) continue
                val uuid = parts[1]
                val type = parts[2] // zoomed | original | cropped
                val uri  = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                items += MediaItem(uri, c.getLong(dateCol), c.getLong(durCol), uuid, type)
            }
        }

        // 날짜 라벨링
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0)
            set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0)
        }
        val todayStart = cal.timeInMillis
        val yesterdayStart = todayStart - TimeUnit.DAYS.toMillis(1)
        val sdf = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())

        val sections = linkedMapOf<String, MutableList<MediaItem>>()
        items.forEach { mi ->
            val label = when {
                mi.dateTaken >= todayStart     -> "오늘"
                mi.dateTaken >= yesterdayStart -> "어제"
                else                            -> sdf.format(Date(mi.dateTaken))
            }
            sections.getOrPut(label) { mutableListOf() } += mi
        }

        // 섹션 → UUID 묶음 → 3칸(zoomed, original, cropped/locked/placeholder)
        val result = mutableListOf<ListItem>()
        for ((label, list) in sections) {
            result += ListItem.Header(label)

            val byUuid: Map<String, List<MediaItem>> = list.groupBy { it.uuid }
            byUuid.values.forEach { group ->
                val zoomed   = group.find { it.type == "zoomed" }
                val original = group.find { it.type == "original" }

                val croppedList = group.filter { it.type == "cropped" }
                val cropped     = croppedList.maxByOrNull { it.dateTaken }
                val croppedCnt  = croppedList.size

                // 1) 왼쪽: zoomed (없으면 Placeholder)
                result += if (zoomed != null) {
                    ListItem.Video(
                        uri = zoomed.uri,
                        durationMs = zoomed.durationMs,
                        uuid = zoomed.uuid,
                        type = "zoomed",
                        locked = false
                    )
                } else {
                    ListItem.Placeholder
                }

                // 2) 가운데: original (없으면 Placeholder)
                result += if (original != null) {
                    ListItem.Video(
                        uri = original.uri,
                        durationMs = original.durationMs,
                        uuid = original.uuid,
                        type = "original",
                        locked = false
                    )
                } else {
                    ListItem.Placeholder
                }

                // 3) 오른쪽: cropped or lock/placeholder
                result += when {
                    cropped != null -> ListItem.Video(
                        uri = cropped.uri,
                        durationMs = cropped.durationMs,
                        uuid = cropped.uuid,
                        type = "cropped",
                        locked = false,
                        croppedCount = croppedCnt       // ★ 여기!
                    )
                    original != null -> ListItem.Video(
                        uri = original.uri,
                        durationMs = original.durationMs,
                        uuid = original.uuid,
                        type = "original",
                        locked = true
                    )
                    else -> ListItem.Placeholder
                }
            }
        }
        return result
    }


    companion object {
        private const val TAG = "GalleryFragment"
    }

    // NEW: 모드 선택 다이얼로그
    // NEW: 모드 선택 다이얼로그
    // NEW: 모드 선택 다이얼로그
    fun showLockedOverlay(uri: Uri) {
        val v = layoutInflater.inflate(R.layout.dialog_pick_crop_mode, null)
        val img = v.findViewById<ImageView>(R.id.thumb)
        val btnAuto = v.findViewById<Button>(R.id.btnAuto)
        val btnSot  = v.findViewById<Button>(R.id.btnSot)

        Glide.with(this).load(uri).centerCrop().into(img)

        // DISPLAY_NAME -> sessionUuid
        val fileName = requireContext().contentResolver
            .query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst())
                c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))
                    .substringBeforeLast('.') else null } ?: run {
            Toast.makeText(requireContext(), "파일명을 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val parts = fileName.split('_')
        if (parts.size < 2) {
            Toast.makeText(requireContext(), "잘못된 파일명: $fileName", Toast.LENGTH_SHORT).show()
            return
        }
        val sessionUuid = parts[1]

        // ✅ original만 찾고, 없으면 클릭한 uri로 폴백
        val originalPrefix = fileName.substringBefore("_zoomed_") + "_original_"
        val originalUri    = findMediaUri(requireContext(), originalPrefix, "mp4")
        val videoUriForSot = originalUri ?: uri

        val dialog = AlertDialog.Builder(requireContext())
            .setView(v)
            .create().apply { window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }

        btnAuto.setOnClickListener {
            dialog.dismiss()
            // 🔽 새 독립 다이얼로그 프래그먼트 호출
            MakeAutoDetactionFragment
                .newInstance(uri)
                .show(childFragmentManager, "autoDetect")
        }

        btnSot.setOnClickListener {
            dialog.dismiss()
            // ✅ 하이브리드 프로세서 실행 (YOLO + Pose + Face)
            HybridPickerDialogFragment
                .newInstance(videoUriForSot, sessionUuid)
                .show(childFragmentManager, "hybridPicker")
        }

        dialog.show()
        val widthPx = (300 * resources.displayMetrics.density).toInt()
        dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }



    fun findMediaUri(
        context: Context,
        prefix: String,
        extension: String
    ): Uri? {
        // 확장자에 따라 적절한 컬렉션 선택
        val (collection, nameCol) = when (extension.lowercase()) {
            "mp4" -> Pair(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.DISPLAY_NAME
            )
            "json" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                Pair(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    MediaStore.MediaColumns.DISPLAY_NAME
                ) else
                Pair(
                    MediaStore.Files.getContentUri("external"),
                    MediaStore.MediaColumns.DISPLAY_NAME
                )
            else -> return null
        }

        // DISPLAY_NAME LIKE 'prefix%.extension'
        val sel = "$nameCol LIKE ?"
        val selArgs = arrayOf("${prefix}%.$extension")
        val proj = arrayOf(MediaStore.MediaColumns._ID)

        Log.d(TAG, "▶ findMediaUri: 검색조건 $nameCol LIKE '${selArgs[0]}'")

        return context.contentResolver.query(collection, proj, sel, selArgs, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    )
                    ContentUris.withAppendedId(collection, id).also {
                        Log.d(TAG, "   ▶ findMediaUri: 찾은 Uri=$it")
                    }
                } else {
                    Log.w(TAG, "   ▶ findMediaUri: 해당 이름의 파일 없음")
                    null
                }
            }
    }


    /** 섹션 헤더 + 비디오 뷰타입 2종 처리 어댑터 */
    private class SectionedAdapter(
        private val items: List<ListItem>,
        private val onItemClick: (ListItem.Video) -> Unit,
        private val onLockedClick: (ListItem.Video) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            const val TYPE_HEADER      = 0
            const val TYPE_VIDEO       = 1
            const val TYPE_PLACEHOLDER = 2
        }

        override fun getItemViewType(position: Int) = when (items[position]) {
            is ListItem.Header      -> TYPE_HEADER
            is ListItem.Video       -> TYPE_VIDEO
            is ListItem.Placeholder -> TYPE_PLACEHOLDER
        }

        private inner class HeaderVH(val binding: ItemDateHeaderBinding)
            : RecyclerView.ViewHolder(binding.root)

        inner class VideoVH(private val binding: GalleryItemBinding)
            : RecyclerView.ViewHolder(binding.root) {

            fun bind(v: ListItem.Video) {
                Glide.with(binding.root).load(v.uri).centerCrop().into(binding.thumbnailImageView)

                // durationMs가 0이면 사진이므로 재생 시간 숨김
                if (v.durationMs > 0) {
                    val m = TimeUnit.MILLISECONDS.toMinutes(v.durationMs)
                    val s = TimeUnit.MILLISECONDS.toSeconds(v.durationMs) % 60
                    binding.tvPlayDuration.text = String.format("%02d:%02d", m, s)
                    binding.tvPlayDuration.visibility = View.VISIBLE
                } else {
                    binding.tvPlayDuration.visibility = View.GONE
                }

                // ★ 2개 이상 cropped면 배지 보여주기 (cropped 칸만)
                binding.badgeMulti.visibility =
                    if (v.type == "cropped" && v.croppedCount > 1) View.VISIBLE else View.GONE

                if (v.locked) {
                    binding.dimOverlay.visibility = View.VISIBLE
                    binding.lockOverlayImageView.visibility = View.VISIBLE
                    binding.root.setOnClickListener { onLockedClick(v) }
                } else {
                    binding.dimOverlay.visibility = View.GONE
                    binding.lockOverlayImageView.visibility = View.GONE
                    binding.root.setOnClickListener { onItemClick(v) }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                TYPE_HEADER -> {
                    val b = ItemDateHeaderBinding.inflate(
                        LayoutInflater.from(parent.context), parent, false
                    )
                    HeaderVH(b)
                }
                TYPE_VIDEO -> {
                    val b = GalleryItemBinding.inflate(
                        LayoutInflater.from(parent.context), parent, false
                    )
                    VideoVH(b)
                }
                TYPE_PLACEHOLDER -> {
                    val marginPx = (4 * parent.context.resources.displayMetrics.density).toInt()
                    // 1) MarginLayoutParams 생성
                    val lp = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(marginPx, marginPx, marginPx, marginPx)
                    }

                    // 2) FrameLayout 컨테이너 생성 및 margin 적용
                    val container = FrameLayout(parent.context).apply {
                        layoutParams = lp
                        setBackgroundColor(Color.WHITE)
                        // 원하는 높이가 있다면 아래처럼 지정 가능 (예: thumbnail 높이에 맞추려면 0dp + aspect ratio 처리 필요)
                        minimumHeight = (parent.context.resources.displayMetrics.density * 100).toInt()
                    }

                    // 3) 중앙에 회색 쓰레기통 아이콘 추가
                    val iconSize = (24 * parent.context.resources.displayMetrics.density).toInt()
                    val icon = AppCompatImageView(parent.context).apply {
                        setImageResource(R.drawable.ic_folder_off)
                        imageTintList = ColorStateList.valueOf(Color.GRAY)
                        layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
                    }
                    container.addView(icon)

                    object : RecyclerView.ViewHolder(container) {}
                }
                else -> throw IllegalArgumentException("Unknown viewType $viewType")
            }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val it = items[position]) {
                is ListItem.Header -> (holder as HeaderVH).binding.headerText.text = it.label
                is ListItem.Video  -> (holder as VideoVH).bind(it)   // ← uri, locked, durationMs 따로 안 넘김
                is ListItem.Placeholder -> { /* no-op */ }
            }
        }
    }


    // 갤러리 새로고침 (간단 버전)
    fun refreshGallery() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val items = loadVideoItems()
            withContext(Dispatchers.Main) {
                sectionedItems = items
                (binding.galleryRecyclerView.adapter as? RecyclerView.Adapter<*>)?.let { ad ->
                    // 통째로 갈아끼우는게 간단
                    binding.galleryRecyclerView.adapter = null
                    binding.galleryRecyclerView.adapter = SectionedAdapter(
                        items,
                        onItemClick = { v ->
                            when (v.type) {
                                "cropped" -> {
                                    CroppedPagerDialogFragment
                                        .newInstance(v.uuid)
                                        .show(childFragmentManager, "croppedPager")
                                }
                                else -> findNavController().navigate(
                                    GalleryFragmentDirections
                                        .actionGalleryFragmentToPreviewPlayerFragment(v.uri.toString())
                                )
                            }
                        },
                        onLockedClick = { v -> showLockedOverlay(v.uri) }
                    )
                }
            }
        }
    }
    
    /**
     * 갤러리에서 원래 프래그먼트로 명시적으로 네비게이션
     * 전면 카메라에서 온 경우 CustomFrontPreviewFragment로, 후면 카메라인 경우 CustomPreviewFragment로 복귀
     * 모든 파라미터를 전달하여 안정적인 상태 복원 보장
     */
    private fun navigateBackToCustomPreview() {
        // ✅ 카메라 ID를 확인하여 전면/후면 카메라 구분
        val cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val isFrontCamera = try {
            val characteristics = cameraManager.getCameraCharacteristics(args.cameraId)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        } catch (e: Exception) {
            false // 오류 시 후면 카메라로 간주
        }
        
        if (isFrontCamera) {
            // 전면 카메라인 경우 CustomFrontPreviewFragment로 복귀
            val action = GalleryFragmentDirections.actionGalleryFragmentToCustomFrontPreviewFragment(
                args.cameraId,
                args.width,
                args.height,
                args.fps,
                args.dynamicRange,
                args.colorSpace,
                args.previewStabilization,
                args.useMediaRecorder,
                args.videoCodec,
                args.filterOn,
                args.transfer,
                args.useHardware,
                args.pipelineMode
            ).apply {
                // forcePhysicalId는 nullable이므로 setter로 설정
                args.forcePhysicalId?.let { setForcePhysicalId(it) }
            }
            findNavController().navigate(action)
        } else {
            // 후면 카메라인 경우 CustomPreviewFragment로 복귀
            val action = GalleryFragmentDirections.actionGalleryFragmentToCustomPreviewFragment(
                args.cameraId,
                args.width,
                args.height,
                args.fps,
                args.dynamicRange,
                args.colorSpace,
                args.previewStabilization,
                args.useMediaRecorder,
                args.videoCodec,
                args.filterOn,
                args.transfer,
                args.useHardware,
                args.pipelineMode
            ).apply {
                // forcePhysicalId는 nullable이므로 setter로 설정
                args.forcePhysicalId?.let { setForcePhysicalId(it) }
            }
            findNavController().navigate(action)
        }
    }
}
