package com.mydream.calllogger

import android.app.Application

/**
 * Application entry point. The database is a lazy singleton
 * (see [com.mydream.calllogger.data.AppDatabase.getInstance]), so this class
 * intentionally stays empty and just anchors the process.
 */
class App : Application()
