package com.example.sentrytestapp

import android.app.Application
import io.sentry.android.core.SentryAndroid

class SentryTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        SentryAndroid.init(this) { options ->
            options.dsn = "https://846a1f0b551bcc7341dc0f777214a5a6@o4509374427889664.ingest.us.sentry.io/4509429485928448"
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
