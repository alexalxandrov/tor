package com.bridgepipe

import android.app.Application
import com.bridgepipe.util.AppLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("App", "BridgePipe запущен")
    }
}
