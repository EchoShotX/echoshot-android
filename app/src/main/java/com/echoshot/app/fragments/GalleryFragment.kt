package com.echoshot.app.fragments

import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.echoshot.app.R
import com.echoshot.app.databinding.FragmentGalleryBinding
import com.echoshot.app.databinding.GalleryItemBinding
import com.echoshot.app.databinding.ItemDateHeaderBinding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.echoshot.app.mp4detact.GlCtx
import com.echoshot.app.utils.DeploymentModeManager
import com.echoshot.app.utils.FancamHistoryManager
import com.echoshot.app.utils.setupBottomNavigationBar


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
    
    // 선택 모드 관련 변수
    private var isSelectionMode = false
    private val selectedItems = mutableSetOf<Uri>()
    
    // 삭제 권한 요청 관련
    private var pendingDeleteUris: List<Uri> = emptyList()
    private lateinit var deletePermissionLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>
    
    // 저장공간 권한 요청 관련
    private lateinit var storagePermissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?) : View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        glCtx = GlCtx()
        
        // 저장공간 권한 요청 Launcher 초기화
        storagePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                // 권한이 승인되었으면 갤러리 새로고침 (초기 모드 유지)
                val startBasic = args.startBasic
                reloadForMode(startBasic)
            } else {
                // 권한이 거부되었으면 안내 메시지 표시
                Toast.makeText(
                    requireContext(),
                    "저장공간 권한이 필요합니다. 갤러리 기능이 제한될 수 있습니다.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        // 삭제 권한 요청 Launcher 초기화
        deletePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                // 권한이 승인되었으면 삭제 재시도
                deleteUrisWithPermission(pendingDeleteUris)
            } else {
                // 권한이 거부되었으면 메시지 표시
                Toast.makeText(
                    requireContext(),
                    getString(R.string.delete_permission_denied),
                    Toast.LENGTH_SHORT
                ).show()
                exitSelectionMode()
            }
        }
        
        // 갤러리 진입 시 저장공간 권한 확인 및 요청
        checkAndRequestStoragePermission()

        binding.backIcon.setOnClickListener { 
            // ✅ 방법 1: 명시적으로 CustomPreviewFragment로 네비게이션하여 모든 파라미터 전달
            navigateBackToCustomPreview()
        }
        
        // 내장 갤러리 버튼 클릭
        binding.systemGalleryButton.setOnClickListener {
            openSystemGallery()
        }
        
        // 시스템 백 버튼도 처리
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isSelectionMode) {
                        exitSelectionMode()
                    } else {
                        navigateBackToCustomPreview()
                    }
                }
            }
        )

        // 네비게이션 바 설정
        setupBottomNavigationBar(
            currentPage = "gallery",
            onHomeClick = {
                // 갤러리에서 홈으로 이동
                val action = GalleryFragmentDirections.actionGalleryFragmentToHomeFragment()
                findNavController().navigate(action)
            },
            onArchiveClick = {
                val action = GalleryFragmentDirections.actionGalleryFragmentToFancamEditFragment()
                findNavController().navigate(action)
            },
            onProfileClick = {
                // 배포모드일 때는 프로필로 이동하지 않음
                if (DeploymentModeManager.isDeploymentMode()) {
                    return@setupBottomNavigationBar
                }
                // 갤러리에서 프로필로 이동
                val action = GalleryFragmentDirections.actionGalleryFragmentToProfileFragment()
                findNavController().navigate(action)
            },
            onCameraClick = {
                // 갤러리에서 카메라로 이동 (항상 후면 카메라로 이동)
                val cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val backCameraId = getBackCameraId(cameraManager) ?: args.cameraId
                
                val action = GalleryFragmentDirections.actionGalleryFragmentToCustomPreviewFragment(
                    backCameraId,
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
                    // 후면 카메라로 이동할 때는 forcePhysicalId를 null로 설정 (논리 카메라 사용)
                    setForcePhysicalId(null)
                }
                findNavController().navigate(action)
            }
        )

        // ✅ SwipeRefreshLayout 설정 (위로 당기면 새로고침)
        binding.swipeRefreshLayout.setOnRefreshListener {
            // 현재 모드에 맞춰 새로고침
            reloadForMode(null)
        }
        // 새로고침 색상 설정 (선택사항)
        binding.swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_red_light
        )

        // 1) 초기 모드: 네비게이션 인자대로
        val startBasic = args.startBasic
        mode = if (startBasic) GalleryMode.BASIC else GalleryMode.EXTENDED
        setActiveTab(isBasic = startBasic)   // 버튼 색/테두리 갱신
        // 리스트 로딩은 checkAndRequestStoragePermission에서 권한 확인 후 수행

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
                exitSelectionMode()
                mode = GalleryMode.BASIC
                setActiveTab(isBasic = true)
                reloadForMode(true)
            }
        }
        binding.btnExtendedGallery.setOnClickListener {
            if (mode != GalleryMode.EXTENDED) {
                exitSelectionMode()
                mode = GalleryMode.EXTENDED
                setActiveTab(isBasic = false)
                reloadForMode(false)
            }
        }
        
        // 4) 삭제 버튼 클릭
        binding.btnDelete.setOnClickListener {
            deleteSelectedItems()
        }
    }
    
    /**
     * 선택 모드 진입
     */
    private fun enterSelectionMode() {
        isSelectionMode = true
        binding.deleteButtonContainer.visibility = View.VISIBLE
        binding.bottomTabs.visibility = View.GONE
        // backBar UI 업데이트
        binding.systemGalleryButton.visibility = View.GONE
        binding.selectedCountText.visibility = View.VISIBLE
        updateDeleteButton()
        updateSelectedCount()
        // 어댑터의 선택 모드 상태를 즉시 업데이트하여 동그라미와 체크 표시
        (binding.galleryRecyclerView.adapter as? SectionedAdapter)?.updateSelectionMode(
            isSelectionMode, 
            selectedItems
        )
    }
    
    /**
     * 선택 모드 종료
     */
    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedItems.clear()
        binding.deleteButtonContainer.visibility = View.GONE
        binding.bottomTabs.visibility = View.VISIBLE
        // backBar UI 업데이트
        binding.systemGalleryButton.visibility = View.VISIBLE
        binding.selectedCountText.visibility = View.GONE
        reloadForMode(null) // 어댑터 갱신
    }
    
    /**
     * 항목 선택/해제 토글
     */
    private fun toggleSelection(uri: Uri) {
        if (selectedItems.contains(uri)) {
            selectedItems.remove(uri)
        } else {
            selectedItems.add(uri)
        }
        updateDeleteButton() // updateDeleteButton 내부에서 updateSelectedCount 호출
        // 어댑터에 선택 상태 변경 알림 (헤더의 선택 개수도 업데이트되도록)
        (binding.galleryRecyclerView.adapter as? SectionedAdapter)?.updateSelectedItems(selectedItems)
    }
    
    /**
     * 삭제 버튼 상태 업데이트
     */
    private fun updateDeleteButton() {
        binding.btnDelete.isClickable = selectedItems.isNotEmpty()
        binding.btnDelete.isEnabled = selectedItems.isNotEmpty()
        binding.btnDelete.alpha = if (selectedItems.isNotEmpty()) 1.0f else 0.5f
        updateSelectedCount()
    }
    
    /**
     * 선택 개수 표시 업데이트
     */
    private fun updateSelectedCount() {
        if (isSelectionMode) {
            val count = selectedItems.size
            binding.selectedCountText.text = if (count > 0) {
                getString(R.string.selected_count, count)
            } else {
                ""
            }
        }
    }
    
    /**
     * 저장공간 권한 확인 및 요청 (갤러리 진입 시)
     */
    private fun checkAndRequestStoragePermission() {
        val permissions = mutableListOf<String>()
        
        when {
            // Android 13+ (API 33+)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            // Android 10-12 (API 29-32)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            // Android 9 이하
            else -> {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        // 권한이 이미 있으면 바로 로드
        val needsPermission = permissions.any { permission ->
            ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (needsPermission) {
            // 권한 요청
            storagePermissionLauncher.launch(permissions.toTypedArray())
        } else {
            // 권한이 이미 있으면 바로 로드 (초기 모드 유지)
            val startBasic = args.startBasic
            reloadForMode(startBasic)
        }
    }
    
    /**
     * 내장 갤러리 앱 열기
     */
    private fun openSystemGallery() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.cannot_open_gallery),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    /**
     * 선택된 항목 삭제
     */
    private fun deleteSelectedItems() {
        if (selectedItems.isEmpty()) return
        
        val count = selectedItems.size
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete))
            .setMessage(
                if (count == 1) {
                    getString(R.string.delete_single_item)
                } else {
                    getString(R.string.delete_multiple_items, count)
                }
            )
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                performDelete()
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }
    
    /**
     * 실제 삭제 수행
     */
    private fun performDelete() {
        pendingDeleteUris = selectedItems.toList()
        deleteUrisWithPermission(pendingDeleteUris)
    }
    
    /**
     * 권한 요청을 포함한 삭제 수행
     */
    private fun deleteUrisWithPermission(uris: List<Uri>) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val needPermissionUris = mutableListOf<Uri>()
            val deletedCount = uris.count { uri ->
                try {
                    val deleted = requireContext().contentResolver.delete(uri, null, null)
                    if (deleted > 0) {
                        // 파일도 삭제 시도
                        deleteFileIfExists(uri)
                        true
                    } else {
                        false
                    }
                } catch (e: RecoverableSecurityException) {
                    // 권한이 필요한 경우
                    needPermissionUris.add(uri)
                    false
                } catch (e: Exception) {
                    Log.e(TAG, "삭제 실패: $uri", e)
                    false
                }
            }
            
            withContext(Dispatchers.Main) {
                // 권한이 필요한 항목이 있으면 권한 요청
                if (needPermissionUris.isNotEmpty()) {
                    requestDeletePermission(needPermissionUris)
                } else {
                    // 모든 삭제 완료
                    if (deletedCount > 0) {
                        Toast.makeText(
                            requireContext(),
                            if (deletedCount == 1) {
                                getString(R.string.item_deleted)
                            } else {
                                getString(R.string.items_deleted, deletedCount)
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    exitSelectionMode()
                    reloadForMode(null) // 갤러리 새로고침
                }
            }
        }
    }
    
    /**
     * 삭제 권한 요청
     */
    private fun requestDeletePermission(uris: List<Uri>) {
        try {
            val firstUri = uris.first()
            val exception = try {
                requireContext().contentResolver.delete(firstUri, null, null)
                null
            } catch (e: RecoverableSecurityException) {
                e
            } catch (e: Exception) {
                null
            }
            
            if (exception != null) {
                pendingDeleteUris = uris
                val intentSender = exception.userAction.actionIntent?.intentSender
                if (intentSender != null) {
                    val request = IntentSenderRequest.Builder(intentSender).build()
                    deletePermissionLauncher.launch(request)
                } else {
                    Log.e(TAG, "IntentSender를 가져올 수 없습니다.")
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.delete_permission_denied),
                        Toast.LENGTH_SHORT
                    ).show()
                    exitSelectionMode()
                }
            } else {
                // 권한 요청이 필요 없으면 다시 시도
                deleteUrisWithPermission(uris)
            }
        } catch (e: Exception) {
            Log.e(TAG, "권한 요청 실패", e)
            Toast.makeText(
                requireContext(),
                getString(R.string.delete_permission_required),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    /**
     * 파일 삭제 (파일 경로가 있는 경우)
     */
    private fun deleteFileIfExists(uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 이상에서는 MediaStore를 통해서만 삭제 가능
                return
            }
            
            // Android 9 이하에서는 파일 경로로 직접 삭제 시도
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            requireContext().contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val filePath = cursor.getString(columnIndex)
                    if (!filePath.isNullOrEmpty()) {
                        val file = File(filePath)
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "파일 삭제 실패: $uri", e)
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
                    viewLifecycleOwner,  // ✅ LifecycleOwner 전달
                    isSelectionMode = isSelectionMode,
                    selectedItems = selectedItems.toMutableSet(),
                    onItemClick = { v ->
                        if (isSelectionMode) {
                            // 선택 모드: 선택/해제
                            toggleSelection(v.uri)
                        } else {
                            // 일반 모드: BASIC: 바로 플레이어 / EXTENDED: 기존 동작 유지
                            if (mode == GalleryMode.EXTENDED && v.type == "cropped") {
                                CroppedPagerDialogFragment
                                    .newInstance(v.uuid)
                                    .show(childFragmentManager, "croppedPager")
                            } else {
                                com.echoshot.app.AnalyticsTracker.log(requireContext(), "gallery_video_play", "gallery_mode" to mode.name.lowercase())
                                findNavController().navigate(
                                    GalleryFragmentDirections
                                        .actionGalleryFragmentToPreviewPlayerFragment(v.uri.toString())
                                )
                            }
                        }
                    },
                    onItemLongClick = { v ->
                        // 꾹 누르면 선택 모드 진입 (BASIC 모드에서만)
                        if (!isSelectionMode && mode == GalleryMode.BASIC) {
                            enterSelectionMode()
                            toggleSelection(v.uri)
                        }
                        true
                    },
                    onLockedClick = { v ->
                        com.echoshot.app.AnalyticsTracker.log(requireContext(), "gallery_locked_click", "gallery_mode" to mode.name.lowercase())
                        if (mode == GalleryMode.EXTENDED) showLockedOverlay(v.uri)
                        // BASIC에선 잠금 개념 없음
                    }
                )
                
                // ✅ 새로고침 완료 후 로딩 인디케이터 숨기기
                binding.swipeRefreshLayout.isRefreshing = false
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
        
        // Android 10+에서는 RELATIVE_PATH 사용, Q 미만에서는 DATA LIKE 사용
        val isAndroidQPlus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        
        // 동영상 쿼리 projection (Q 미만에서는 DATA 컬럼 추가)
        val videoProj = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DURATION
        ).apply {
            if (!isAndroidQPlus) {
                add(MediaStore.Video.Media.DATA)
            }
        }.toTypedArray()
        
        val (videoSel, videoSelArgs) = if (isAndroidQPlus) {
            // Android 10+: RELATIVE_PATH 사용 (슬래시 유무와 관계없이 매칭)
            Pair(
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("DCIM/EchoShot%")
            )
        } else {
            // Android 9 이하: DATA LIKE 사용
            Pair(
                "${MediaStore.Video.Media.DATA} LIKE ?",
                arrayOf("%/DCIM/EchoShot/%")
            )
        }
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
        
        // 사진 쿼리 projection (Q 미만에서는 DATA 컬럼 추가)
        val imageProj = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN
        ).apply {
            if (!isAndroidQPlus) {
                add(MediaStore.Images.Media.DATA)
            }
        }.toTypedArray()
        
        val (imageSel, imageSelArgs) = if (isAndroidQPlus) {
            // Android 10+: RELATIVE_PATH 사용 (슬래시 유무와 관계없이 매칭)
            Pair(
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("DCIM/EchoShot%")
            )
        } else {
            // Android 9 이하: DATA LIKE 사용
            Pair(
                "${MediaStore.Images.Media.DATA} LIKE ?",
                arrayOf("%/DCIM/EchoShot/%")
            )
        }
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
        
        // Android 10+에서는 RELATIVE_PATH 사용, Q 미만에서는 DATA LIKE 사용
        val isAndroidQPlus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        
        // projection (Q 미만에서는 DATA 컬럼 추가)
        val proj = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DURATION
        ).apply {
            if (!isAndroidQPlus) {
                add(MediaStore.Video.Media.DATA)
            }
        }.toTypedArray()
        
        // 전면(파일명 시작이 VID_front_)을 제외하고 EchoShot 폴더만 쿼리
        val (sel, selArgs) = if (isAndroidQPlus) {
            // Android 10+: RELATIVE_PATH 사용 (슬래시 유무와 관계없이 매칭)
            Pair(
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} NOT LIKE ?",
                arrayOf("DCIM/EchoShot%", "VID_front_%")
            )
        } else {
            // Android 9 이하: DATA LIKE 사용
            Pair(
                "${MediaStore.Video.Media.DATA} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} NOT LIKE ?",
                arrayOf("%/DCIM/EchoShot/%", "VID_front_%")
            )
        }

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
                val type = parts[2] // zoomed | original | cropped | add
                // "add" 타입은 FancamEdit 전용 영상 → 확장 갤러리에 포함하지 않음
                if (type !in setOf("zoomed", "original", "cropped")) continue
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
                mi.dateTaken >= todayStart     -> getString(R.string.today)
                mi.dateTaken >= yesterdayStart -> getString(R.string.yesterday)
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
            Toast.makeText(requireContext(), getString(R.string.cannot_get_filename), Toast.LENGTH_SHORT).show()
            return
        }
        val parts = fileName.split('_')
        if (parts.size < 2) {
            Toast.makeText(requireContext(), "${getString(R.string.invalid_filename)}: $fileName", Toast.LENGTH_SHORT).show()
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
            com.echoshot.app.AnalyticsTracker.log(requireContext(), "composition_type_select", "composition_type" to "auto")
            dialog.dismiss()
            // 🔽 새 독립 다이얼로그 프래그먼트 호출
            MakeAutoDetactionFragment
                .newInstance(uri)
                .show(childFragmentManager, "autoDetect")
        }

        btnSot.setOnClickListener {
            com.echoshot.app.AnalyticsTracker.log(requireContext(), "composition_type_select", "composition_type" to "person_select")
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
        private val lifecycleOwner: LifecycleOwner,
        private var isSelectionMode: Boolean = false,
        private var selectedItems: MutableSet<Uri> = mutableSetOf(),
        private val onItemClick: (ListItem.Video) -> Unit,
        private val onItemLongClick: ((ListItem.Video) -> Boolean)? = null,
        private val onLockedClick: (ListItem.Video) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        
        /**
         * 선택 모드 상태 업데이트
         */
        fun updateSelectionMode(isSelectionMode: Boolean, selectedItems: Set<Uri>) {
            this.isSelectionMode = isSelectionMode
            this.selectedItems.clear()
            this.selectedItems.addAll(selectedItems)
            notifyDataSetChanged()
        }
        
        /**
         * 선택된 항목 업데이트
         */
        fun updateSelectedItems(selectedItems: Set<Uri>) {
            this.selectedItems.clear()
            this.selectedItems.addAll(selectedItems)
            notifyDataSetChanged()
        }

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
                // ✅ 비디오인 경우 MediaMetadataRetriever로 명시적으로 1초 시점의 프레임 추출
                // 이렇게 하면 두 동영상(zoomed/original)이 같은 시점의 미리보기를 표시함
                if (v.durationMs > 0) {
                    // 비디오: 1초 시점의 프레임을 명시적으로 추출 (두 동영상 동기화)
                    if (lifecycleOwner is Fragment) {
                        (lifecycleOwner as Fragment).viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            val thumb = try {
                                val retriever = MediaMetadataRetriever()
                                retriever.setDataSource(binding.root.context, v.uri)
                                // 1초(1,000,000 마이크로초) 시점의 키프레임 추출
                                val bmp = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                retriever.release()
                                bmp
                            } catch (e: Exception) {
                                Log.e(TAG, "썸네일 생성 실패: ${v.uri}", e)
                                null
                            }
                            
                            withContext(Dispatchers.Main) {
                                if (thumb != null) {
                                    binding.thumbnailImageView.setImageBitmap(thumb)
                                } else {
                                    // 실패 시 Glide로 폴백
                                    Glide.with(binding.root).load(v.uri).centerCrop().into(binding.thumbnailImageView)
                                }
                            }
                        }
                    } else {
                        // Fragment가 아닌 경우 Glide 사용
                        Glide.with(binding.root).load(v.uri).centerCrop().into(binding.thumbnailImageView)
                    }
                    
                    val m = TimeUnit.MILLISECONDS.toMinutes(v.durationMs)
                    val s = TimeUnit.MILLISECONDS.toSeconds(v.durationMs) % 60
                    binding.tvPlayDuration.text = String.format("%02d:%02d", m, s)
                    binding.tvPlayDuration.visibility = View.VISIBLE
                } else {
                    // 사진: Glide 사용
                    Glide.with(binding.root).load(v.uri).centerCrop().into(binding.thumbnailImageView)
                    binding.tvPlayDuration.visibility = View.GONE
                }

                // ★ 2개 이상 cropped면 배지 보여주기 (cropped 칸만)
                binding.badgeMulti.visibility =
                    if (v.type == "cropped" && v.croppedCount > 1) View.VISIBLE else View.GONE

                // 선택 모드 표시
                val isSelected = selectedItems.contains(v.uri)
                if (isSelectionMode) {
                    // 선택 모드일 때 모든 항목에 동그라미 표시
                    binding.selectionCheckCircle.visibility = View.VISIBLE
                    // 선택된 항목은 체크 채워진 아이콘 표시
                    binding.selectionCheckFilled.visibility = if (isSelected) View.VISIBLE else View.GONE
                } else {
                    binding.selectionCheckCircle.visibility = View.GONE
                    binding.selectionCheckFilled.visibility = View.GONE
                }

                // 클릭 리스너 설정
                if (v.locked && !isSelectionMode) {
                    binding.dimOverlay.visibility = View.VISIBLE
                    binding.lockOverlayImageView.visibility = View.VISIBLE
                    binding.root.setOnClickListener { onLockedClick(v) }
                    binding.root.setOnLongClickListener(null)
                } else {
                    // 선택 모드가 아닐 때는 dimOverlay 숨김 (잠금이 아닌 경우)
                    binding.dimOverlay.visibility = View.GONE
                    binding.lockOverlayImageView.visibility = View.GONE
                    binding.root.setOnClickListener { onItemClick(v) }
                    binding.root.setOnLongClickListener {
                        onItemLongClick?.invoke(v) ?: false
                    }
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
                is ListItem.Header -> {
                    val headerBinding = (holder as HeaderVH).binding
                    headerBinding.headerText.text = it.label
                    
                    // 선택 모드일 때 전체 선택된 개수 표시 (첫 번째 헤더에만)
                    if (isSelectionMode && selectedItems.isNotEmpty() && position == 0) {
                        // 첫 번째 헤더에만 전체 선택 개수 표시
                        val count = selectedItems.size
                        headerBinding.selectedCountText.text = 
                            headerBinding.root.context.getString(R.string.selected_count, count)
                        headerBinding.selectedCountText.visibility = View.VISIBLE
                    } else if (isSelectionMode && selectedItems.isNotEmpty()) {
                        // 다른 헤더에는 현재 섹션의 선택 개수 표시
                        var count = 0
                        for (i in (position + 1) until items.size) {
                            val item = items.getOrNull(i)
                            if (item is ListItem.Video && selectedItems.contains(item.uri)) {
                                count++
                            } else if (item is ListItem.Header) {
                                // 다음 헤더를 만나면 중단
                                break
                            }
                        }
                        
                        if (count > 0) {
                            headerBinding.selectedCountText.text = 
                                headerBinding.root.context.getString(R.string.selected_count, count)
                            headerBinding.selectedCountText.visibility = View.VISIBLE
                        } else {
                            headerBinding.selectedCountText.visibility = View.GONE
                        }
                    } else {
                        headerBinding.selectedCountText.visibility = View.GONE
                    }
                }
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
                        viewLifecycleOwner,  // ✅ LifecycleOwner 전달
                        isSelectionMode = isSelectionMode,
                        selectedItems = selectedItems.toMutableSet(),
                        onItemClick = { v ->
                            if (isSelectionMode) {
                                toggleSelection(v.uri)
                            } else {
                                when (v.type) {
                                    "cropped" -> {
                                        CroppedPagerDialogFragment
                                            .newInstance(v.uuid)
                                            .show(childFragmentManager, "croppedPager")
                                    }
                                    else -> {
                                        com.echoshot.app.AnalyticsTracker.log(requireContext(), "gallery_video_play", "gallery_mode" to mode.name.lowercase())
                                        findNavController().navigate(
                                            GalleryFragmentDirections
                                                .actionGalleryFragmentToPreviewPlayerFragment(v.uri.toString())
                                        )
                                    }
                                }
                            }
                        },
                        onItemLongClick = { v ->
                            // 꾹 누르면 선택 모드 진입 (BASIC 모드에서만)
                            if (!isSelectionMode && mode == GalleryMode.BASIC) {
                                enterSelectionMode()
                                toggleSelection(v.uri)
                            }
                            true
                        },
                        onLockedClick = { v ->
                            com.echoshot.app.AnalyticsTracker.log(requireContext(), "gallery_locked_click", "gallery_mode" to mode.name.lowercase())
                            showLockedOverlay(v.uri)
                        }
                    )
                }
                // ✅ 새로고침 완료 후 로딩 인디케이터 숨기기
                binding.swipeRefreshLayout.isRefreshing = false
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
    
    /**
     * 후면 카메라 ID를 찾는 헬퍼 함수
     */
    private fun getBackCameraId(cameraManager: CameraManager): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            try {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                lensFacing == CameraCharacteristics.LENS_FACING_BACK
            } catch (e: Exception) {
                false
            }
        }
    }
}
