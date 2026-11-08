package com.bitchat.android.hotspot

import android.content.Context
import java.io.File

/**
 * The part of [ApkWebServer] that [HotspotViewModel] drives. It exists so the
 * ViewModel can be tested without binding a socket.
 */
interface ApkWebServing {
    fun startServer()
    fun stopServer()
}

/** Makes the server for one hotspot session; the port stays [ApkWebServer.DEFAULT_PORT]. */
fun interface ApkWebServingFactory {
    fun create(apkFile: File): ApkWebServing
}

/** Delegates every call to a real [ApkWebServer]. */
internal class ApkWebServerServing(private val server: ApkWebServer) : ApkWebServing {
    override fun startServer() = server.startServer()
    override fun stopServer() = server.stopServer()
}

internal fun apkWebServerServingFactory(context: Context) = ApkWebServingFactory { apkFile ->
    ApkWebServerServing(ApkWebServer(context, apkFile))
}
