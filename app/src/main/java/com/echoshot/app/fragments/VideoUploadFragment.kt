package com.echoshot.app.fragments

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.echoshot.app.R
import com.echoshot.app.auth.TokenManager
import com.echoshot.app.auth.models.VideoInfo
import com.echoshot.app.databinding.FragmentVideoUploadBinding
import com.echoshot.app.network.RetrofitClient
import com.echoshot.app.network.VideoApiService
import com.echoshot.app.utils.setupBottomNavigationBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream

class VideoUploadFragment : Fragment() {

    private var _binding: FragmentVideoUploadBinding? = null
    private val binding get() = _binding!!

    private lateinit var tokenManager: TokenManager
    private lateinit var videoApiService: VideoApiService
    private var uploadHistoryAdapter: UploadHistoryAdapter? = null
    private var pollingJob: Job? = null
    private val uploadHistory = mutableListOf<UploadHistoryItem>()
    
    /**
     * 업로드 히스토리 아이템 (로컬 데이터)
     */
    data class UploadHistoryItem(
        val videoId: Long?,
        val thumbnailUri: Uri?,
        val thumbnailBitmap: Bitmap?,
        val originalWidth: Int,
        val originalHeight: Int,
        val upscaledWidth: Int? = null,
        val upscaledHeight: Int? = null,
        val uploadTime: Long = System.currentTimeMillis(),
        val status: String, // "대기중" | "처리중" | "완료" | "실패"
        val fileName: String? = null
    )

    companion object {
        private const val TAG = "VideoUploadFragment"
        private const val POLLING_INTERVAL_MS = 5000L // 5초
    }

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
        _binding = FragmentVideoUploadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // TokenManager 초기화
        tokenManager = TokenManager(requireActivity())
        
        // VideoApiService 초기화
        val authenticatedRetrofit = RetrofitClient.createAuthenticatedClient(tokenManager)
        videoApiService = authenticatedRetrofit.create(VideoApiService::class.java)

        // 네비게이션 바 설정
        setupBottomNavigationBar(
            currentPage = "upload",
            onHomeClick = {
                val action = VideoUploadFragmentDirections.actionVideoUploadFragmentToHomeFragment()
                findNavController().navigate(action)
            },
            onGalleryClick = {
                navigateToGallery()
            },
            onCameraClick = {
                navigateToCamera()
            },
            onArchiveClick = {
                // 업로드 페이지에서는 아무 동작 없음
            },
            onProfileClick = {
                if (!com.echoshot.app.utils.DeploymentModeManager.isDeploymentMode()) {
                    val action = VideoUploadFragmentDirections.actionVideoUploadFragmentToProfileFragment()
                    findNavController().navigate(action)
                }
            }
        )

        // 업로드 버튼 클릭
        binding.btnUpload.setOnClickListener {
            videoPickerLauncher.launch("video/*")
        }

