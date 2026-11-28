package com.bitchat.android.ui

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import org.junit.runner.JUnitCore

/** Minimal runner avoids instrumentation-only tracing dependencies removed by target R8. */
class ReleaseScannerTestRunner : Instrumentation() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = JUnitCore.runClasses(BarcodeScannerInstrumentedTest::class.java)
        val report = Bundle().apply {
            putString("stream", buildString {
                append("Tests run: ${result.runCount}, failures: ${result.failureCount}\n")
                result.failures.forEach { append(it.trace).append('\n') }
            })
        }
        finish(if (result.wasSuccessful()) -1 else 0, report)
    }
}
