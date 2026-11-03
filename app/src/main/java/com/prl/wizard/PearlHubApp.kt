package com.prl.wizard

import android.app.Application

class PearlHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Push.init(this)
        Notifications.ensureChannels(this)
    }
}
