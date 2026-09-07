package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** UI tests never initialize transports, identities, location services or a real conversation store. */
class ChatUiTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
