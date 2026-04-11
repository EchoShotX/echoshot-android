package com.echoshot.app

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.opencv.android.OpenCVLoader

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // OpenCV AAR/모듈을 추가했다면 이걸로 충분
        check(OpenCVLoader.initDebug()) { "OpenCV init failed" }

        // 앱 시작 시 로그인 상태 확인 및 SSE 연결
        // 배포 모드(서버 기능 비활성화)가 아닐 때만 연결
        val isDeployment = com.echoshot.app.utils.DeploymentModeManager.isDeploymentMode()
        android.util.Log.d("MyApp", "🚀 App Started - DeploymentMode: $isDeployment")
        
        if (!isDeployment) {
            val tokenManager = com.echoshot.app.auth.TokenManager(this)
            val isLoggedIn = tokenManager.isLoggedIn()
            android.util.Log.d("MyApp", "🔑 Login Status: $isLoggedIn")
            
            if (isLoggedIn) {
                android.util.Log.d("MyApp", "📡 Starting SSE connection...")
                com.echoshot.app.repository.NotificationRepository.connect(tokenManager)
            } else {
                android.util.Log.d("MyApp", "⚠️ Not logged in - SSE skipped")
            }
        } else {
            android.util.Log.d("MyApp", "⚠️ Deployment mode - SSE disabled")
        }
    }
}
