package com.campusmesh

import android.app.Application
import com.campusmesh.service.MeshForegroundService
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class CampusMeshApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        try {
            MeshForegroundService.startService(this)
        } catch (e: Exception) {
            Timber.w(e, "Could not auto-start MeshForegroundService from Application.onCreate")
        }
        Timber.i("CampusMesh active — persistent background service and store-and-forward mesh initialized.")
    }
}
