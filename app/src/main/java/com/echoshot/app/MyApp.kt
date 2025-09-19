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
    }
}
