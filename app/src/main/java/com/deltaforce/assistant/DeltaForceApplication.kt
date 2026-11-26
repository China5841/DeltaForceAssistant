package com.deltaforce.assistant

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DeltaForceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 初始化原生库
        System.loadLibrary("memory")
        System.loadLibrary("ue4parser")
    }
}