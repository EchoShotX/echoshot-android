package com.echoshot.app.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.ColorSpaceProfiles
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.echoshot.app.R
import com.echoshot.app.auth.AuthRepository
import com.echoshot.app.auth.TokenManager
import com.echoshot.app.databinding.FragmentProfileBinding
import com.echoshot.app.network.RetrofitClient
import com.echoshot.app.network.UserApiService
import com.echoshot.app.repository.NotificationRepository
import com.echoshot.app.utils.setupBottomNavigationBar
import com.echoshot.app.CameraActivity
import kotlinx.coroutines.launch
import kotlin.math.abs

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var authRepository: AuthRepository
    private lateinit var tokenManager: TokenManager
    
    companion object {
        private const val TAG = "ProfileFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Auth 관련 초기화
        // Custom Tab을 열기 위해 Activity context 사용
        val activityContext = requireActivity()
        authRepository = AuthRepository(activityContext)
        tokenManager = TokenManager(activityContext)

        // 네비게이션 바 설정
        setupBottomNavigationBar(
            currentPage = "profile",
            onHomeClick = {
                // 프로필에서 홈으로 이동
                val action = ProfileFragmentDirections.actionProfileFragmentToHomeFragment()
                findNavController().navigate(action)
            },
            onGalleryClick = {
                // 프로필에서 갤러리로 이동
                navigateToGallery()
            },
            onCameraClick = {
                // 프로필에서 카메라로 이동
                navigateToCamera()
            },
            onArchiveClick = {
                // 프로필에서 업로드 페이지로 이동
                val action = ProfileFragmentDirections.actionProfileFragmentToFancamEditFragment()
                findNavController().navigate(action)
            },
            onProfileClick = {
                // 프로필 페이지에서는 아무 동작 없음
            }
        )

        // 알림 벨 버튼 클릭 로직
        binding.notificationBell.setOnClickListener {
             showNotifications()
        }

        // 구글 로그인 버튼 클릭 로직
        binding.btnGoogleLogin.setOnClickListener {
            authRepository.startGoogleLogin()
        }

        // 로그아웃 버튼 클릭 로직
        binding.btnLogout.setOnClickListener {
            handleLogout()
        }
        
        // 초기 UI 상태 업데이트
        updateUI()
        
        // 🔍 SSE 연결 상태 확인 로그
        Log.d(TAG, "========== SSE 체인 연결 상태 확인 ==========")
        Log.d(TAG, "🔗 eventSource 존재: ${NotificationRepository.isConnected()}")
        Log.d(TAG, "🔗 실제 연결됨 (onOpen 호출됨): ${NotificationRepository.isConnectionEstablished()}")
        Log.d(TAG, "🔔 hasUnreadNotification: ${NotificationRepository.hasUnreadNotification}")
        Log.d(TAG, "📡 Flow 구독 시작...")
        
        // Flow 구독 시작
        lifecycleScope.launch {
            NotificationRepository.notificationFlow.collect { data ->
                Log.d(TAG, "✨ ProfileFragment에서 SSE 이벤트 수신: $data")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    view?.findViewById<View>(R.id.nav_profile_badge)?.visibility = View.VISIBLE
                    view?.findViewById<View>(R.id.notificationBadge)?.visibility = View.VISIBLE
                    Log.d(TAG, "✅ 배지 표시 완료!")
                }
            }
        }
        Log.d(TAG, "=============================================")
    }
    
    /**
     * 로그인 상태에 따라 UI를 업데이트합니다.
     */
    private fun updateUI() {
        val isLoggedIn = tokenManager.isLoggedIn()
        val accessToken = tokenManager.getAccessToken()
        val refreshToken = tokenManager.getRefreshToken()
        
        Log.d(TAG, "UI 업데이트 - 로그인 상태: $isLoggedIn")
        if (isLoggedIn) {
            Log.d(TAG, "AccessToken 존재: ${accessToken != null}, 길이: ${accessToken?.length ?: 0}")
            Log.d(TAG, "RefreshToken 존재: ${refreshToken != null}, 길이: ${refreshToken?.length ?: 0}")
            if (accessToken != null) {
                Log.d(TAG, "AccessToken 앞 20자: ${accessToken.take(20)}...")
            }
            
            // 로그인된 경우 사용자 정보 조회
            fetchUserProfile()
            binding.notificationBell.visibility = View.VISIBLE
        } else {
            // 로그인되지 않은 경우
            binding.notificationBell.visibility = View.GONE
            binding.btnGoogleLogin.visibility = View.VISIBLE
            binding.btnLogout.visibility = View.GONE
            binding.userName.text = "비회원"
            binding.userEmail.text = ""
            binding.userCredit.visibility = View.GONE
            binding.userJoinedAt.visibility = View.GONE
        }
    }
    
    /**
     * 사용자 프로필 정보를 조회합니다.
     */
    private fun fetchUserProfile() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "=== 사용자 정보 조회 시작 ===")
                
                val authenticatedRetrofit = RetrofitClient.createAuthenticatedClient(tokenManager)
                val userApi = authenticatedRetrofit.create(UserApiService::class.java)
                
                val endpoint = "/member/me"
                val fullUrl = "${RetrofitClient.BASE_URL}$endpoint"
                
                Log.d(TAG, "=== API 요청 정보 ===")
                Log.d(TAG, "URL: $fullUrl")
                Log.d(TAG, "Method: GET")
                Log.d(TAG, "Endpoint: $endpoint")
                Log.d(TAG, "Authorization 헤더: Bearer ${tokenManager.getAccessToken()?.take(20)}...")
                Log.d(TAG, "====================")
                
                val response = userApi.getUserProfile()
                
                Log.d(TAG, "=== 사용자 정보 응답 수신 ===")
                Log.d(TAG, "isSuccess: ${response.isSuccess}, code: ${response.code}, message: ${response.message}")
                
                if (response.isSuccess && response.result != null) {
                    val profile = response.result!!
                    Log.d(TAG, "사용자 정보 조회 성공")
                    Log.d(TAG, "이메일: ${profile.email}")
                    Log.d(TAG, "가입 날짜: ${profile.joinedAt}")
                    Log.d(TAG, "크레딧: ${profile.credit}")
                    
                    // UI 업데이트
                    binding.btnGoogleLogin.visibility = View.GONE
                    binding.btnLogout.visibility = View.VISIBLE
                    
                    // 이메일 표시 (이메일에서 @ 앞부분을 이름으로 사용)
                    val displayName = profile.email.substringBefore("@")
                    binding.userName.text = displayName
                    binding.userEmail.text = profile.email
                    
                    // 크레딧 표시
                    binding.userCredit.text = "크레딧: ${profile.credit}"
                    binding.userCredit.visibility = View.VISIBLE
                    
                    // 가입 날짜 표시 (ISO 8601 형식을 읽기 쉬운 형식으로 변환)
                    val formattedDate = formatJoinedDate(profile.joinedAt)
                    binding.userJoinedAt.text = "가입일: $formattedDate"
                    binding.userJoinedAt.visibility = View.VISIBLE

                    // SSE 연결 시작 (이미 연결되어 있다면 내부적으로 무시됨)
                    NotificationRepository.connect(tokenManager)
                } else {
                    Log.e(TAG, "사용자 정보 조회 실패 - code: ${response.code}, message: ${response.message}")
                    // 에러가 발생해도 로그인 상태는 유지
                    binding.btnGoogleLogin.visibility = View.GONE
                    binding.btnLogout.visibility = View.VISIBLE
                    binding.userName.text = "로그인됨"
                    binding.userEmail.text = ""
                    binding.userCredit.visibility = View.GONE
                    binding.userJoinedAt.visibility = View.GONE
                }
            } catch (e: retrofit2.HttpException) {
                Log.e(TAG, "사용자 정보 조회 중 HTTP 예외 발생: ${e.code()}", e)
                // 502, 401 등 서버 에러 시 로그아웃 처리
                if (e.code() == 502 || e.code() == 401 || e.code() == 403) {
                    Log.w(TAG, "서버 에러(${e.code()})로 인해 로그아웃 처리")
                    forceLogout()
                } else {
                    // 다른 HTTP 에러는 로그인 상태 유지
                    binding.btnGoogleLogin.visibility = View.GONE
                    binding.btnLogout.visibility = View.VISIBLE
                    binding.userName.text = "로그인됨"
                    binding.userEmail.text = ""
                    binding.userCredit.visibility = View.GONE
                    binding.userJoinedAt.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "사용자 정보 조회 중 예외 발생", e)
                // 일반 에러는 로그인 상태 유지
                binding.btnGoogleLogin.visibility = View.GONE
                binding.btnLogout.visibility = View.VISIBLE
                binding.userName.text = "로그인됨"
                binding.userEmail.text = ""
                binding.userCredit.visibility = View.GONE
                binding.userJoinedAt.visibility = View.GONE
            }
        }
    }
    
    /**
     * 강제 로그아웃 처리 (서버 에러 시)
     */
    private fun forceLogout() {
        // SSE 연결 해제
        NotificationRepository.disconnect()
        // 토큰 삭제
        tokenManager.clearTokens()
        // 알림 배지 숨김
        clearAllBadges()
        // UI 업데이트
        updateUI()
        // 토스트 메시지
        android.widget.Toast.makeText(
            requireContext(), 
            "서버 연결에 문제가 발생하여 로그아웃되었습니다.", 
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun handleLogout() {
        lifecycleScope.launch {
            try {
                // 저장된 리프레시 토큰 가져오기
                val refreshToken = tokenManager.getRefreshToken()
                
                if (!refreshToken.isNullOrBlank()) {
                    // 서버에 로그아웃 요청
                    val response = authRepository.logout(refreshToken)
                    if (response.isSuccess) {
                        Log.d(TAG, "서버 로그아웃 성공")
                    } else {
                        Log.e(TAG, "서버 로그아웃 실패: ${response.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "로그아웃 처리 중 오류 발생", e)
            } finally {
                // 서버 응답과 무관하게 로컬 데이터 클리어 및 연결 해제
                NotificationRepository.disconnect()
                tokenManager.clearTokens()
                // 알림 배지 숨김
                clearAllBadges()
                android.widget.Toast.makeText(requireContext(), "로그아웃되었습니다", android.widget.Toast.LENGTH_SHORT).show()
                updateUI()
            }
        }
    }

    /**
     * 알림 목록을 BottomSheet로 표시합니다.
     */
    private fun showNotifications() {
        // 알림 배지 숨김 (벨 클릭 시)
        clearAllBadges()
        
        val bottomSheet = NotificationBottomSheetFragment.newInstance()
        bottomSheet.show(parentFragmentManager, NotificationBottomSheetFragment.TAG)
    }
    
    /**
     * 모든 알림 배지를 숨깁니다 (벨의 느낌표 + 하단 바의 점)
     */
    private fun clearAllBadges() {
        // 읽지 않은 알림 플래그 초기화
        NotificationRepository.clearUnreadFlag()
        
        // 프로필 헤더의 알림 배지 숨김
        binding.notificationBadge.visibility = View.GONE
        
        // 하단 바의 프로필 배지 숨김
        view?.findViewById<View>(R.id.nav_profile_badge)?.visibility = View.GONE
        
        // CameraActivity에도 알려주기
        (activity as? CameraActivity)?.hideBadge()
    }

    // 기존 showNotificationDialog 등 삭제
    
    /**
     * ISO 8601 형식의 날짜 문자열을 읽기 쉬운 형식으로 변환합니다.
     * 예: "2025-12-21T10:30:00" -> "2025년 12월 21일"
     */
    private fun formatJoinedDate(isoDateString: String): String {
        return try {
            // ISO 8601 형식: "2025-12-21T10:30:00" 또는 "2025-12-21T10:30:00.000"
            val datePart = isoDateString.substringBefore("T")
            val parts = datePart.split("-")
            if (parts.size == 3) {
                "${parts[0]}년 ${parts[1].toInt()}월 ${parts[2].toInt()}일"
            } else {
                isoDateString
            }
        } catch (e: Exception) {
            Log.e(TAG, "날짜 형식 변환 실패: $isoDateString", e)
            isoDateString
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 화면이 다시 표시될 때 로그인 상태 확인
        if (::tokenManager.isInitialized) {
            updateUI()
        }
    }

    @SuppressLint("MissingPermission")
    private fun navigateToGallery() {
        val context = requireContext()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val selectedCameraId = cameraManager.cameraIdList.firstOrNull() ?: "0"
        
        // 기본값으로 갤러리로 이동
        val action = ProfileFragmentDirections.actionProfileFragmentToGalleryFragment(
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
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val selectedCameraId = cameraManager.cameraIdList.firstOrNull() ?: return
        val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)

        val targetClass = MediaRecorder::class.java
        val configMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: run {
            navigateToCameraWithDefaults(selectedCameraId)
            return
        }

        // 1) 기기가 실제로 지원하는 해상도 목록
        val allSizes = configMap.getOutputSizes(targetClass)
        val selectedSize = chooseBestVideoSize(allSizes)

        // 2) 안전하게 FPS 계산
        val selectedFps = getSafeFps(configMap, targetClass, selectedSize)

        // DynamicRange 설정
        val dynamicRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val capabilities = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )
            val has10bit =
                capabilities?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
                ) == true

            if (has10bit) {
                val profiles = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
                )
                profiles?.supportedProfiles?.firstOrNull()
                    ?: DynamicRangeProfiles.STANDARD
            } else {
                DynamicRangeProfiles.STANDARD
            }
        } else {
            DynamicRangeProfiles.STANDARD
        }

        // ColorSpace 설정
        val colorSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val profiles = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES
            )
            val supported = try {
                profiles?.getSupportedColorSpacesForDynamicRange(
                    ImageFormat.PRIVATE,
                    dynamicRange
                )
            } catch (_: IllegalArgumentException) {
                null
            }
            supported?.firstOrNull()?.ordinal ?: ColorSpaceProfiles.UNSPECIFIED
        } else {
            ColorSpaceProfiles.UNSPECIFIED
        }

        // Preview Stabilization 설정
        val stabilizationModes = characteristics.get(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        )
        val supportsPreviewStabilization =
            stabilizationModes?.contains(
                android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            ) == true

        val action = ProfileFragmentDirections.actionProfileFragmentToCustomPreviewFragment(
            selectedCameraId,
            selectedSize.width,
            selectedSize.height,
            selectedFps,
            dynamicRange,
            colorSpace,
            supportsPreviewStabilization,
            false,  // useMediaRecorder
            0,      // videoCodec
            false,  // filterOn
            0,      // transfer
            true,   // useHardware
            "default" // pipelineMode
        )
        findNavController().navigate(action)
    }

    private fun navigateToCameraWithDefaults(cameraId: String) {
        val action = ProfileFragmentDirections.actionProfileFragmentToCustomPreviewFragment(
            cameraId,
            1920,
            1080,
            30,
            DynamicRangeProfiles.STANDARD,
            ColorSpaceProfiles.UNSPECIFIED,
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

    /** 기기가 지원하는 해상도 중에서 "가장 무난한" 것 선택 */
    private fun chooseBestVideoSize(sizes: Array<Size>?): Size {
        if (sizes == null || sizes.isEmpty()) {
            return Size(1920, 1080)
        }

        val targetRatio = 16f / 9f

        // 1) 16:9 비율 중에서 가장 큰 해상도
        val candidates = sizes.filter { s ->
            val ratio = s.width.toFloat() / s.height
            abs(ratio - targetRatio) < 0.01f
        }.sortedByDescending { it.width * it.height }

        return candidates.firstOrNull()
            ?: sizes.maxByOrNull { it.width * it.height }!!
    }

    /** getOutputMinFrameDuration이 지원 안 돼도 죽지 않게 FPS 계산 */
    private fun getSafeFps(
        map: StreamConfigurationMap,
        targetClass: Class<*>,
        size: Size
    ): Int {
        return try {
            val durationNs = map.getOutputMinFrameDuration(targetClass, size)
            if (durationNs > 0L) {
                val fps = (1_000_000_000L / durationNs).toInt()
                fps.coerceIn(15, 120)
            } else {
                30
            }
        } catch (_: IllegalArgumentException) {
            30
        } catch (_: Throwable) {
            30
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
