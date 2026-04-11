package com.echoshot.app.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * 앱 시작 시 카메라 자동 실행 설정을 관리하는 유틸리티 클래스
 */
object AutoStartCameraManager {
    private const val PREFS_NAME = "auto_start_camera_prefs"
    private const val KEY_AUTO_START_CAMERA = "auto_start_camera"
    
    /**
     * 앱 시작 시 카메라 자동 실행 여부를 가져옵니다.
     * @param context Context
     * @return 자동 실행이 활성화되어 있으면 true, 아니면 false (기본값: false)
     */
    fun isAutoStartEnabled(context: Context): Boolean {
        val prefs: SharedPreferences = 
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_START_CAMERA, false)
    }
    
    /**
     * 앱 시작 시 카메라 자동 실행 여부를 설정합니다.
     * @param context Context
     * @param enabled 자동 실행 활성화 여부
     */
    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        val prefs: SharedPreferences = 
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_AUTO_START_CAMERA, enabled)
            .apply()
    }
}

