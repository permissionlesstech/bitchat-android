package com.bitchat.android.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.ConversationRepository
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Closing a repository while writes are queued leaves the file usable at once by the next one. */
@RunWith(AndroidJUnit4::class)
class ConversationRepositoryCloseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun closingFromAnotherThreadDuringABurstOfWritesLeavesNoLockForTheNextRepository() {
        val run = UUID.randomUUID().toString()
        fun message(id: String) = BitchatMessage(
            id = "$run-$id", sender = "synthetic", content = id, timestamp = Date(), isPrivate = true,
        )
        repeat(15) { round ->
            val name = "synthetic-close-${UUID.randomUUID()}.db"
            val old = ConversationRepository(context, databaseName = name)
            try {
                val writer = Thread {
                    repeat(300) { i ->
                        old.upsertMessage("synthetic-peer", setOf("synthetic-peer"), "synthetic", message("$round-$i"), isRead = true)
                    }
                }
                writer.start()
                Thread.sleep(5)
                // From a third thread, while the burst is queued and running.
                Thread { old.close() }.apply { start(); join() }
                writer.join()

                val next = ConversationRepository(context, databaseName = name)
                try {
                    assertTrue(
                        runBlocking {
                            next.upsertMessageAndWait("synthetic-peer", setOf("synthetic-peer"), "synthetic", message("next-$round"), isRead = true)
                        },
                    )
                } finally {
                    next.close()
                }
            } finally {
                old.close()
                context.deleteDatabase(name)
            }
        }
    }
}
