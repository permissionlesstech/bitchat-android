package com.bitchat.watch.testhook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import com.bitchat.android.features.voice.VoiceWaveformCache
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.ui.WearChatState
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import kotlin.math.PI
import kotlin.math.sin

/**
 * Debug-only UI demo data for screenshot reviews. Everything here is synthetic: fixed fake
 * peer IDs and nicknames, scripted messages, a generated gradient image and a generated tone.
 * The peers exist only in the local peer table and the shared UI store; no packets are sent.
 *
 * Seeding is refused on an installation whose nickname was chosen by a person, so a real
 * name never ends up in a screenshot and real conversation history is never mixed with demo
 * rows. A disposable install (auto-generated name) is switched to [DEMO_SELF_NICKNAME].
 */
internal object WearUiDemoSeeder {
    private const val TRANSPORT_ID = "ui_demo"
    const val DEMO_SELF_NICKNAME = "ada"
    private const val DEMO_PREFS = "bitchat_watch_ui_demo"
    private const val KEY_SEEDED = "seeded"

    val peers = linkedMapOf(
        "a11ce0000000a11c" to "alice",
        "b0b0000000000b0b" to "bob",
        "5a705100000005a7" to "satoshi",
        "c0ffee00000c0ffe" to "nightowl",
        "f00d00000000f00d" to "kiwi_long_nickname"
    )

    fun seed(context: Context, withMedia: Boolean, unread: Boolean): JSONObject {
        val mesh = WearMeshService.getOrCreate(context)
        val me = mesh.myPeerID
        val demoPrefs = context.getSharedPreferences(DEMO_PREFS, Context.MODE_PRIVATE)
        val seededBefore = demoPrefs.getBoolean(KEY_SEEDED, false)
        if (!isDisposableIdentity(mesh.nickname, me, seededBefore)) {
            return JSONObject()
                .put("status", "error")
                .put("error", "refusing to seed: nickname was chosen by a person; clear app data first")
        }
        demoPrefs.edit().putBoolean(KEY_SEEDED, true).apply()
        if (mesh.nickname != DEMO_SELF_NICKNAME) mesh.setNickname(DEMO_SELF_NICKNAME)
        val myNick = DEMO_SELF_NICKNAME
        addPeersToMesh(mesh)
        AppStateStore.setTransportPeers(TRANSPORT_ID, peers.keys.toList())

        val base = System.currentTimeMillis() - 30 * 60_000L
        var minute = 0
        fun at() = Date(base + (minute++) * 60_000L)
        fun pub(peer: String, text: String) = AppStateStore.addPublicMessage(
            BitchatMessage(sender = peers.getValue(peer), senderPeerID = peer, content = text, timestamp = at())
        )
        fun mine(text: String) = AppStateStore.addPublicMessage(
            BitchatMessage(sender = myNick, senderPeerID = me, content = text, timestamp = at())
        )

        pub("a11ce0000000a11c", "anyone near the north entrance?")
        pub("b0b0000000000b0b", "yep, by the food trucks")
        mine("on my way over")
        pub("5a705100000005a7", "mesh is looking healthy today, 5 hops end to end")
        if (withMedia) {
            AppStateStore.addPublicMessage(
                BitchatMessage(
                    sender = peers.getValue("c0ffee00000c0ffe"),
                    senderPeerID = "c0ffee00000c0ffe",
                    content = demoImage(context).absolutePath,
                    type = BitchatMessageType.Image,
                    timestamp = at()
                )
            )
            AppStateStore.addPublicMessage(
                BitchatMessage(
                    sender = peers.getValue("a11ce0000000a11c"),
                    senderPeerID = "a11ce0000000a11c",
                    content = demoVoice(context).absolutePath,
                    type = BitchatMessageType.Audio,
                    timestamp = at()
                )
            )
        }
        pub("f00d00000000f00d", "the long nickname test: does it ellipsize nicely on a round screen?")
        mine("see you all at 6")

        val alice = "a11ce0000000a11c"
        fun dm(fromMe: Boolean, text: String) = AppStateStore.addPrivateMessage(
            alice,
            BitchatMessage(
                sender = if (fromMe) myNick else peers.getValue(alice),
                senderPeerID = if (fromMe) me else alice,
                content = text,
                timestamp = at(),
                isPrivate = true,
                recipientNickname = if (fromMe) peers.getValue(alice) else myNick
            ),
            forceRead = true
        )
        dm(false, "hey, are you coming tonight?")
        dm(true, "yes! leaving in 10")
        dm(false, "great, bring the spare battery")

        if (unread) {
            repeat(2) { WearChatState.onPrivateMessageArrived("b0b0000000000b0b") }
            WearChatState.onPrivateMessageArrived("5a705100000005a7")
        }
        return JSONObject().put("status", "ok").put("peers", peers.size)
    }

