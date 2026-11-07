package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** UI fixtures do not start transports, identity persistence, or location services. */
class PerformanceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(cl, Application::class.java.name, context)
}
