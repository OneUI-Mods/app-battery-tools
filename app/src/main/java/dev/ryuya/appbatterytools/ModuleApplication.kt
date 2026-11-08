package dev.ryuya.appbatterytools

import android.app.Application

class ModuleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FrameworkServiceConnection.register(this)
    }
}