    /**
     * True for the auto-generated name (`WearMeshService` defaults to `watch-<peer>`), or for the
     * demo name when this seeder set it earlier on this install. Anything else was typed by a
     * person and stays out of demo data.
     */
    internal fun isDisposableIdentity(
        nickname: String,
        myPeerID: String,
        seededBefore: Boolean
    ): Boolean = nickname == "watch-${myPeerID.take(4)}" ||
        (seededBefore && nickname == DEMO_SELF_NICKNAME)

    fun refreshPeers(context: Context): JSONObject {
        addPeersToMesh(WearMeshService.getOrCreate(context))
        AppStateStore.setTransportPeers(TRANSPORT_ID, peers.keys.toList())
        return JSONObject().put("status", "ok")
    }

    fun clearPeers(): JSONObject {
        AppStateStore.clearTransportPeers(TRANSPORT_ID)
        return JSONObject().put("status", "ok")
    }

    // The peer table is private to the shared mesh core; reflection keeps this debug-only demo
    // out of release sources.
    private fun addPeersToMesh(mesh: WearMeshService) {
        val core = WearMeshService::class.java.getDeclaredField("meshCore")
            .apply { isAccessible = true }.get(mesh)
        val peerManager = core.javaClass.getDeclaredField("peerManager")
            .apply { isAccessible = true }.get(core)
        val add = peerManager.javaClass.getMethod("addOrUpdatePeer", String::class.java, String::class.java)
        peers.forEach { (id, nick) -> add.invoke(peerManager, id, nick) }
    }

    private fun demoImage(context: Context): File {
        val file = File(context.cacheDir, "ui_demo/sunset.png")
        if (file.exists()) return file
        file.parentFile?.mkdirs()
        val bitmap = createBitmap(480, 320)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, 320f, 0xFF1B2A6B.toInt(), 0xFFFF8A3D.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 480f, 320f, paint)
        paint.shader = null
        paint.color = 0xFFFFD27A.toInt()
        canvas.drawCircle(240f, 230f, 54f, paint)
        paint.color = 0xFF10161F.toInt()
        canvas.drawRect(0f, 236f, 480f, 320f, paint)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun demoVoice(context: Context): File {
        val file = File(context.cacheDir, "ui_demo/voice.wav")
        val sampleRate = 16_000
        val samples = sampleRate * 4
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until samples) {
                val t = i.toDouble() / sampleRate
                val envelope = 0.25 + 0.75 * sin(PI * t * 1.7).let { it * it }
                pcm.putShort((sin(2 * PI * 220 * t) * envelope * 9000).toInt().toShort())
            }
            FileOutputStream(file).use { out ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
                header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                    .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
                header.put("data".toByteArray()).putInt(samples * 2)
                out.write(header.array())
                out.write(pcm.array())
            }
        }
        VoiceWaveformCache.put(file.absolutePath, FloatArray(120) { i ->
            val t = i / 120.0 * 4
            (0.25 + 0.75 * sin(PI * t * 1.7).let { it * it }).toFloat()
        })
        return file
    }
}
