/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.echoshot.app.fragments

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import com.echoshot.app.R
import com.echoshot.app.utils.AutoStartCameraManager

private const val PERMISSIONS_REQUEST_CODE = 10

/**
 * Android 버전에 따라 필요한 권한 목록을 반환
 */
private fun getRequiredPermissions(): Array<String> {
    val permissions = mutableListOf<String>()
    
    // 카메라와 오디오 권한은 항상 필요
    permissions.add(Manifest.permission.CAMERA)
    permissions.add(Manifest.permission.RECORD_AUDIO)
    
    // 저장공간 권한은 갤러리/복구 진입 시에만 요청 (정책 리스크 최소화)
    
    return permissions.toTypedArray()
}

/**
 * This [Fragment] requests permissions and, once granted, it will navigate to the next fragment
 */
class PermissionsFragment : Fragment() {
    
    // 설정 화면으로 이동했는지 추적
    private var wentToSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requiredPermissions = getRequiredPermissions()
        
        if (hasPermissions(requireContext(), requiredPermissions)) {
            // 권한이 이미 허용되어 있으면 자동 시작 설정에 따라 이동
            navigateAfterPermissions()
        } else {
            // Request all required permissions (camera, audio, storage)
            requestPermissions(requiredPermissions, PERMISSIONS_REQUEST_CODE)
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // 설정에서 돌아왔을 때 권한 다시 체크
        if (wentToSettings) {
            wentToSettings = false
            val requiredPermissions = getRequiredPermissions()
            if (hasPermissions(requireContext(), requiredPermissions)) {
                // 권한이 허용되었으면 다음 화면으로 이동
                navigateAfterPermissions()
            } else {
                // 여전히 권한이 없으면 다시 다이얼로그 표시
                showCameraPermissionDeniedDialog()
            }
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            // 권한 결과 배열이 비어있거나 길이가 맞지 않으면 안전하게 처리
            if (grantResults.isEmpty() || permissions.size != grantResults.size) {
                // 결과가 없거나 불완전한 경우, 현재 권한 상태를 다시 확인
                val requiredPermissions = getRequiredPermissions()
                if (hasPermissions(requireContext(), requiredPermissions)) {
                    // 권한이 이미 허용되어 있으면 자동 시작 설정에 따라 이동
                    navigateAfterPermissions()
                }
                return
            }
            
            // 카메라와 오디오 권한을 인덱스로 직접 확인
            val cameraIndex = permissions.indexOf(Manifest.permission.CAMERA)
            val audioIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            
            val cameraGranted = cameraIndex >= 0 && 
                    grantResults.getOrNull(cameraIndex) == PackageManager.PERMISSION_GRANTED
            val audioGranted = audioIndex >= 0 && 
                    grantResults.getOrNull(audioIndex) == PackageManager.PERMISSION_GRANTED
            
            // 카메라 권한이 없으면 설정으로 안내
            if (!cameraGranted) {
                showCameraPermissionDeniedDialog()
                return
            }
            
            // 오디오 권한 상태를 SharedPreferences에 저장 (녹화 시 사용)
            val prefs = requireContext().getSharedPreferences("echoshot_permissions", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("audio_permission_granted", audioGranted)
                .apply()
            
            // 카메라 권한이 있으면 자동 시작 설정에 따라 이동 (오디오는 선택적)
            navigateAfterPermissions()
        }
    }
    
    /**
     * 권한 승인 후 자동 시작 설정에 따라 적절한 화면으로 이동
     */
    private fun navigateAfterPermissions() {
        val navController = Navigation.findNavController(requireActivity(), R.id.fragment_container)
        
        // 언어 변경으로 인한 재시작인 경우 카메라 자동 시작 무시하고 홈으로 이동
        val skipAutoStart = activity?.intent?.getBooleanExtra("SKIP_AUTO_START_CAMERA", false) ?: false
        
        // 로그인 후 프로필 이동 요청 확인
        val navigateTo = activity?.intent?.getStringExtra("navigateTo")
        if (navigateTo == "profile") {
            // 홈으로 이동하면서 Bundle 전달
            val bundle = Bundle().apply { 
                putString("navigateTo", "profile") 
            }
            navController.navigate(R.id.action_permissions_to_home, bundle)
            return
        }

        // 자동 시작 설정이 켜져있고, 언어 변경 재시작이 아닌 경우에만 카메라로 이동
        if (AutoStartCameraManager.isAutoStartEnabled(requireContext()) && !skipAutoStart) {
            // ✅ 먼저 HomeFragment로 이동 (백스택에 Home이 남도록)
            navController.navigate(PermissionsFragmentDirections.actionPermissionsToHome())
            // ✅ 그 다음 카메라로 이동 (popBackStack 시 Home으로 돌아갈 수 있음)
            launchFromHome(navController)
        } else {
            // 자동 시작이 꺼져있거나 언어 변경 재시작인 경우 HomeFragment로 이동
            navController.navigate(PermissionsFragmentDirections.actionPermissionsToHome())
        }
    }
    
    /**
     * HomeFragment 기준으로 카메라 설정 후 CustomPreviewFragment로 이동
     * (백스택: Home → CustomPreview 유지)
     */
    @SuppressLint("MissingPermission")
    private fun launchFromHome(navController: androidx.navigation.NavController) {
        val context = requireContext()
        val cameraManager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

        val selectedCameraId = cameraManager.cameraIdList.firstOrNull() ?: return
        val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)

        val targetClass = android.media.MediaRecorder::class.java
        val configMap = characteristics.get(
            android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: run {
            navigateFromHomeWithDefaults(selectedCameraId, navController)
            return
        }

        val allSizes = configMap.getOutputSizes(targetClass)
        val selectedSize = chooseBestVideoSize(allSizes)
        val selectedFps = getSafeFps(configMap, targetClass, selectedSize)

        val dynamicRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val capabilities = characteristics.get(
                android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )
            val has10bit = capabilities?.contains(
                android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
            ) == true
            if (has10bit) {
                characteristics.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                    ?.supportedProfiles?.firstOrNull()
                    ?: android.hardware.camera2.params.DynamicRangeProfiles.STANDARD
            } else {
                android.hardware.camera2.params.DynamicRangeProfiles.STANDARD
            }
        } else {
            android.hardware.camera2.params.DynamicRangeProfiles.STANDARD
        }

        val colorSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val profiles = characteristics.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
            val supported = try {
                profiles?.getSupportedColorSpacesForDynamicRange(android.graphics.ImageFormat.PRIVATE, dynamicRange)
            } catch (_: IllegalArgumentException) { null }
            supported?.firstOrNull()?.ordinal ?: android.hardware.camera2.params.ColorSpaceProfiles.UNSPECIFIED
        } else {
            android.hardware.camera2.params.ColorSpaceProfiles.UNSPECIFIED
        }

