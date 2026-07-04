package com.echoshot.app.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceProfiles
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.echoshot.app.CameraActivity
import com.echoshot.app.R
import com.echoshot.app.databinding.FragmentHomeBinding
import com.echoshot.app.utils.AutoStartCameraManager
import com.echoshot.app.utils.DeploymentModeManager
import com.echoshot.app.utils.MediaScanUtils
import com.echoshot.app.utils.setupBottomNavigationBar
import kotlinx.coroutines.launch
import kotlin.math.abs

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 언어 선택 스피너 설정
        setupLanguageSpinner()

        // 네비게이션 바 설정
        setupBottomNavigationBar(
            currentPage = "home",
            onGalleryClick = {
                // 홈에서 갤러리로 이동
                navigateToGallery()
            },
            onCameraClick = {
                // 홈에서 카메라로 이동
                navigateToCamera()
            },
            onArchiveClick = {
                val action = HomeFragmentDirections.actionHomeFragmentToFancamEditFragment()
                findNavController().navigate(action)
            },
            onProfileClick = {
                // 배포모드일 때는 프로필로 이동하지 않음
                if (DeploymentModeManager.isDeploymentMode()) {
                    return@setupBottomNavigationBar
                }
                // 홈에서 프로필로 이동
                val action = HomeFragmentDirections.actionHomeFragmentToProfileFragment()
                findNavController().navigate(action)
            }
        )

        // 촬영하기 박스 클릭
        binding.shootBox.setOnClickListener {
            navigateToCamera()
        }

        // 앱 시작 시 카메라 자동 실행 토글 설정
        setupAutoStartCameraToggle()
        
        // 빠른 사용 가이드 박스 클릭
        binding.guideBox.setOnClickListener {
            GuideDialogFragment.newInstance()
                .show(parentFragmentManager, "guideDialog")
        }
        
        // 앱 첫 실행 시 또는 저장된 미디어 파일 스캔 (백그라운드에서 비동기 처리)
        // 저장공간 권한이 있는 경우에만 스캔 실행
        if (MediaScanUtils.hasStoragePermission(requireContext())) {
            lifecycleScope.launch {
                MediaScanUtils.scanEchoShotMedia(requireContext())
            }
        } else {
            // 권한이 없으면 로그만 남기고 스캔하지 않음
            // 사용자가 나중에 권한을 허용하면 다음 실행 시 자동으로 스캔됨
            android.util.Log.d("HomeFragment", "저장공간 권한이 없어 미디어 스캔을 건너뜁니다")
        }
        
        // 로그인 후 프로필로 이동하라는 요청이 있는지 확인
        val navigateTo = arguments?.getString("navigateTo")
        if (navigateTo == "profile" && !DeploymentModeManager.isDeploymentMode()) {
            // 배포모드가 아닐 때만 프로필로 이동
            view.post {
                val action = HomeFragmentDirections.actionHomeFragmentToProfileFragment()
                findNavController().navigate(action)
            }
        }
        arguments?.remove("navigateTo")

        
        // 실시간 알림 수신 대기
        // 1. 이미 있는 알림 확인 (Fragment 시작 시)
        if (com.echoshot.app.repository.NotificationRepository.hasUnreadNotification) {
            view.findViewById<View>(R.id.nav_profile_badge)?.visibility = View.VISIBLE
            android.util.Log.d("HomeFragment", "🔔 Found existing unread notification on start")
        }
        
        // 2. 실시간 알림 감지
        lifecycleScope.launch {
            com.echoshot.app.repository.NotificationRepository.notificationFlow
                .collect { data ->
                    android.util.Log.d("HomeFragment", "✨ SSE Event Received in HomeFragment: $data")
                    // UI 업데이트는 메인 스레드에서
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        view.findViewById<View>(R.id.nav_profile_badge)?.visibility = View.VISIBLE
                        android.util.Log.d("HomeFragment", "✅ Badge visibility set to VISIBLE")
                    }
                }
        }
    }

    override fun onResume() {
        super.onResume()
        // Fragment가 다시 resume될 때 토글 색상 재설정
        updateToggleColor()
    }

    private fun setupAutoStartCameraToggle() {
        val switch = binding.autoStartSwitch
        val isEnabled = AutoStartCameraManager.isAutoStartEnabled(requireContext())
        
        // Switch 스타일 설정 (초록색)
        setupSwitchColors(switch)
        
        // 초기 상태 설정 (리스너 설정 전에)
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = isEnabled
        
        // 토글 변경 리스너
        switch.setOnCheckedChangeListener { _, isChecked ->
            AutoStartCameraManager.setAutoStartEnabled(requireContext(), isChecked)
        }
    }
    
    private fun setupSwitchColors(switch: Switch) {
        // Switch 스타일 설정 (초록색)
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val colors = intArrayOf(
            android.graphics.Color.parseColor("#90EE90"), // 켜져있을 때 초록색
            android.graphics.Color.parseColor("#CCCCCC")  // 꺼져있을 때 회색
        )
        val trackColorStateList = android.content.res.ColorStateList(states, colors)
        switch.trackTintList = trackColorStateList
        switch.trackTintMode = android.graphics.PorterDuff.Mode.SRC_OVER
        switch.thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
    }
    
    private fun updateToggleColor() {
        val switch = binding.autoStartSwitch
        val isEnabled = AutoStartCameraManager.isAutoStartEnabled(requireContext())
        
        // 상태에 맞게 체크 상태 설정 (리스너가 호출되지 않도록 임시로 제거)
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = isEnabled
        
        // Switch 색상 재설정
        setupSwitchColors(switch)
        
        // 색상이 제대로 적용되도록 강제로 refresh
        switch.jumpDrawablesToCurrentState()
        switch.refreshDrawableState()
        
        // 리스너 다시 설정
        switch.setOnCheckedChangeListener { _, isChecked ->
            AutoStartCameraManager.setAutoStartEnabled(requireContext(), isChecked)
        }
    }
    
    private fun setupLanguageSpinner() {
        val languages = resources.getStringArray(R.array.languages)
        val languageCodes = resources.getStringArray(R.array.language_codes)

        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_language_item,
            R.id.spinnerText,
            languages
        ).apply {
            setDropDownViewResource(R.layout.spinner_language_dropdown)
        }

        binding.spinnerLanguage.adapter = adapter

        // 현재 언어 설정에 맞춰 선택
        val currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val currentIndex = when {
            currentLocale.startsWith("en") -> 0
            currentLocale.startsWith("ko") -> 1
            currentLocale.startsWith("id") -> 2
            currentLocale.startsWith("vi") -> 3
            else -> 0 // 기본값: English
        }
        binding.spinnerLanguage.setSelection(currentIndex)

        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedCode = languageCodes[position]
                val currentCode = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                
                // 현재 언어와 다를 때만 변경
                if (!currentCode.startsWith(selectedCode)) {
                    // 미리 context 참조 저장
                    val appContext = context?.applicationContext ?: return
                    
                    // 언어 설정 적용
                    val localeList = LocaleListCompat.forLanguageTags(selectedCode)
                    AppCompatDelegate.setApplicationLocales(localeList)
                    
                    // 언어 변경 후 즉시 앱을 재시작 (애니메이션 방지)
                    val intent = Intent(appContext, CameraActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        // 언어 변경으로 인한 재시작임을 표시 - 카메라 자동 시작 무시
                        putExtra("SKIP_AUTO_START_CAMERA", true)
                    }
                    appContext.startActivity(intent)
                    
                    // 프로세스 완전 종료 후 재시작
                    Runtime.getRuntime().exit(0)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            ) == true

        val action = HomeFragmentDirections.actionHomeFragmentToCustomPreviewFragment(
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

    @SuppressLint("MissingPermission")
    private fun navigateToGallery() {
        val context = requireContext()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val selectedCameraId = cameraManager.cameraIdList.firstOrNull() ?: return
        val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)

        val targetClass = MediaRecorder::class.java
        val configMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: run {
            navigateToGalleryWithDefaults(selectedCameraId)
            return
        }

        val allSizes = configMap.getOutputSizes(targetClass)
        val selectedSize = chooseBestVideoSize(allSizes)
        val selectedFps = getSafeFps(configMap, targetClass, selectedSize)

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

        val stabilizationModes = characteristics.get(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        )
        val supportsPreviewStabilization =
            stabilizationModes?.contains(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            ) == true

        val action = HomeFragmentDirections.actionHomeFragmentToGalleryFragment(
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
        ).apply {
            startBasic = false  // 확장 갤러리 탭 기본 활성화
        }
        findNavController().navigate(action)
    }

    private fun navigateToCameraWithDefaults(cameraId: String) {
        val action = HomeFragmentDirections.actionHomeFragmentToCustomPreviewFragment(
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

    private fun navigateToGalleryWithDefaults(cameraId: String) {
        val action = HomeFragmentDirections.actionHomeFragmentToGalleryFragment(
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
        ).apply {
            startBasic = false
        }
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

