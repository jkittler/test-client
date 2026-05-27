package com.ysoft.wsnewtest

import com.ysoft.wsnewtest.proto.Wsnew
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.slf4j.LoggerFactory
import java.security.cert.X509Certificate
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Thin OkHttp WebSocket wrapper. Transport only — no protocol logic.
 * Inbound binary frames are parsed and pushed onto a blocking queue.
 */
class WsClient(private val url: String, private val trustAllCerts: Boolean = false) {
    private val log = LoggerFactory.getLogger(WsClient::class.java)
    private val inbound: BlockingQueue<Event> = LinkedBlockingQueue()
    private lateinit var ws: WebSocket

    sealed interface Event {
        data class Message(val parsed: Envelopes.Inbound) : Event
        data class Closed(val code: Int, val reason: String) : Event
        data class Failed(val t: Throwable, val response: Response?) : Event
        data object Open : Event
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for long-lived WS
        .apply { if (trustAllCerts) applyTrustAll(this) }
        .build()

    // Dev-only: accept the server's self-signed cert. Never use against production.
    private fun applyTrustAll(b: OkHttpClient.Builder) {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), java.security.SecureRandom())
        }
        b.sslSocketFactory(ctx.socketFactory, trustAll)
        b.hostnameVerifier { _, _ -> true }
    }

    fun connect() {
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log.info("WS open: HTTP {} {}", response.code, response.message)
                inbound.put(Event.Open)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                inbound.put(Event.Message(Envelopes.parse(bytes.toByteArray())))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                log.warn("Unexpected TEXT frame ({} chars), ignoring", text.length)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                inbound.put(Event.Closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                inbound.put(Event.Failed(t, response))
            }
        })
    }

    fun send(envelope: Wsnew.R_Envelope) {
        ws.send(envelope.toByteArray().toByteString())
    }

    /** Wait for the next event up to [timeoutSeconds], or null on timeout. */
    fun poll(timeoutSeconds: Long): Event? = inbound.poll(timeoutSeconds, TimeUnit.SECONDS)

    /** Block for the next event indefinitely. */
    fun take(): Event = inbound.take()

    fun close() {
        if (::ws.isInitialized) ws.close(1000, "client shutdown")
        http.dispatcher.executorService.shutdown()
    }
}
