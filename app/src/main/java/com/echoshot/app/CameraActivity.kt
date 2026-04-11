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

package com.echoshot.app

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.echoshot.app.databinding.ActivityCameraBinding
import com.echoshot.app.repository.NotificationRepository
import kotlinx.coroutines.launch

class CameraActivity : AppCompatActivity() {

    private lateinit var activityCameraBinding: ActivityCameraBinding
    private val TAG = "CameraActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityCameraBinding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(activityCameraBinding.root)
        
        // 전역 알림 배지 관리
        observeNotifications()
    }
    
    /**
     * 알림 이벤트를 전역으로 감지하여 하단 바의 배지를 업데이트합니다.
     * 모든 Fragment에서 배지가 보이도록 Activity 레벨에서 처리합니다.
     */
    private fun observeNotifications() {
        // 앱 시작 시 이미 읽지 않은 알림이 있는지 확인
        if (NotificationRepository.hasUnreadNotification) {
            updateBadgeVisibility(true)
        }
        
        // 실시간 알림 이벤트 수신
        lifecycleScope.launch {
            NotificationRepository.notificationFlow.collect { data ->
                Log.d(TAG, "🔔 CameraActivity에서 알림 이벤트 수신: $data")
                runOnUiThread {
                    updateBadgeVisibility(true)
                }
            }
        }
    }
    
    /**
     * 하단 바의 프로필 배지 가시성을 업데이트합니다.
     */
    private fun updateBadgeVisibility(visible: Boolean) {
        val badge = findViewById<View>(R.id.nav_profile_badge)
        if (badge != null) {
            badge.visibility = if (visible) View.VISIBLE else View.GONE
            Log.d(TAG, "✅ 하단 바 배지 visibility 업데이트: ${if (visible) "VISIBLE" else "GONE"}")
        }
    }
    
    /**
     * 외부에서 배지를 숨기고 싶을 때 호출 (예: 알림 읽음 처리 후)
     */
    fun hideBadge() {
        updateBadgeVisibility(false)
        NotificationRepository.clearUnreadFlag()
    }

    override fun onResume() {
        super.onResume()
        // Before setting full screen flags, we must wait a bit to let UI settle; otherwise, we may
        // be trying to set app to immersive mode before it's ready and the flags do not stick
        activityCameraBinding.fragmentContainer.postDelayed({
            activityCameraBinding.fragmentContainer.systemUiVisibility = FLAGS_FULLSCREEN
        }, IMMERSIVE_FLAG_TIMEOUT)
        
        // onResume 시에도 배지 상태 확인
        if (NotificationRepository.hasUnreadNotification) {
            updateBadgeVisibility(true)
        }
    }

    companion object {
        /** Combination of all flags required to put activity into immersive mode */
        const val FLAGS_FULLSCREEN =
                View.SYSTEM_UI_FLAG_LOW_PROFILE or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or  // 시스템 네비게이션 바 숨기기
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or  // 레이아웃이 네비게이션 바 영역까지 확장
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        /** Milliseconds used for UI animations */
        const val ANIMATION_FAST_MILLIS = 50L
        const val ANIMATION_SLOW_MILLIS = 100L
        private const val IMMERSIVE_FLAG_TIMEOUT = 500L
    }
}

