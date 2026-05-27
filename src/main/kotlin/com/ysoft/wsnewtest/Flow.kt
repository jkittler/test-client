package com.ysoft.wsnewtest

import com.ysoft.wsnewtest.proto.Wsnew
import org.slf4j.LoggerFactory

/** Exit codes (see design spec). */
object Exit {
    const val OK = 0
    const val CONFIG = 1
    const val CONNECT = 2
    const val REJECTED = 3
    const val HELLO_TIMEOUT = 4
}

/**
 * Cumulative phases of the /wsnew conversation. Running a step performs every
 * earlier step first (the WS connection is stateful), then stops after the
 * named one — so you can debug each leg in isolation:
 *
 *   connect  -> just open the TLS WebSocket, then stop
 *   hello    -> + HelloClient/HelloServer
 *   session  -> + InitiateUserSession (needs jwt)
 *   adddoc   -> + RemoteDelivery/AddDocument, then drain briefly for the reaction
 *   listen   -> + stay connected, print inbound PrintLocalDocument (Ctrl-C to exit)
 *   full     -> hello -> session -> (adddoc if sendRemoteDelivery) -> listen
 */
enum class Step(val cliName: String) {
    CONNECT("connect"),
    HELLO("hello"),
    SESSION("session"),
    ADDDOC("adddoc"),
    LISTEN("listen"),
    FULL("full");

    companion object {
        val default = FULL
        fun parse(s: String?): Step? =
            if (s == null) default else entries.firstOrNull { it.cliName.equals(s, ignoreCase = true) }
        fun names(): String = entries.joinToString(", ") { it.cliName }
    }
}

/**
 * Drives the /wsnew protocol as discrete, individually-callable steps.
 * Each step is a public method returning a typed outcome and logging a banner;
 * [runUpTo] composes them and maps outcomes to process exit codes.
 */
class Flow(private val cfg: ClientConfig, private val ws: WsClient) {
    private val log = LoggerFactory.getLogger(Flow::class.java)

    @Volatile private var running = true
    fun stop() { running = false }

    // ---- outcomes ----

    sealed interface HelloOutcome {
        data class Accepted(val server: Wsnew.R_HelloServer) : HelloOutcome
        data class Rejected(val reason: String) : HelloOutcome
        data object Timeout : HelloOutcome
        data object Failed : HelloOutcome
    }

    sealed interface SessionOutcome {
        data object Skipped : SessionOutcome
        data class Cookie(val value: String) : SessionOutcome
        data class Error(val message: String) : SessionOutcome
        data object Timeout : SessionOutcome
    }

    // ---- orchestrator ----

    fun runUpTo(step: Step): Int {
        log.info("Running up to step: {}", step.cliName)

        banner("CONNECT")
        if (!open()) return Exit.CONNECT
        if (step == Step.CONNECT) return done(step)

        banner("HELLO")
        when (val h = hello()) {
            is HelloOutcome.Failed -> return Exit.CONNECT
            is HelloOutcome.Timeout -> return Exit.HELLO_TIMEOUT
            is HelloOutcome.Rejected -> return Exit.REJECTED
            is HelloOutcome.Accepted -> {}
        }
        if (step == Step.HELLO) return done(step)

        banner("SESSION")
        val cookie = when (val s = session()) {
            is SessionOutcome.Cookie -> s.value
            else -> null
        }
        if (step == Step.SESSION) return done(step)

        // ADDDOC is forced when explicitly requested; in FULL it only runs if configured.
        if (step == Step.ADDDOC || cfg.sendRemoteDelivery) {
            banner("ADDDOC")
            sendAddDocument(cookie)
            if (step == Step.ADDDOC) {
                log.info("Draining up to {}s for server reaction (parse error / close / print)...",
                    cfg.helloTimeoutSeconds)
                drain(cfg.helloTimeoutSeconds)
                return done(step)
            }
        }

        banner("LISTEN")
        return listen()
    }

    // ---- step 1: open the WebSocket ----

    fun open(): Boolean {
        ws.connect()
        return when (val e = ws.poll(cfg.helloTimeoutSeconds)) {
            is WsClient.Event.Open -> { log.info("WebSocket open"); true }
            is WsClient.Event.Failed -> {
                log.error("WS connect failed: {}", e.t.message, e.t)
                e.response?.let { log.error("HTTP response: {} {}", it.code, it.message) }
                false
            }
            null -> {
                log.error("No WS open within {}s (TLS may have connected but the server never " +
                    "completed the /wsnew upgrade — check the endpoint is served on this port)",
                    cfg.helloTimeoutSeconds)
                false
            }
            else -> { log.error("Unexpected pre-open event: {}", e); false }
        }
    }

    // ---- step 2: hello handshake ----

    fun hello(): HelloOutcome {
        log.info("-> HelloClient uuid={} domain={} hmac={}",
            cfg.uuid, cfg.accountDomain, if (cfg.useHmac) "yes" else "OFF (escape hatch)")
        ws.send(Envelopes.helloClient(cfg))

        val hs = awaitMessage(cfg.helloTimeoutSeconds) { (it as? Envelopes.Inbound.HelloServer)?.msg }
        if (hs == null) {
            log.error("Timed out after {}s waiting for HelloServer", cfg.helloTimeoutSeconds)
            return HelloOutcome.Timeout
        }
        log.info("<- HelloServer accepted={} serverUuid={}{}",
            hs.connectionAccepted, hs.uuid,
            if (!hs.connectionAccepted) " rejectionReason=\"${hs.rejectionReason}\"" else "")
        log.debug("   HelloServer fields: uuid={} accepted={} hasSinglePort={} rejectionReason={}",
            if (hs.hasUuid()) hs.uuid else "<none>",
            hs.connectionAccepted,
            if (hs.hasHasSinglePort()) hs.hasSinglePort else "<none>",
            if (hs.hasRejectionReason()) "\"${hs.rejectionReason}\"" else "<none>")
        return if (hs.connectionAccepted) HelloOutcome.Accepted(hs)
        else HelloOutcome.Rejected(hs.rejectionReason)
    }

