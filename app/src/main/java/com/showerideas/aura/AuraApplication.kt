package com.showerideas.aura

import android.app.Application
import com.showerideas.aura.BuildConfig
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class AuraApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.ENABLE_LOGGING) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("AURA Application starting — v${BuildConfig.VERSION_NAME}")
    }
}
