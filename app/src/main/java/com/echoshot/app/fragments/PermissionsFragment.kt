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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import com.echoshot.app.R

private const val PERMISSIONS_REQUEST_CODE = 10

/**
 * Android 버전에 따라 필요한 권한 목록을 반환
 */
private fun getRequiredPermissions(): Array<String> {
    val permissions = mutableListOf<String>()
    
    // 카메라와 오디오 권한은 항상 필요
    permissions.add(Manifest.permission.CAMERA)
    permissions.add(Manifest.permission.RECORD_AUDIO)
    
    // 저장공간 권한은 Android 버전에 따라 다름
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
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    
    return permissions.toTypedArray()
}

/**
 * This [Fragment] requests permissions and, once granted, it will navigate to the next fragment
 */
class PermissionsFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requiredPermissions = getRequiredPermissions()
        
        if (hasPermissions(requireContext(), requiredPermissions)) {
            // If permissions have already been granted, proceed
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    PermissionsFragmentDirections.actionPermissionsToSelector())
        } else {
            // Request all required permissions (camera, audio, storage)
            requestPermissions(requiredPermissions, PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            // 모든 권한이 허용되었는지 확인
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            
            if (allGranted) {
                // 모든 권한이 허용되면 다음 화면으로 이동
                Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                        PermissionsFragmentDirections.actionPermissionsToSelector())
            } else {
                // 일부 권한이 거부된 경우
                val deniedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }
                
                // 카메라 권한이 거부된 경우에만 경고 표시
                if (deniedPermissions.contains(Manifest.permission.CAMERA)) {
                    Toast.makeText(context, "카메라 권한이 필요합니다", Toast.LENGTH_LONG).show()
                } else {
                    // 저장공간 권한만 거부된 경우 경고 표시
                    Toast.makeText(context, "저장공간 권한이 거부되었습니다. 갤러리 기능이 제한될 수 있습니다.", Toast.LENGTH_LONG).show()
                    // 저장공간 권한 없이도 앱 사용 가능하므로 다음 화면으로 이동
                    Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                            PermissionsFragmentDirections.actionPermissionsToSelector())
                }
            }
        }
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
