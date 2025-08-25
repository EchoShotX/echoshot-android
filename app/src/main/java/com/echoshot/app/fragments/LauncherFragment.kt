package com.echoshot.app.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.ColorSpaceProfiles
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.echoshot.app.R

class LauncherFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 화면 레이아웃은 그대로 써도 되고, 빈 레이아웃으로 대체해도 됩니다.
        return inflater.inflate(R.layout.fragment_launcher, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 앱 시작하자마자 바로 카메라 설정 후 CustomPreview로 이동
        launchWithAutoConfig()
    }

    @SuppressLint("MissingPermission")
    private fun launchWithAutoConfig() {
        val context = requireContext()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val selectedCameraId = cameraManager.cameraIdList.first()
        val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)

        val targetClass = MediaRecorder::class.java
        val configMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return

        val allSizes = configMap.getOutputSizes(targetClass)
        val selectedSize = allSizes.firstOrNull { it.width == 3840 && it.height == 2160 }
            ?: Size(4080, 3060) // fallback
        val secondsPerFrame = configMap.getOutputMinFrameDuration(targetClass, selectedSize) / 1_000_000_000.0
        val selectedFps = if (secondsPerFrame > 0) (1.0 / secondsPerFrame).toInt() else 30

        val dynamicRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            if (capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT) == true) {
                val profiles = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                profiles?.getSupportedProfiles()?.firstOrNull() ?: DynamicRangeProfiles.STANDARD
            } else {
                DynamicRangeProfiles.STANDARD
            }
        } else {
            DynamicRangeProfiles.STANDARD
        }

        val colorSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val profiles = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
            profiles?.getSupportedColorSpacesForDynamicRange(android.graphics.ImageFormat.UNKNOWN, dynamicRange)?.firstOrNull()?.ordinal ?: ColorSpaceProfiles.UNSPECIFIED
        } else {
            ColorSpaceProfiles.UNSPECIFIED
        }

        val stabilizationModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val supportsPreviewStabilization = stabilizationModes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) == true

        val action = LauncherFragmentDirections.actionLauncherToCustomPreview(
            selectedCameraId,                 // String
            selectedSize.width,              // Int
            selectedSize.height,             // Int
            selectedFps,                     // Int
            dynamicRange,                    // Long
            colorSpace,                      // Int
            supportsPreviewStabilization,   // Boolean
            false,                           // useMediaRecorder: Boolean
            0,                               // videoCodec: Int
            false,                           // filterOn: Boolean
             0,                                // ✅ transfer: Int (예: 기본값 0)
            true,
             "hybrid",

        )
        findNavController().navigate(action)
    }
}
