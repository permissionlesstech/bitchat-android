package com.bitchat.android.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A panic wipes the sandbox by location, but a notification, a shortcut, a clipboard clip, a
 * scheduled job or a persisted URI grant lives in the system. Each main source file that reaches one of those APIs is
 * listed here with the `PanicCleanup` that covers it, or the reason none is needed, so a new use
 * cannot arrive without someone deciding what panic does about it. Comments and string literals
 * are dropped before the scan.
 */
class PanicSystemSideRulesTest {

    private val allowlist: Map<String, String> = SYSTEM_SIDE_ALLOWLIST

    @Test
    fun aFileUsingASystemSideApiIsAllowlistedWithAReason() {
        val offenders = violations(realSources(), allowlist)
        assertTrue(
            "Each file below uses a system-side API. Cover it with a PanicCleanup and list it in " +
                "SYSTEM_SIDE_ALLOWLIST with the cleanup's name, or say why none is needed:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun theAllowlistHasNoStaleEntry() {
        val sources = realSources()
        val using = sources.filter { usedApis(it.code).isNotEmpty() }.map { it.path }.toSet()
        val stale = allowlist.keys.filter { it !in using }
        assertTrue("These entries no longer use a system-side API; remove them:\n${stale.joinToString("\n")}", stale.isEmpty())
        val blank = allowlist.filterValues { it.isBlank() }.keys
        assertTrue("These entries give no reason:\n${blank.joinToString("\n")}", blank.isEmpty())
    }

    @Test
    fun theScanCatchesEachApi() {
        SYSTEM_SIDE_APIS.forEach { api ->
            val found = violations(listOf(Src("app/src/main/java/x/Uses$api.kt", "import android.x.$api\nclass A { val m: $api? = null }")), emptyMap())
            assertEquals("$api is not caught", 1, found.size)
        }
    }

    @Test
    fun aPersistedUriGrantIsASystemSideUse() {
        val src = Src(
            "feature/chat/src/main/java/x/Picker.kt",
            "fun keep(c: Context, u: Uri) { c.contentResolver.takePersistableUriPermission(u, 1) }",
        )
        assertEquals(listOf("${src.path} uses takePersistableUriPermission"), violations(listOf(src), emptyMap()))
    }

    @Test
    fun theScanIgnoresCommentsStringsAndLookalikeNames() {
        val sources = listOf(
            Src("app/src/main/java/x/Comment.kt", "// WorkManager\n/* NotificationManager\n AlarmManager */\nclass A"),
            Src("app/src/main/java/x/Text.kt", "class B { val s = \"MediaStore ClipboardManager\" }"),
            Src("app/src/main/java/x/Lookalike.kt", "class C { val a: MyWorkManagerFactory? = null; val b: NotificationManagerX? = null }"),
        )
        assertTrue(violations(sources, emptyMap()).isEmpty())
    }

    @Test
    fun theScanSeesAnAllowlistedFileOnlyWhenItIsListed() {
        val src = Src("app/src/main/java/x/Uses.kt", "class A { val m: WorkManager? = null }")
        assertEquals(1, violations(listOf(src), emptyMap()).size)
        assertTrue(violations(listOf(src), mapOf(src.path to "reason")).isEmpty())
    }

    // ---- The scan ---------------------------------------------------------------------------

    private class Src(val path: String, val code: String)

    private fun violations(sources: List<Src>, allowed: Map<String, String>): List<String> =
        sources.filter { it.path !in allowed && usedApis(it.code).isNotEmpty() }
            .map { "${it.path} uses ${usedApis(it.code).joinToString(", ")}" }

    private fun usedApis(code: String): List<String> {
        val bare = mask(code)
        return SYSTEM_SIDE_APIS.filter { Regex("""\b$it\b""").containsMatchIn(bare) }
    }

    /** Drops comments and string literals so a mention in prose is not a use. */
    private fun mask(code: String): String =
        code.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex(""""(?:\\.|[^"\\\n])*""""), "\"\"")
            .replace(Regex("""//[^\n]*"""), " ")

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** Main sources of the phone app and the shared modules; the watch has no panic. */
    private fun realSources(): List<Src> {
        val files = listOf("app", "core", "feature").flatMap { top ->
            File(root, top).walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" }
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.relativeTo(root).path.replace(File.separatorChar, '/').contains("/src/main/") }
                .toList()
        }
        assertTrue("Found no sources under $root; the scan is broken", files.size > 100)
        return files.map { Src(it.relativeTo(root).path.replace(File.separatorChar, '/'), it.readText()) }
    }

