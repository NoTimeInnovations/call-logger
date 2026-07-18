package com.mydream.calllogger

import android.app.Application
import com.mydream.calllogger.prefs.SettingsManager
import com.mydream.calllogger.work.CallSync

/**
 * Application entry point. The database is a lazy singleton
 * (see [com.mydream.calllogger.data.AppDatabase.getInstance]). On start we also
 * register the periodic upload safety net so captured calls reach the backend
 * even if a one-shot upload was missed — unless the master switch is OFF.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (SettingsManager(this).active) CallSync.schedulePeriodic(this)
    }
}
