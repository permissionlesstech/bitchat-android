package com.bitchat.android.panic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Process
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.NotificationCompat
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.identity.scope.IdentityLifetimeEntryPoint
import com.bitchat.android.identity.scope.entryPoint
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.navigation.OnboardedApp
import com.bitchat.android.navigation.awaitChat
import com.bitchat.android.nostr.di.NostrIdentityEntryPoint
import com.bitchat.android.testhook.ChatSessionTestAccess
import com.bitchat.android.testhook.MeshTestAccess
import com.bitchat.android.testhook.SandboxWipeObserver
import com.bitchat.android.ui.debug.DebugMessage
import java.io.File
import java.security.KeyStore
import java.util.Collections
import java.util.Date
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a confirmed panic leaves, on the real application graph: the sandbox holds only what the
 * keep list names, the kept settings survive, everything outside the sandbox is undone, and the
 * app goes on in the same process on a new identity whose storage works.
 *
 * Seeds synthetic data and wipes the app's data, which the clean emulator is for.
 */
@RunWith(AndroidJUnit4::class)
class PanicContractTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val PEER = "0123456789abcdef"
        private const val SETTINGS = "bitchat_settings"
        private const val THEME = "theme_preference"
        private const val UNKNOWN_SETTING = "synthetic_unknown_setting"
        private const val CHANNEL = "panic_contract_test"
        private const val NOTIFICATION_ID = 0x5A17
        private const val CLIP = "synthetic copied text"
        private const val DEBUG_ENTRY = "synthetic debug entry"
        private const val BOOKMARK = "s000"

        /**
         * Top-level entries the wipe leaves to the system: `lib` links to the installed native
         * libraries, and `code_cache` is the runtime's (and the instrumentation's) compiled-code cache.
         */
        private val SYSTEM_OWNED = setOf("lib", "code_cache")

        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    private val context: Context get() = rule.activity.applicationContext

    private fun graph() = MeshTestAccess.of(context)

    private fun session() = ChatSessionTestAccess.of(rule.activity)

    private var themeBefore: String? = null

    @After
    fun tearDown() {
        SandboxWipeObserver.afterWipe = null
        val settings = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
        themeBefore?.let { settings.putString(THEME, it) } ?: settings.remove(THEME)
        settings.commit()
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.cancel(NOTIFICATION_ID)
        notifications.deleteNotificationChannel(CHANNEL)
    }

    @Test
    fun aConfirmedPanicLeavesOnlyTheKeepListAndGoesOnInTheSameProcess() {
        rule.awaitChat()
        val process = Process.myPid()
        val holder = graph().identityHolder()
        val oldScope = holder.entryPoint<IdentityLifetimeEntryPoint>().scope()
        seed()

        var leftovers: List<String>? = null
        var aliases: List<String>? = null
        var confirmed = false
        SandboxWipeObserver.afterWipe = { result ->
            leftovers = sandboxOutsideTheKeepList()
            aliases = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList()
            confirmed = result.confirmed
        }

        val before = holder.generation.value
        rule.runOnUiThread { session().panicClear().run() }
        rule.waitUntil(timeoutMillis = 15_000) {
            holder.generation.value == before + 1 && !session().panicClear().inProgress
        }
        rule.awaitChat()

        // A restart would have ended this process.
        assertEquals(process, Process.myPid())
        assertFalse(rule.activity.isFinishing)
        assertFalse(oldScope.isActive)
        assertTrue(confirmed)
        assertEquals("only the keep list survives the wipe", emptyList<String>(), leftovers)
        assertEquals("no Keystore entry survives the wipe", emptyList<String>(), aliases)
        assertFalse(PanicMarker(context).pending)

        // The kept setting survives by key, the unknown one beside it does not.
        val settings = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        assertEquals("Dark", settings.getString(THEME, null))
        assertFalse(settings.contains(UNKNOWN_SETTING))

        // The seeded data is gone, from disk and from the new identity.
        assertFalse(File(context.filesDir, "images/incoming/synthetic.jpg").exists())
        assertNull(graph().appStateStore().privateMessages.value[PEER])
        assertTrue(holder.entryPoint<NostrIdentityEntryPoint>().geohashBookmarksStore().bookmarks.value.isEmpty())
        assertTrue(graph().favorites().getOurFavorites().isEmpty())

        // Every system-side cleanup ran.
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue("the seeded notification is gone", active.none { it.id == NOTIFICATION_ID })
        var clip: String? = "unread"
        rule.runOnUiThread {
            clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }
        assertTrue("the clipboard no longer holds the seeded clip", clip != CLIP)
        assertTrue(graph().debugSettings().debugMessages.value.none { DEBUG_ENTRY in it.content })

        // The new identity's encrypted storage works over the wiped keys.
        rule.runOnUiThread {
            val secure = session().secureIdentityStateManager()
            secure.storeSecureValue("synthetic_probe", "synthetic value")
            assertEquals("synthetic value", secure.getSecureValue("synthetic_probe"))
            assertTrue(secure.clearSecureValuesSynchronously("synthetic_probe"))
        }

        val completed = rule.activity.getString(R.string.system_panic_completed)
        assertTrue(graph().appStateStore().publicMessages.value.any { it.content == completed })
    }

    /**
     * A manager that starts building while the panic holds the identity's storage builds nothing,
     * its master key included, until the panic lets go. Built before the wipe ends and finished
     * after it, the master key and the keyset were made by two key generators that do not exclude
     * each other, and the next identity's keyset was left under a key that no longer existed: an
     * AEADBadTagException on every later launch.
     */
    @Test
    fun aManagerStartedDuringTheWipeBuildsNoKeyUntilThePanicLetsGo() {
        rule.awaitChat()
        // Read once: the rule's activity waits for the main thread, which the wipe holds.
        val app = context
        val holder = graph().identityHolder()
        val storage = SecureIdentityStateManager::class.java.getDeclaredField("storageLock")
            .apply { isAccessible = true }.get(null) as ReentrantReadWriteLock
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        var manager: Thread? = null
        var waited = false
        var masterKeyWhileHeld: Boolean? = null
        SandboxWipeObserver.afterWipe = {
            val started = thread(name = "manager-during-the-wipe") {
                try {
                    SecureIdentityStateManager(app)
                } catch (e: Throwable) {
                    failures += e
                }
            }
            manager = started
            val deadline = System.currentTimeMillis() + 5_000
            while (!storage.hasQueuedThread(started) && started.isAlive && System.currentTimeMillis() < deadline) {
                Thread.sleep(1)
            }
            waited = storage.hasQueuedThread(started)
            masterKeyWhileHeld = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }

        val before = holder.generation.value
        rule.runOnUiThread { session().panicClear().run() }
        rule.waitUntil(timeoutMillis = 15_000) {
            holder.generation.value == before + 1 && !session().panicClear().inProgress
        }
        manager!!.join(10_000)

        assertTrue("the manager waited for the panic", waited)
        assertEquals("no master key is built while the panic holds the storage", false, masterKeyWhileHeld)
        assertEquals(emptyList<Throwable>(), failures)
        // The storage opens again under the key the next identity built.
        SecureIdentityStateManager(app)
    }

    /** Synthetic data across the identity and the sandbox, and outside it. */
    private fun seed() {
        val message = BitchatMessage(
            id = UUID.randomUUID().toString(),
            sender = "synthetic",
            content = "synthetic private text",
            timestamp = Date(),
            isPrivate = true,
            senderPeerID = PEER,
        )
        graph().appStateStore().addPrivateMessage(PEER, message)
        graph().favorites().updateFavoriteStatus(ByteArray(32) { 0x5A }, "synthetic", true)
        assertTrue(graph().favorites().getOurFavorites().isNotEmpty())
        // Written as the bookmark store writes it, with its name, so nothing looks the name up.
        assertTrue(
            context.getSharedPreferences("geohash_prefs", Context.MODE_PRIVATE).edit()
                .putString("locationChannel.bookmarks", "[\"$BOOKMARK\"]")
                .putString("locationChannel.bookmarkNames", "{\"$BOOKMARK\":\"synthetic\"}")
                .commit(),
        )
        File(context.filesDir, "images/incoming").mkdirs()
        File(context.filesDir, "images/incoming/synthetic.jpg").writeBytes(ByteArray(64) { 0x7F })

        val settings = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        themeBefore = settings.getString(THEME, null)
        assertTrue(settings.edit().putString(THEME, "Dark").putString(UNKNOWN_SETTING, "synthetic").commit())

        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "synthetic", NotificationManager.IMPORTANCE_LOW))
        notifications.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("synthetic")
                .build(),
        )
        // Posting reaches the system asynchronously.
        rule.waitUntil(timeoutMillis = 5_000) { notifications.activeNotifications.any { it.id == NOTIFICATION_ID } }
        rule.runOnUiThread {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("synthetic", CLIP))
        }
        graph().debugSettings().addDebugMessage(DebugMessage.SystemMessage(DEBUG_ENTRY))
        assertTrue(graph().debugSettings().debugMessages.value.any { DEBUG_ENTRY in it.content })
    }

    /**
     * Walks the sandbox the wipe covers and returns each file, and each empty directory, that the
     * keep list does not name, relative to its root.
     */
    private fun sandboxOutsideTheKeepList(): List<String> {
        val kept = KeepList.paths.map { it.relative }
        fun isKept(rel: String) = kept.any { k ->
            if (k.endsWith("*")) {
                File(rel).parent == File(k).parent && File(rel).name.startsWith(File(k).name.removeSuffix("*"))
            } else {
                rel == k || rel.startsWith("$k/")
            }
        }
        fun leadsToKept(rel: String) = kept.any { it.startsWith("$rel/") }

        val found = ArrayList<String>()
        val roots = listOfNotNull(
            Triple("", context.dataDir, true),
            context.getExternalFilesDir(null)?.let { Triple("external_files/", it, false) },
            context.externalCacheDir?.let { Triple("external_cache/", it, false) },
        )
        for ((label, root, keeps) in roots) {
            assertNotNull(root)
            root.walkTopDown()
                .onEnter { dir -> dir == root || dir.relativeTo(root).path.substringBefore('/') !in SYSTEM_OWNED || !keeps }
                .filter { it != root }
                .forEach { entry ->
                    val rel = entry.relativeTo(root).path
                    if (keeps && rel.substringBefore('/') in SYSTEM_OWNED) return@forEach
                    val isEmptyDir = entry.isDirectory && entry.list().isNullOrEmpty()
                    if (!entry.isDirectory || isEmptyDir) {
                        val allowed = keeps && (isKept(rel) || (isEmptyDir && leadsToKept(rel)))
                        if (!allowed) found += label + rel
                    }
                }
        }
        return found
    }
}
