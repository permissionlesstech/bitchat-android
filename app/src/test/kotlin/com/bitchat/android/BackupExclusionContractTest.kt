package com.bitchat.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * bitchat's app-private storage — the Noise/Nostr key material, the identity store and the
 * private conversation database — must never be handed to Auto Backup or to a device-to-device
 * transfer.
 *
 * These rules used to be an allow-by-default list naming two files, and it went stale: it
 * excluded "bitchat_crypto" after EncryptionService had already moved to
 * "bitchat_crypto_secure". So the invariant pinned here is deny-by-default over whole domains,
 * which cannot go stale when storage is renamed or added.
 */
class BackupExclusionContractTest {

    private val resourcesDirectory = File("src/main/res")
    private val manifest = File("src/main/AndroidManifest.xml")

    private val allDomains = setOf(
        "root",
        "file",
        "database",
        "sharedpref",
        "external",
        "device_root",
        "device_file",
        "device_database",
        "device_sharedpref"
    )

    private fun rulesFile(name: String) = File(resourcesDirectory, "xml/$name").readText()

    /** Domains excluded at path "." within [section], or across the file when it has no sections. */
    private fun excludedDomains(xml: String, section: String?): Set<String> {
        val scope = if (section == null) {
            xml
        } else {
            Regex("""<$section[^>]*>(.*?)</$section>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?: fail("missing <$section> section")
        }
        return Regex("""<exclude\s+domain="([^"]+)"\s+path="\."\s*/>""")
            .findAll(scope)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    @Test
    fun `cloud backup excludes every storage domain`() {
        assertEquals(
            allDomains,
            excludedDomains(rulesFile("data_extraction_rules.xml"), "cloud-backup")
        )
    }

    @Test
    fun `device to device transfer excludes every storage domain`() {
        // allowBackup=false does not reliably disable D2D migration on Android 12+, so this
        // section is the only thing protecting a phone migration.
        assertEquals(
            allDomains,
            excludedDomains(rulesFile("data_extraction_rules.xml"), "device-transfer")
        )
    }

    @Test
    fun `legacy auto backup rules exclude every storage domain`() {
        assertEquals(allDomains, excludedDomains(rulesFile("backup_rules.xml"), null))
    }

    @Test
    fun `no rule opts any path back in`() {
        for (name in listOf("data_extraction_rules.xml", "backup_rules.xml")) {
            assertFalse(
                "$name must not re-include anything",
                rulesFile(name).contains("<include")
            )
        }
    }

    @Test
    fun `the manifest still disables backup and points at both rule files`() {
        val text = manifest.readText()
        assertTrue(text.contains("""android:allowBackup="false""""))
        assertTrue(text.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(text.contains("""android:fullBackupContent="@xml/backup_rules""""))
    }
}
