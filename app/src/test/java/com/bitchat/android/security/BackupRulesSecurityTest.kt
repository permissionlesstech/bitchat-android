package com.bitchat.android.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against regressions of issues #647 and #699: verifies that
 * AndroidManifest.xml disables Auto Backup and both backup_rules.xml and
 * data_extraction_rules.xml exclude all storage domains by default.
 */
class BackupRulesSecurityTest {

    private fun appMainDir(): File {
        val candidates = listOf(
            File("src/main"),
            File("app/src/main")
        )
        return candidates.first { it.exists() }
    }

    @Test
    fun manifestDisablesAllowBackup() {
        val manifest = File(appMainDir(), "AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest.xml must set android:allowBackup=\"false\"",
            manifest.contains("android:allowBackup=\"false\"")
        )
    }

    @Test
    fun backupRulesExcludeAllDomains() {
        val rules = File(appMainDir(), "res/xml/backup_rules.xml").readText()
        val requiredDomains = listOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref"
        )
        for (domain in requiredDomains) {
            assertTrue(
                "backup_rules.xml must exclude domain=\"$domain\"",
                rules.contains("<exclude domain=\"$domain\" path=\".\" />")
            )
        }
    }

    @Test
    fun dataExtractionRulesExcludeCloudBackupAndDeviceTransfer() {
        val rules = File(appMainDir(), "res/xml/data_extraction_rules.xml").readText()
        assertTrue(rules.contains("<cloud-backup>"))
        assertTrue(rules.contains("<device-transfer>"))
        assertTrue(rules.contains("<exclude domain=\"database\" path=\".\" />"))
        assertTrue(rules.contains("<exclude domain=\"sharedpref\" path=\".\" />"))
    }
}
