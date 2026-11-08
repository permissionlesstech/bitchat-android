package com.bitchat.android.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.ImeAction
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.testhook.ChatSessionTestAccess
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.ConversationStoreState
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComposerOutcomeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val editor = hasSetTextAction() and SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Send)
    private fun field() = compose.onNode(editor)
    private fun session() = ChatSessionTestAccess.of(compose.activity)
    private fun store() = session().panicClear().appStateStore
    private fun start(): PanicClear {
        compose.waitUntil(30_000) { compose.onAllNodes(editor).fetchSemanticsNodes().size == 1 }
        return session().panicClear()
    }
    /** The store's repository is private and set once at start-up; tests swap it to make a wipe fail or wait. */
    private val repositoryField = AppStateStore::class.java.getDeclaredField("conversationRepository").apply { isAccessible = true }
    private fun currentRepository() = repositoryField.get(store()) as ConversationRepository?
    private fun useRepository(repository: ConversationRepository?) = repositoryField.set(store(), repository)
    private fun confirmation() {
        compose.mainClock.autoAdvance = false
        try { repeat(3) { compose.onNodeWithContentDescription(context.getString(R.string.cd_open_about)).performClick() } }
        finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithText(context.getString(R.string.panic_confirm_title)).assertIsDisplayed()
    }
    private fun confirm() = compose.onNodeWithText(context.getString(R.string.panic_confirm_action)).performClick()
    private fun ids() = store().publicMessages.value.map { it.id }.toSet()
    private fun awaitOutcome(prior: Set<String>, resource: Int) {
        compose.waitUntil(30_000) { store().publicMessages.value.any { it.id !in prior && it.content == context.getString(resource) } }
    }
    private fun inProgress(vm: PanicClear): Boolean = PanicClear::class.java.getDeclaredField("panicClearInProgress").apply { isAccessible = true }.getBoolean(vm)

    private fun withCleanup(vararg cleanup: () -> Unit, block: () -> Unit) {
        var failure: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            failure = error
        } finally {
            for (action in cleanup) {
                try {
                    action()
                } catch (error: Throwable) {
                    val primary = failure
                    if (primary == null) failure = error
                    else if (primary !== error) primary.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }

    @Test fun failedConversationWipeStillClearsConfirmedDraft() {
        val vm = start()
        val original = currentRepository()
        val name = "synthetic-composer-failed-store.db"
        val path = context.getDatabasePath(name)
        path.deleteRecursively(); assertTrue(path.mkdirs())
        val repository = ConversationRepository(context, databaseName = name)
        withCleanup(
            { compose.waitUntil(30_000) { !inProgress(vm) } },
            { useRepository(original) },
            { repository.closeForTest() },
            { path.deleteRecursively() },
            {
                val prior = ids()
                compose.runOnIdle {
                    check(!inProgress(vm)) { "previous panic wipe has not finished" }
                    vm.run()
                }
                awaitOutcome(prior, R.string.system_panic_completed)
            }
        ) {
            useRepository(repository)
            field().performTextInput("SYNTHETIC_FAILED_WIPE")
            confirmation(); val prior = ids(); confirm()
            awaitOutcome(prior, R.string.system_panic_incomplete)
            compose.waitUntil(5_000) { !inProgress(vm) }
            field().assertTextEquals("")
            assertTrue(repository.storeState.value is ConversationStoreState.Error)
            assertFalse(store().publicMessages.value.any { it.id !in prior && it.content == context.getString(R.string.system_panic_completed) })
        }
    }

    @Test fun secondConfirmationClearsDraftWhileFirstWipeWaitsThenDrains() {
        val vm = start()
        val original = currentRepository()
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val queued = AtomicInteger()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.incrementAndGet(); executor.execute(block) }
        }
        val repository = ConversationRepository(context, dispatcher, "synthetic-composer-held-store.db")
        withCleanup(
            { release.countDown() },
            { compose.waitUntil(30_000) { !inProgress(vm) } },
            { useRepository(original) },
            { repository.closeForTest() },
            { executor.shutdown() },
            { assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) },
            { context.deleteDatabase("synthetic-composer-held-store.db") }
        ) {
            executor.execute { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            useRepository(repository)
            field().performTextInput("SYNTHETIC_FIRST")
            confirmation(); val prior = ids(); confirm()
            compose.waitUntil(5_000) { queued.get() == 1 && inProgress(vm) }
            field().assertTextEquals("")
            assertEquals(1L, release.count)
            field().performTextInput("SYNTHETIC_WHILE_WAITING")
            confirmation(); confirm(); field().assertTextEquals("")
            assertEquals("second confirmation must not enqueue another wipe", 1, queued.get())
            assertTrue(inProgress(vm))
            release.countDown()
            awaitOutcome(prior, R.string.system_panic_completed)
            compose.waitUntil(5_000) { !inProgress(vm) }
            field().assertTextEquals("")
            field().performTextInput("SYNTHETIC_AFTER_DRAIN")
            field().assertTextEquals("SYNTHETIC_AFTER_DRAIN")
        }
    }

    @Test fun sendButtonAcceptsOrdinaryAndFreshPostWipeTextIntoLocalSink() {
        start()
        val captured = CopyOnWriteArrayList<String>()
        var delegateToRestore = session().sessionMesh().unified
        var installedSink: MeshService? = null
        fun installSink() {
            val delegate = session().sessionMesh().unified
            delegateToRestore = delegate
            val sink = object : MeshService by delegate {
                override fun sendMessage(content: String, mentions: List<String>, channel: String?) { captured.add(content) }
            }
            installedSink = sink
            compose.runOnIdle { session().sessionMesh().replace(sink) }
        }
        fun send(value: String) {
            field().performTextReplacement(value)
            compose.onNodeWithContentDescription(context.getString(R.string.send_message)).performClick()
            compose.waitUntil(5_000) { captured.contains(value) }
            field().assertTextEquals("")
            assertTrue(store().publicMessages.value.any { it.content == value })
        }
        withCleanup({
            // A successful wipe replaced the service; unwrap the currently installed sink.
            compose.runOnIdle {
                if (session().sessionMesh().unified === installedSink) {
                    session().sessionMesh().replace(delegateToRestore)
                }
            }
        }) {
            installSink(); send("SYNTHETIC_ORDINARY_SEND")
            field().performTextInput("SYNTHETIC_DISCARDED")
            confirmation(); val prior = ids(); confirm(); awaitOutcome(prior, R.string.system_panic_completed)
            field().assertTextEquals("")
            assertFalse(store().publicMessages.value.any { it.content == "SYNTHETIC_ORDINARY_SEND" })
            installSink(); send("SYNTHETIC_FRESH_SEND")
            assertEquals(listOf("SYNTHETIC_ORDINARY_SEND", "SYNTHETIC_FRESH_SEND"), captured.toList())
        }
    }
}