        val stabilizationModes = characteristics.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val supportsPreviewStabilization = stabilizationModes?.contains(
            android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
        ) == true

        // ✅ HomeFragmentDirections 사용 (백스택에 Home 유지)
        val action = HomeFragmentDirections.actionHomeFragmentToCustomPreviewFragment(
            selectedCameraId,
            selectedSize.width,
            selectedSize.height,
            selectedFps,
            dynamicRange,
            colorSpace,
            supportsPreviewStabilization,
            false, 0, false, 0, true, "hybrid"
        )
        navController.navigate(action)
    }
    
    private fun navigateFromHomeWithDefaults(cameraId: String, navController: androidx.navigation.NavController) {
        val action = HomeFragmentDirections.actionHomeFragmentToCustomPreviewFragment(
            cameraId, 1920, 1080, 30,
            android.hardware.camera2.params.DynamicRangeProfiles.STANDARD,
            android.hardware.camera2.params.ColorSpaceProfiles.UNSPECIFIED,
            false, false, 0, false, 0, true, "hybrid"
        )
        navController.navigate(action)
    }
    
    /**
     * LauncherFragment와 동일한 로직으로 카메라 설정 후 CustomPreviewFragment로 이동
     */
    @SuppressLint("MissingPermission")
    private fun launchWithAutoConfig(navController: androidx.navigation.NavController) {
        val context = requireContext()
        val cameraManager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

        val selectedCameraId = cameraManager.cameraIdList.firstOrNull() ?: return
        val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)

        val targetClass = android.media.MediaRecorder::class.java
        val configMap = characteristics.get(
            android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: run {
            navigateWithDefaults(selectedCameraId, navController)
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
                android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )
            val has10bit =
                capabilities?.contains(
                    android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
                ) == true

            if (has10bit) {
                val profiles = characteristics.get(
                    android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
                )
                profiles?.supportedProfiles?.firstOrNull()
                    ?: android.hardware.camera2.params.DynamicRangeProfiles.STANDARD
            } else {
                android.hardware.camera2.params.DynamicRangeProfiles.STANDARD
            }
        } else {
            android.hardware.camera2.params.DynamicRangeProfiles.STANDARD
        }

        // ColorSpace 설정
        val colorSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val profiles = characteristics.get(
                android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES
            )
            val supported = try {
                profiles?.getSupportedColorSpacesForDynamicRange(
                    android.graphics.ImageFormat.PRIVATE,
                    dynamicRange
                )
            } catch (_: IllegalArgumentException) {
                null
            }
            supported?.firstOrNull()?.ordinal ?: android.hardware.camera2.params.ColorSpaceProfiles.UNSPECIFIED
        } else {
            android.hardware.camera2.params.ColorSpaceProfiles.UNSPECIFIED
        }

        // Preview Stabilization 설정
        val stabilizationModes = characteristics.get(
            android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        )
        val supportsPreviewStabilization =
            stabilizationModes?.contains(
                android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            ) == true

        val action = PermissionsFragmentDirections.actionPermissionsToCustomPreview(
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
        navController.navigate(action)
    }

    /** 기기가 지원하는 해상도 중에서 "가장 무난한" 것 선택 */
    private fun chooseBestVideoSize(sizes: Array<android.util.Size>?): android.util.Size {
        if (sizes == null || sizes.isEmpty()) {
            return android.util.Size(1920, 1080)
        }

        val targetRatio = 16f / 9f

        // 1) 16:9 비율 중에서 가장 큰 해상도
        val candidates = sizes.filter { s ->
            val ratio = s.width.toFloat() / s.height
            kotlin.math.abs(ratio - targetRatio) < 0.01f
        }.sortedByDescending { it.width * it.height }

        return candidates.firstOrNull()
            ?: sizes.maxByOrNull { it.width * it.height }!!
    }

    /** getOutputMinFrameDuration이 지원 안 돼도 죽지 않게 FPS 계산 */
    private fun getSafeFps(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        targetClass: Class<*>,
        size: android.util.Size
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

    /** configMap도 못 가져올 때 완전 기본값으로 넘기기 */
    private fun navigateWithDefaults(cameraId: String, navController: androidx.navigation.NavController) {
        val action = PermissionsFragmentDirections.actionPermissionsToCustomPreview(
            cameraId,
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
            "hybrid"
        )
        navController.navigate(action)
    }
    
    /**
     * 카메라 권한이 거부되었을 때 설정으로 안내하는 다이얼로그 표시
     */
    private fun showCameraPermissionDeniedDialog() {
        val ctx = context ?: return
        
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.permission_camera_required_title)
            .setMessage(R.string.permission_camera_required_message)
            .setPositiveButton(R.string.permission_go_to_settings) { _, _ ->
                try {
                    wentToSettings = true  // 설정으로 이동 플래그 설정
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", ctx.packageName, null)
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    wentToSettings = false
                    Toast.makeText(ctx, R.string.permission_cannot_open_settings, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.permission_exit_app) { _, _ ->
                requireActivity().finish()
            }
            .setCancelable(false)
            .show()
    }

    companion object {

        /** Convenience method used to check if all permissions required by this app are granted */
        fun hasPermissions(context: Context, permissions: Array<String>? = null): Boolean {
            val requiredPermissions = permissions ?: getRequiredPermissions()
            return requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
}
