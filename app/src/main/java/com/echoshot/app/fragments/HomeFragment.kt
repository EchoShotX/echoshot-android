package com.echoshot.app.fragments

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.echoshot.app.R
import com.echoshot.app.databinding.FragmentHomeBinding
import com.echoshot.app.utils.setupBottomNavigationBar
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
            }
        )

        // 촬영하기 박스 클릭
        binding.shootBox.setOnClickListener {
            navigateToCamera()
        }

        // 빠른 사용 가이드 박스 클릭
        binding.guideBox.setOnClickListener {
            GuideDialogFragment.newInstance()
                .show(parentFragmentManager, "guideDialog")
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
            else -> 0 // 기본값: English
        }
        binding.spinnerLanguage.setSelection(currentIndex)

        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedCode = languageCodes[position]
                val currentCode = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                
                // 현재 언어와 다를 때만 변경
                if (!currentCode.startsWith(selectedCode)) {
                    val localeList = LocaleListCompat.forLanguageTags(selectedCode)
                    AppCompatDelegate.setApplicationLocales(localeList)
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
            "hybrid" // pipelineMode
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
            "hybrid" // pipelineMode
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
            "hybrid"
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
            "hybrid"
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