    // ---- step 3: user session ----

    fun session(): SessionOutcome {
        val jwt = cfg.jwt
        if (jwt == null) {
            log.info("No JWT configured; skipping InitiateUserSession")
            return SessionOutcome.Skipped
        }
        val (reqId, env) = Envelopes.initiateUserSession(cfg, jwt)
        log.info("-> InitiateUserSession requestId={} jwt={}", reqId, jwt.masked())
        ws.send(env)
        val resp = awaitMessage(cfg.helloTimeoutSeconds) { (it as? Envelopes.Inbound.SessionResponse)?.msg }
        return when {
            resp == null -> { log.warn("No InitiateUserSessionResponse within timeout"); SessionOutcome.Timeout }
            resp.hasSessionCookie() -> {
                log.info("<- InitiateUserSessionResponse cookie={}", resp.sessionCookie.masked())
                SessionOutcome.Cookie(resp.sessionCookie)
            }
            else -> {
                log.warn("<- InitiateUserSessionResponse error=\"{}\"", resp.errorMessage)
                SessionOutcome.Error(resp.errorMessage)
            }
        }
    }

    // ---- step 4: remote delivery / add document ----

    fun sendAddDocument(cookie: String?) {
        log.info("-> RemoteDelivery target={} AddDocument(uuid={}, providerId={}) cookie={}",
            cfg.rd.targetActorPath, cfg.rd.documentUuid, cfg.rd.providerId,
            cookie?.masked() ?: "<none>")
        ws.send(Envelopes.remoteDeliveryAddDocument(cfg, cookie))
    }

    // ---- step 5: listen for server-pushed messages ----

    fun listen(): Int {
        log.info("Connected. Waiting for server messages (Ctrl-C to exit)...")
        while (running) {
            when (val e = ws.poll(1)) {
                is WsClient.Event.Message -> logInbound(e.parsed)
                is WsClient.Event.Closed -> {
                    log.info("Server closed connection: code={} reason=\"{}\"", e.code, e.reason); return Exit.OK
                }
                is WsClient.Event.Failed -> { log.error("WS failure: {}", e.t.message, e.t); return Exit.CONNECT }
                is WsClient.Event.Open, null -> {}
            }
        }
        return Exit.OK
    }

    /** Bounded receive window: log everything that arrives within [seconds], then return. */
    private fun drain(seconds: Long) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (running && System.nanoTime() < deadline) {
            when (val e = ws.poll(1)) {
                is WsClient.Event.Message -> logInbound(e.parsed)
                is WsClient.Event.Closed -> { log.info("Server closed: code={} reason=\"{}\"", e.code, e.reason); return }
                is WsClient.Event.Failed -> { log.error("WS failure: {}", e.t.message); return }
                is WsClient.Event.Open, null -> {}
            }
        }
    }

    // ---- shared helpers ----

    private fun done(step: Step): Int {
        log.info("Step '{}' complete; stopping.", step.cliName)
        return Exit.OK
    }

    private fun banner(name: String) = log.info("========== STEP: {} ==========", name)

    private fun logInbound(inbound: Envelopes.Inbound) {
        when (inbound) {
            is Envelopes.Inbound.PrintLocal -> {
                val m = inbound.msg
                val portName = if (m.hasOutputPort() && m.outputPort.hasName()) m.outputPort.name else "?"
                log.info("<- PrintLocalDocument documentId={} outputPortId={} outputPort.name={}",
                    m.documentId, m.outputPortId, portName)
            }
            is Envelopes.Inbound.LocalJobStored ->
                log.info("<- LocalJobStored documentId={} name={} inputPortId={}",
                    inbound.msg.documentId, inbound.msg.documentName, inbound.msg.inputPortId)
            is Envelopes.Inbound.HelloServer ->
                log.info("<- (late) HelloServer accepted={}", inbound.msg.connectionAccepted)
            is Envelopes.Inbound.SessionResponse ->
                log.info("<- (late) InitiateUserSessionResponse requestId={}", inbound.msg.requestId)
            is Envelopes.Inbound.Other ->
                log.info("<- {}", Envelopes.summarize(inbound.envelope))
            is Envelopes.Inbound.Undecodable -> {
                log.warn("<- undecodable frame ({} bytes): {}", inbound.bytes.size, inbound.error.message)
                log.warn("   hex: {}", Hex.preview(inbound.bytes))
            }
        }
    }

    private fun <T> awaitMessage(timeoutSeconds: Long, extract: (Envelopes.Inbound) -> T?): T? {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000_000L).coerceAtLeast(1)
            when (val e = ws.poll(remaining)) {
                is WsClient.Event.Message -> {
                    val v = extract(e.parsed)
                    if (v != null) return v
                    logInbound(e.parsed) // unrelated message: log and keep waiting
                }
                is WsClient.Event.Failed -> { log.error("WS failure: {}", e.t.message); return null }
                is WsClient.Event.Closed -> { log.warn("Closed while waiting: {}", e.reason); return null }
                is WsClient.Event.Open, null -> {}
            }
        }
        return null
    }
}
