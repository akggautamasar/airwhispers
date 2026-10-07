package com.airwhispers

import android.app.Application
import com.airwhispers.core.AppLog
import com.airwhispers.service.Notifier

class AirWhispersApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AppContainer.install(container)
        Notifier(this).ensureChannels()
        AppLog.i("App", "started", "version" to BuildConfig.VERSION_NAME, "flavor" to BuildConfig.FLAVOR)
    }
}
