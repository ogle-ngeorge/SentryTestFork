package com.example.sentrytestapp

import android.app.Application
import io.sentry.android.core.SentryAndroid

class SentryTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        SentryAndroid.init(this) { options ->
            options.dsn = "http://add45c761cf0fe12864419efb306b9cd@localhost:9000/2"
            options.isDebug = true

            // ANR timeout interval
            options.tracesSampleRate = 1.0
            options.anrTimeoutIntervalMillis = 2000

            // session replays
            options.sessionReplay.onErrorSampleRate = 1.0
            options.sessionReplay.sessionSampleRate = 1.0

            // profiling
            options.profilesSampleRate = 1.0
        }
    }
}