        // RecyclerView 설정
        uploadHistoryAdapter = UploadHistoryAdapter(uploadHistory)
        binding.uploadHistoryRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.uploadHistoryRecyclerView.adapter = uploadHistoryAdapter
    }

    override fun onResume() {
        super.onResume()
        // 업로드 히스토리 새로고침 (필요시)
    }

    override fun onPause() {
        super.onPause()
        // 폴링 중지
        pollingJob?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollingJob?.cancel()
        _binding = null
    }

    /**
     * 동영상 미리보기 다이얼로그 표시
     */
    private fun showVideoPreviewDialog(videoUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 썸네일과 해상도 가져오기
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(requireContext(), videoUri)
                
                val thumbnail = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                
                retriever.release()
                
                withContext(Dispatchers.Main) {
                    val dialogView = LayoutInflater.from(requireContext())
                        .inflate(R.layout.dialog_video_upload_preview, null)
                    
                    val thumbnailImageView = dialogView.findViewById<ImageView>(R.id.thumbnailImageView)
                    val resolutionText = dialogView.findViewById<TextView>(R.id.resolutionText)
                    val messageText = dialogView.findViewById<TextView>(R.id.messageText)
                    val uploadButton = dialogView.findViewById<ViewGroup>(R.id.uploadButton)
                    val cancelButton = dialogView.findViewById<ViewGroup>(R.id.cancelButton)
                    
                    // 썸네일 설정
                    thumbnail?.let {
                        thumbnailImageView.setImageBitmap(it)
                    }
                    
                    // 해상도 표시
                    resolutionText.text = "해상도: ${width}x${height}"
                    
                    // UHD 이상 체크 (3840x2160 이상)
                    val pixels = width * height
                    val isUHDOrHigher = pixels >= 3840 * 2160
                    
                    if (isUHDOrHigher) {
                        // UHD 이상: 메시지 표시 및 업로드 버튼 비활성화
                        messageText.text = "이미 최대 해상도입니다"
                        messageText.visibility = View.VISIBLE
                        uploadButton.isEnabled = false
                        uploadButton.alpha = 0.5f
                        uploadButton.isClickable = false
                    } else {
                        // UHD 미만: 메시지 숨김 및 업로드 버튼 활성화
                        messageText.visibility = View.GONE
                        uploadButton.isEnabled = true
                        uploadButton.alpha = 1.0f
                        uploadButton.isClickable = true
                    }
                    
                    // 다이얼로그 생성
                    val dialog = AlertDialog.Builder(requireContext())
                        .setView(dialogView)
                        .create()
                        .apply {
                            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                        }
                    
                    // 업로드 버튼 클릭 - 서버에서 FSRCNN으로 업스케일링하므로 현재 해상도로 업로드
                    uploadButton.setOnClickListener {
                        if (!isUHDOrHigher) {
                            uploadVideo(videoUri)
                            dialog.dismiss()
                        }
                    }
                    
                    cancelButton.setOnClickListener {
                        dialog.dismiss()
                    }
                    
                    dialog.show()
                    dialog.window?.apply {
                        val displayMetrics = resources.displayMetrics
                        val screenWidth = displayMetrics.widthPixels
                        val widthPx = (screenWidth * 0.8f).toInt()
                        setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
                        setGravity(Gravity.CENTER)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "동영상 정보 가져오기 실패", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "동영상 정보를 가져올 수 없습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 동영상 업로드 시작
     * 서버에서 FSRCNN으로 업스케일링하므로 현재 해상도로 업로드
     */
    private fun uploadVideo(videoUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 파일 정보 가져오기
                val fileName = getFileName(videoUri) ?: "video.mp4"
                val fileSize = getFileSize(videoUri) ?: 0L

                Log.d(TAG, "업로드 시작: $fileName, 크기: $fileSize bytes")

                // 비디오 메타데이터 추출
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(requireContext(), videoUri)
                
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val durationSeconds = durationMs / 1000.0
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                val frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                val frameRate = frameRateStr?.toIntOrNull() ?: 30 // 기본값 30fps
                
                retriever.release()
                
                // Codec 정보는 MediaExtractor로 가져오기
                var codec = "unknown"
                try {
                    val extractor = android.media.MediaExtractor()
                    extractor.setDataSource(requireContext(), videoUri, null)
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                        if (mime?.startsWith("video/") == true) {
                            // mime에서 codec 추출 (예: "video/avc" -> "avc", "video/hevc" -> "hevc")
                            codec = mime.substringAfter("/") ?: "unknown"
                            break
                        }
                    }
                    extractor.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Codec 정보 가져오기 실패, 기본값 사용", e)
                }

                Log.d(TAG, "비디오 메타데이터: ${width}x${height}, ${durationSeconds}s, codec: $codec, bitrate: $bitrate, fps: $frameRate")

                // 썸네일 추출
                val thumbnail = try {
                    val thumbRetriever = MediaMetadataRetriever()
                    thumbRetriever.setDataSource(requireContext(), videoUri)
                    val thumb = thumbRetriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    thumbRetriever.release()
                    thumb
                } catch (e: Exception) {
                    Log.w(TAG, "썸네일 추출 실패", e)
                    null
                }

                // 업스케일 해상도 계산 (FSRCNN으로 2배 업스케일)
                val upscaledWidth = width * 2
                val upscaledHeight = height * 2

                withContext(Dispatchers.Main) {
                    // 진행 상태 표시
                    binding.uploadProgressContainer.visibility = View.VISIBLE
                    binding.uploadFileName.text = fileName
                    binding.uploadProgressBar.progress = 0
                    binding.uploadProgressText.text = "0%"
                    
                    // 히스토리에 즉시 추가 (대기중 상태)
                    val historyItem = UploadHistoryItem(
                        videoId = null,
                        thumbnailUri = videoUri,
                        thumbnailBitmap = thumbnail,
                        originalWidth = width,
                        originalHeight = height,
                        upscaledWidth = upscaledWidth,
                        upscaledHeight = upscaledHeight,
                        uploadTime = System.currentTimeMillis(),
                        status = "대기중",
                        fileName = fileName
                    )
                    uploadHistory.add(0, historyItem)
                    uploadHistoryAdapter?.notifyItemInserted(0)
                }

                // 1. 업로드 시작 (initiate)
                val initiateRequest = com.echoshot.app.auth.models.VideoUploadInitiateRequest(
                    fileName = fileName,
                    filesSizeBytes = fileSize,
                    contentType = "video/mp4",
                    processingType = "AI_UPSCALING"
                )

                val initiateResponse = videoApiService.initiateUpload(initiateRequest)
                
                if (!initiateResponse.isSuccess || initiateResponse.result == null) {
                    throw Exception("업로드 시작 실패: ${initiateResponse.message}")
                }

                val uploadUrl = initiateResponse.result!!.uploadUrl
                val videoId = initiateResponse.result!!.videoId

                Log.d(TAG, "업로드 URL 받음: $uploadUrl, videoId: $videoId")

                // 히스토리 아이템에 videoId 업데이트 및 상태를 처리중으로 변경
                withContext(Dispatchers.Main) {
                    val itemIndex = uploadHistory.indexOfFirst { it.videoId == null && it.thumbnailUri == videoUri }
                    if (itemIndex >= 0) {
                        val oldItem = uploadHistory[itemIndex]
                        uploadHistory[itemIndex] = oldItem.copy(
                            videoId = videoId,
                            status = "처리중"
                        )
                        uploadHistoryAdapter?.notifyItemChanged(itemIndex)
                    }
                }

                // 2. 파일을 임시 파일로 복사
                val tempFile = File(requireContext().cacheDir, "upload_${System.currentTimeMillis()}.mp4")
                requireContext().contentResolver.openInputStream(videoUri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // 3. 파일 업로드 (S3 등 외부 스토리지에 직접 업로드)
                val requestFile = tempFile.asRequestBody("video/mp4".toMediaType())
                
                val uploadClient = OkHttpClient.Builder()
                    .build()

                val uploadRequest = Request.Builder()
                    .url(uploadUrl)
                    .put(requestFile)
                    .build()

                val uploadResponse = uploadClient.newCall(uploadRequest).execute()

                if (!uploadResponse.isSuccessful) {
                    throw Exception("파일 업로드 실패: ${uploadResponse.code}")
                }

                Log.d(TAG, "파일 업로드 완료")

                // 임시 파일 삭제
                tempFile.delete()

                // 4. 업로드 완료 처리 (메타데이터 포함)
                val completeRequest = com.echoshot.app.auth.models.VideoCompleteUploadRequest(
                    durationSeconds = durationSeconds,
                    width = width,
                    height = height,
                    codec = codec,
                    bitrate = bitrate,
                    frameRate = frameRate
                )

                val completeResponse = videoApiService.completeUpload(videoId, completeRequest)

                if (!completeResponse.isSuccess) {
                    throw Exception("업로드 완료 처리 실패: ${completeResponse.message}")
                }

                Log.d(TAG, "업로드 완료 처리 성공")

                withContext(Dispatchers.Main) {
                    // 진행 상태 숨김
                    binding.uploadProgressContainer.visibility = View.GONE
                    Toast.makeText(requireContext(), "업로드가 완료되었습니다", Toast.LENGTH_SHORT).show()
                }

                // 5. 폴링 시작 (처리 상태 확인)
                startPolling(videoId, width, height)

            } catch (e: Exception) {
                Log.e(TAG, "업로드 실패", e)
                withContext(Dispatchers.Main) {
                    binding.uploadProgressContainer.visibility = View.GONE
                    Toast.makeText(requireContext(), "업로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    
                    // 히스토리 아이템 상태를 실패로 업데이트
                    val itemIndex = uploadHistory.indexOfFirst { it.thumbnailUri == videoUri && it.status == "대기중" || it.status == "처리중" }
                    if (itemIndex >= 0) {
                        val oldItem = uploadHistory[itemIndex]
                        uploadHistory[itemIndex] = oldItem.copy(status = "실패")
                        uploadHistoryAdapter?.notifyItemChanged(itemIndex)
                    }
                }
            }
        }
    }

    /**
     * 폴링으로 영상 처리 상태 확인
     */
    private fun startPolling(videoId: Long, originalWidth: Int, originalHeight: Int) {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (true) {
                delay(POLLING_INTERVAL_MS)
                
                try {
                    val response = videoApiService.getVideoInfo(videoId)
                    
                    if (response.isSuccess && response.result != null) {
                        val videoInfo = response.result!!
                        
                        Log.d(TAG, "영상 상태: ${videoInfo.status}")
                        
                        // 히스토리 아이템 업데이트
                        val existingIndex = uploadHistory.indexOfFirst { it.videoId == videoId }
                        if (existingIndex >= 0) {
                            val oldItem = uploadHistory[existingIndex]
                            val processedWidth = videoInfo.metadata?.width
                            val processedHeight = videoInfo.metadata?.height
                            
                            val newStatus = when (videoInfo.status) {
                                "PENDING_UPLOAD" -> "대기중"
                                "UPLOADING" -> "처리중"
                                "PROCESSING" -> "처리중"
                                "COMPLETED" -> "완료"
                                "FAILED" -> "실패"
                                else -> "처리중"
                            }
                            
                            // 처리 완료된 경우에만 실제 metadata로 업데이트, 그 외에는 기존 예상값 유지
                            val finalUpscaledWidth = if (videoInfo.status == "COMPLETED" && processedWidth != null) {
                                processedWidth
                            } else {
                                oldItem.upscaledWidth // 기존 예상값 유지
                            }
                            
                            val finalUpscaledHeight = if (videoInfo.status == "COMPLETED" && processedHeight != null) {
                                processedHeight
                            } else {
                                oldItem.upscaledHeight // 기존 예상값 유지
                            }
                            
                            uploadHistory[existingIndex] = oldItem.copy(
                                upscaledWidth = finalUpscaledWidth,
                                upscaledHeight = finalUpscaledHeight,
                                status = newStatus
                            )
                            uploadHistoryAdapter?.notifyItemChanged(existingIndex)
                        }
                        
                        // 처리 완료 또는 실패 시 폴링 중지
                        if (videoInfo.status == "COMPLETED" || videoInfo.status == "FAILED") {
                            Log.d(TAG, "처리 완료: ${videoInfo.status}")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "상태 확인 실패", e)
                }
            }
        }
    }

    /**
     * 파일 이름 가져오기
     */
    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.let {
                val cut = it.lastIndexOf('/')
                if (cut != -1) {
                    it.substring(cut + 1)
                } else {
                    it
                }
            }
        }
        return result
    }

    /**
     * 파일 크기 가져오기
     */
    private fun getFileSize(uri: Uri): Long? {
        var result: Long? = null
        if (uri.scheme == "content") {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                    if (sizeIndex >= 0) {
                        result = cursor.getLong(sizeIndex)
                    }
                }
            }
        }
        return result
    }

    @SuppressLint("MissingPermission")
    private fun navigateToGallery() {
        val context = requireContext()
        val cameraManager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val selectedCameraId = cameraManager.cameraIdList.firstOrNull() ?: "0"
        
        val action = VideoUploadFragmentDirections.actionVideoUploadFragmentToGalleryFragment(
            selectedCameraId,
            1920,
            1080,
            30,
            0L,
            0,
            false,
            false,
            0,
            false,
            0,
            true,
            "default"
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
        
        // 기본값으로 카메라로 이동
        val action = VideoUploadFragmentDirections.actionVideoUploadFragmentToCustomPreviewFragment(
            selectedCameraId,
            1920,
            1080,
            30,
            android.hardware.camera2.params.DynamicRangeProfiles.STANDARD,
            android.hardware.camera2.params.ColorSpaceProfiles.UNSPECIFIED,
            false,
            false,
            0,
            false,
            0,
            true,
            "default"
        )
        findNavController().navigate(action)
    }

    /**
     * 업로드 히스토리 어댑터
     */
    private inner class UploadHistoryAdapter(
        private val items: List<UploadHistoryItem>
    ) : RecyclerView.Adapter<UploadHistoryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val thumbnail: ImageView = view.findViewById(R.id.itemThumbnail)
            val title: TextView = view.findViewById(R.id.itemTitle)
            val resolution: TextView = view.findViewById(R.id.itemResolution)
            val status: TextView = view.findViewById(R.id.itemStatus)
            val date: TextView = view.findViewById(R.id.itemDate)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_upload_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            
            // 썸네일 설정
            if (item.thumbnailBitmap != null) {
                holder.thumbnail.setImageBitmap(item.thumbnailBitmap)
            } else if (item.thumbnailUri != null) {
                Glide.with(holder.itemView.context)
                    .load(item.thumbnailUri)
                    .centerCrop()
                    .into(holder.thumbnail)
            }
            
            // 제목 표시
            holder.title.text = "업스케일링"
            
            // 해상도 정보 표시: HD(숫자)->FHD(숫자) 형식
            val originalResName = getResolutionName(item.originalWidth, item.originalHeight)
            val resolutionText = when {
                // 완료된 경우 또는 예상 해상도가 있는 경우
                item.upscaledWidth != null && item.upscaledHeight != null -> {
                    val upscaledResName = getResolutionName(item.upscaledWidth, item.upscaledHeight)
                    "$originalResName(${item.originalWidth}x${item.originalHeight})->$upscaledResName(${item.upscaledWidth}x${item.upscaledHeight})"
                }
                // 해상도 정보가 없고 처리중이 아닌 경우 (실패 등)
                item.status != "처리중" && item.status != "대기중" -> {
                    "$originalResName(${item.originalWidth}x${item.originalHeight})"
                }
                // 그 외 (처리중, 대기중)
                else -> {
                    // 처리중일 때는 예상 해상도 표시 (원본의 2배)
                    val expectedWidth = item.originalWidth * 2
                    val expectedHeight = item.originalHeight * 2
                    val expectedResName = getResolutionName(expectedWidth, expectedHeight)
                    "$originalResName(${item.originalWidth}x${item.originalHeight})->$expectedResName(${expectedWidth}x${expectedHeight})"
                }
            }
            holder.resolution.text = resolutionText
            
            // 상태 표시
            holder.status.text = item.status
            
            // 업로드 시간 표시
            val timeFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            holder.date.text = timeFormat.format(java.util.Date(item.uploadTime))
        }

        override fun getItemCount() = items.size
    }

    /**
     * 해상도를 이름으로 변환 (HD, FHD, UHD 등)
     */
    private fun getResolutionName(width: Int, height: Int): String {
        val pixels = width * height
        return when {
            pixels >= 3840 * 2160 -> "UHD"  // 4K
            pixels >= 1920 * 1080 -> "FHD"  // Full HD
            pixels >= 1280 * 720 -> "HD"    // HD
            else -> "SD"                     // Standard Definition
        }
    }
}
