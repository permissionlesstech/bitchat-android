package com.bitchat.android.net

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TorManagerInstrumentedTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Test
    fun installResources_succeeds_and_files_present() {
        val application = app()
        // Attempt installation (with internal repair & retry)
        val ok = TorManager.installResourcesForTest(application)
        assertTrue("Tor resources install failed", ok)

        val dir = File(application.filesDir, "tor_bin")
        val tor = File(dir, "tor")
        val geoip = File(dir, "geoip")
        val geoip6 = File(dir, "geoip6")

        assertTrue("tor dir missing", dir.exists() && dir.isDirectory)
        assertTrue("tor binary missing", tor.exists() && tor.isFile)
        assertTrue("geoip missing", geoip.exists() && geoip.isFile)
        assertTrue("geoip6 missing", geoip6.exists() && geoip6.isFile)
    }

    @Test
    fun torBinary_executes_version() {
        val application = app()
        val dir = File(application.filesDir, "tor_bin")
        if (!dir.exists()) {
            assertTrue("Precondition install failed", TorManager.installResourcesForTest(application))
        }

        val tor = File(dir, "tor")
        val linker = if (android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "/system/bin/linker64" else "/system/bin/linker"
        val pb = ProcessBuilder(listOf(linker, tor.absolutePath, "--version"))
            .directory(dir)
            .redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = application.filesDir.absolutePath
        env["TMPDIR"] = application.cacheDir.absolutePath
        env["LD_LIBRARY_PATH"] = dir.absolutePath

        val proc = pb.start()
        val finished = proc.waitFor(15, TimeUnit.SECONDS)
        assertTrue("tor --version did not finish", finished)
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue("tor --version output missing", out.contains("Tor") || out.contains("tor-"))
    }
}

