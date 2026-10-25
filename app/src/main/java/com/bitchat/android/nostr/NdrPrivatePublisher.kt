package com.bitchat.android.nostr

import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Fresh send-only sockets. No receiving subscriptions, AUTH, or shared-socket fallback. */
internal class NdrPrivatePublisher(
    private val scope: CoroutineScope,
    private val openSocket: (Request, WebSocketListener) -> WebSocket
) {
    private val attempts = ConcurrentHashMap<String, Attempt>()

    fun send(event: NostrEvent, urls: List<String>, authorized: () -> Boolean, completion: (Boolean) -> Unit) {
        val attempt = Attempt(event, authorized, completion)
        if (!authorized() || attempts.putIfAbsent(event.id, attempt) != null) {
            completion(false)
            return
        }
        attempt.start(urls.distinct())
    }

    fun cancel(eventID: String) { attempts[eventID]?.finish(false) }
    fun cancelAll() { attempts.values.toList().forEach { it.finish(false) } }

    private inner class Attempt(
        private val event: NostrEvent,
        private val authorized: () -> Boolean,
        private val completion: (Boolean) -> Unit
    ) {
        private val done = AtomicBoolean(false)
        private val sockets = ConcurrentHashMap.newKeySet<WebSocket>()
        private val pending = ConcurrentHashMap.newKeySet<String>()
        private var timeout: Job? = null

        fun start(urls: List<String>) {
            pending.addAll(urls)
            if (pending.isEmpty()) { finish(false); return }
            timeout = scope.launch { delay(15_000); finish(false) }
            for (url in urls) {
                if (done.get()) break
                val listener = object : WebSocketListener() {
                    private var framesRemaining = 16
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets.add(webSocket)
                        if (done.get() || !authorized()) { finish(false); webSocket.cancel(); return }
                        val frame = "[\"EVENT\",${event.toJsonString()}]"
                        if (!webSocket.send(frame)) settle(url, webSocket, false)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (done.get() || !authorized()) { finish(false); return }
                        val frame = runCatching {
                            require(text.length <= 64 * 1024)
                            JsonParser.parseString(text).asJsonArray
                        }.getOrNull()
                        val kind = runCatching { frame?.get(0)?.asString }.getOrNull()
                        if (kind == "AUTH" || kind == null) { settle(url, webSocket, false); return }
                        if (kind == "OK" && runCatching { frame!![1].asString == event.id }.getOrDefault(false)) {
                            val accepted = runCatching {
                                val ok = frame!![2]
                                require(ok.isJsonPrimitive && ok.asJsonPrimitive.isBoolean)
                                isNip20ConfirmedSuccess(ok.asBoolean, if (frame.size() > 3) frame[3].asString else null)
                            }.getOrDefault(false)
                            settle(url, webSocket, accepted)
                        } else if (--framesRemaining == 0) settle(url, webSocket, false)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                        settle(url, webSocket, false)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        settle(url, webSocket, false)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        settle(url, webSocket, false)
                    }
                }
                try {
                    val request = Request.Builder().url(url).build()
                    require(request.url.username.isEmpty() && request.url.password.isEmpty())
                    val socket = openSocket(request, listener)
                    sockets.add(socket)
                    if (done.get()) { sockets.remove(socket); socket.cancel() }
                } catch (_: Exception) {
                    pending.remove(url)
                    if (pending.isEmpty()) finish(false)
                }
            }
        }

        private fun settle(url: String, socket: WebSocket, accepted: Boolean) {
            sockets.remove(socket)
            socket.cancel()
            if (!pending.remove(url)) return
            if (accepted || pending.isEmpty()) finish(accepted)
        }

        fun finish(accepted: Boolean) {
            if (!done.compareAndSet(false, true)) return
            attempts.remove(event.id, this)
            timeout?.cancel()
            sockets.toList().forEach(WebSocket::cancel)
            sockets.clear()
            completion(accepted && authorized())
        }
    }
}
