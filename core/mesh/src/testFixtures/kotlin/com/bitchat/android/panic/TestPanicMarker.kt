package com.bitchat.android.panic

import java.io.File
import java.nio.file.Files

/** A marker backed by a fresh temporary directory, so a JVM test needs no Android context. Not pending. */
fun testPanicMarker(): PanicMarker =
    PanicMarker(File(Files.createTempDirectory("panic-marker").toFile().apply { deleteOnExit() }, PanicMarker.NAME))
