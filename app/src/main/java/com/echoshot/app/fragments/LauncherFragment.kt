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
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.echoshot.app.R
import kotlin.math.abs

class LauncherFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_launcher, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchWithAutoConfig()
    }

    @SuppressLint("MissingPermission")
    private fun launchWithAutoConfig() {
        val context = requireContext()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val selectedCameraId = cameraManager.cameraIdList.firstOrNull() ?: return
        val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)

        val targetClass = MediaRecorder::class.java
        val configMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: run {
            // 최소한 앱이 죽지는 않게 기본 값으로 넘어가기
            navigateWithDefaults(selectedCameraId)
            return
        }

        // 1) 기기가 실제로 지원하는 해상도 목록
        val allSizes = configMap.getOutputSizes(targetClass)
        val selectedSize = chooseBestVideoSize(allSizes)

        // 2) 안전하게 FPS 계산 (지원 안 하면 30fps 기본값)
        val selectedFps = getSafeFps(configMap, targetClass, selectedSize)

        // === 아래는 기존 코드 최대한 유지하면서 null/버전 방어만 추가 ===

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

        val action = LauncherFragmentDirections.actionLauncherToCustomPreview(
            selectedCameraId,                 // String
            selectedSize.width,              // Int
            selectedSize.height,             // Int
            selectedFps,                     // Int
            dynamicRange,                    // Long
            colorSpace,                      // Int
            supportsPreviewStabilization,    // Boolean
            false,                           // useMediaRecorder
            0,                               // videoCodec
            false,                           // filterOn
            0,                               // transfer
            true,
            "hybrid",
        )
        findNavController().navigate(action)
    }

    /** 기기가 지원하는 해상도 중에서 “가장 무난한” 것 선택 */
    private fun chooseBestVideoSize(sizes: Array<Size>?): Size {
        if (sizes == null || sizes.isEmpty()) {
            // 이 경우도 거의 없지만, 혹시를 대비한 기본값
            return Size(1920, 1080)
        }

        val targetRatio = 16f / 9f

        // 1) 16:9 비율 중에서 가장 큰 해상도
        val candidates = sizes.filter { s ->
            val ratio = s.width.toFloat() / s.height
            abs(ratio - targetRatio) < 0.01f
        }.sortedByDescending { it.width * it.height }

        return candidates.firstOrNull()
        // 2) 없으면 그냥 제일 큰 해상도
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
                fps.coerceIn(15, 120) // 말도 안 되는 수치 방지
            } else {
                30
            }
        } catch (_: IllegalArgumentException) {
            30
        } catch (_: Throwable) {
            30
        }
    }

    /** configMap도 못 가져올 때 완전 기본값으로 넘기기 (선택사항) */
    private fun navigateWithDefaults(cameraId: String) {
        val action = LauncherFragmentDirections.actionLauncherToCustomPreview(
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
            "hybrid",
        )
        findNavController().navigate(action)
    }
}
