package com.vrec.app

import android.app.Application
import com.vrec.app.update.AppUpdater

class VrecApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppUpdater.init(this, repo = "N2K02/VREC", appName = "VREC")
    }
}
