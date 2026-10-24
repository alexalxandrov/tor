package com.bridgepipe

import android.app.Application
import com.bridgepipe.util.AppLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        // Страховка: любое необработанное исключение в любом потоке сначала
        // попадает в лог-файл (чтобы крах не был "молчаливым"), затем
        // передаётся системному обработчику — диалог "приложение остановлено" остаётся.
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                AppLog.e("CRASH", "необработанное исключение в потоке ${t.name}:\n${e.stackTraceToString()}")
            } catch (_: Exception) {
            }
            prevHandler?.uncaughtException(t, e)
        }
        AppLog.i("App", "BridgePipe запущен")
    }
}
