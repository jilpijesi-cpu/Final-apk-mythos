package com.build.mythos

import android.app.Application

/** Memasang pencatat crash sedini mungkin, sebelum Activity mana pun dibuat. */
class MythosApp : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base.withLockedDisplay())
    }

    override fun onCreate() {
        super.onCreate()
        resources.enforceLockedDisplayMetrics()
        CrashReporter.install(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(lockDisplayConfiguration(newConfig))
        resources.enforceLockedDisplayMetrics()
    }
}