    private companion object {
        val SYSTEM_SIDE_APIS = listOf(
            "NotificationManager", "NotificationManagerCompat", "ShortcutManager", "ShortcutManagerCompat",
            "WorkManager", "AlarmManager", "MediaStore", "ClipboardManager", "LocalClipboardManager", "JobScheduler",
            "takePersistableUriPermission",
        )

        val SYSTEM_SIDE_ALLOWLIST: Map<String, String> = mapOf(
            "app/src/main/java/com/bitchat/android/MainActivity.kt" to
                "NotificationsPanicCleanup: reads the notification extras that open a chat, and holds the chat notifications",
            "app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt" to
                "NotificationsPanicCleanup: the mesh service's own notification manager",
            "app/src/main/java/com/bitchat/android/model/FileSharingManager.kt" to
                "reads a display name only",
            "app/src/main/java/com/bitchat/android/panic/ClipboardPanicCleanup.kt" to
                "is ClipboardPanicCleanup",
            "app/src/main/java/com/bitchat/android/panic/NotificationsPanicCleanup.kt" to
                "is NotificationsPanicCleanup",
            "app/src/main/java/com/bitchat/android/service/AppShutdownCoordinator.kt" to
                "NotificationsPanicCleanup: cancels notifications at shutdown",
            "app/src/main/java/com/bitchat/android/service/ConversationNotificationReceiver.kt" to
                "NotificationsPanicCleanup: handles the actions of the chat notifications",
            "app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt" to
                "NotificationsPanicCleanup: the service's own notifications",
            "app/src/main/java/com/bitchat/android/service/PeerAvailabilityNotifier.kt" to
                "NotificationsPanicCleanup: posts the peer availability notifications",
            "app/src/main/java/com/bitchat/android/wifi-aware/WifiAwareMeshService.kt" to
                "NotificationsPanicCleanup: the mesh service's own notification manager",
            "core/chat/src/main/java/com/bitchat/android/di/ChatSessionBindings.kt" to
                "NotificationsPanicCleanup",
            "core/chat/src/main/java/com/bitchat/android/di/ChatSessionModule.kt" to
                "NotificationsPanicCleanup",
            "core/chat/src/main/java/com/bitchat/android/ui/ConversationDirectory.kt" to
                "NotificationsPanicCleanup",
            "core/chat/src/main/java/com/bitchat/android/ui/GeohashSession.kt" to
                "NotificationsPanicCleanup",
            "core/chat/src/main/java/com/bitchat/android/ui/MeshDelegateHandler.kt" to
                "NotificationsPanicCleanup",
            "core/chat/src/main/java/com/bitchat/android/ui/NotificationManager.kt" to
                "NotificationsPanicCleanup",
            "core/chat/src/main/java/com/bitchat/android/ui/PrivateChatSession.kt" to
                "NotificationsPanicCleanup",
            "core/chat/src/main/java/com/bitchat/android/ui/VerificationHandler.kt" to
                "NotificationsPanicCleanup",
            "core/mesh/src/main/java/com/bitchat/android/features/file/FileUtils.kt" to
                "reads a display name only",
            "feature/chat/src/main/java/com/bitchat/android/ui/ChatUserSheet.kt" to
                "ClipboardPanicCleanup",
            "feature/chat/src/main/java/com/bitchat/android/ui/MessageComponents.kt" to
                "ClipboardPanicCleanup",
            "feature/chat/src/main/java/com/bitchat/android/ui/SecurityVerificationSheet.kt" to
                "ClipboardPanicCleanup",
            "feature/chat/src/main/java/com/bitchat/android/ui/media/FullScreenImageViewer.kt" to
                "Exported to Downloads by the person on purpose; left alone (open question in the decision log)",
            "feature/settings/src/main/java/com/bitchat/android/ui/debug/DebugSettingsSheet.kt" to
                "ClipboardPanicCleanup",
            "feature/share/src/main/java/com/bitchat/android/hotspot/HotspotActivity.kt" to
                "ClipboardPanicCleanup",
            "feature/share/src/main/java/com/bitchat/android/util/ApkDownloadWorker.kt" to
                "APK download job, public release only",
            "feature/share/src/main/java/com/bitchat/android/util/WorkManagerApkDownloader.kt" to
                "APK download job, public release only",
        )
    }
}
