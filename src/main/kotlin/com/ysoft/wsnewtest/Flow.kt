package com.ysoft.wsnewtest

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
 * Drives the /wsnew protocol:
 *   hello -> (optional) user session -> (optional) remote delivery -> receive loop.
 * Returns a process exit code.
 */
class Flow(private val cfg: ClientConfig, private val ws: WsClient) {
    private val log = LoggerFactory.getLogger(Flow::class.java)

    @Volatile private var running = true

    fun stop() { running = false }

    fun run(): Int {
        log.debug("Flow start: connecting and awaiting WS open (timeout {}s)", cfg.helloTimeoutSeconds)
        ws.connect()

        // 1. wait for socket open (or immediate failure)
        when (val e = waitOpenOrFailure()) {
            is WsClient.Event.Open -> {}
            is WsClient.Event.Failed -> {
                log.error("WS connect failed: {}", e.t.message, e.t)
                e.response?.let { log.error("HTTP response: {} {}", it.code, it.message) }
                return Exit.CONNECT
            }
            else -> return Exit.CONNECT
        }

        // 2. HelloClient -> HelloServer
        log.info("-> HelloClient uuid={} domain={} hmac={}",
            cfg.uuid, cfg.accountDomain,
            if (cfg.useHmac) "yes" else "OFF (escape hatch)")
        ws.send(Envelopes.helloClient(cfg))

        val hello = awaitHelloServer() ?: run {
            log.error("Timed out after {}s waiting for HelloServer", cfg.helloTimeoutSeconds)
            return Exit.HELLO_TIMEOUT
        }
        log.info("<- HelloServer accepted={} serverUuid={}{}",
            hello.connectionAccepted, hello.uuid,
            if (!hello.connectionAccepted) " rejectionReason=\"${hello.rejectionReason}\"" else "")
        log.debug("   HelloServer fields: uuid={} accepted={} hasSinglePort={} rejectionReason={}",
            if (hello.hasUuid()) hello.uuid else "<none>",
            hello.connectionAccepted,
            if (hello.hasHasSinglePort()) hello.hasSinglePort else "<none>",
            if (hello.hasRejectionReason()) "\"${hello.rejectionReason}\"" else "<none>")
        if (!hello.connectionAccepted) return Exit.REJECTED

        // 3. (optional) user session
        var sessionCookie: String? = null
        if (cfg.jwt != null) {
            val (reqId, env) = Envelopes.initiateUserSession(cfg, cfg.jwt)
            log.info("-> InitiateUserSession requestId={} jwt={}", reqId, truncate(cfg.jwt))
            ws.send(env)
            val resp = awaitSessionResponse()
            if (resp != null) {
                if (resp.hasSessionCookie()) {
                    sessionCookie = resp.sessionCookie
                    log.info("<- InitiateUserSessionResponse cookie={}", truncate(sessionCookie))
                } else {
                    log.warn("<- InitiateUserSessionResponse error=\"{}\" (continuing without cookie)",
                        resp.errorMessage)
                }
            } else {
                log.warn("No InitiateUserSessionResponse within timeout (continuing)")
            }
        } else {
            log.info("No JWT configured; skipping InitiateUserSession")
        }

        // 4. (optional) remote delivery / AddDocument
        if (cfg.sendRemoteDelivery) {
            log.info("-> RemoteDelivery target={} AddDocument(uuid={}, providerId={})",
                cfg.rd.targetActorPath, cfg.rd.documentUuid, cfg.rd.providerId)
            ws.send(Envelopes.remoteDeliveryAddDocument(cfg, sessionCookie))
        } else {
            log.info("sendRemoteDelivery=false; skipping RemoteDelivery/AddDocument")
        }

        // 5. receive loop
        log.info("Connected. Waiting for server messages (Ctrl-C to exit)...")
        return receiveLoop()
    }

    private fun receiveLoop(): Int {
        while (running) {
            val e = ws.poll(1) ?: continue
            when (e) {
                is WsClient.Event.Message -> logInbound(e.parsed)
                is WsClient.Event.Closed -> {
                    log.info("Server closed connection: code={} reason=\"{}\"", e.code, e.reason)
                    return Exit.OK
                }
                is WsClient.Event.Failed -> {
                    log.error("WS failure: {}", e.t.message, e.t)
                    return Exit.CONNECT
                }
                is WsClient.Event.Open -> {}
            }
        }
        return Exit.OK
    }

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
                log.info("<- envelope contentType={} (no handler)", inbound.envelope.contentTypeCase)
            is Envelopes.Inbound.Undecodable -> {
                log.warn("<- undecodable frame ({} bytes): {}", inbound.bytes.size, inbound.error.message)
                log.warn("   hex: {}", Hex.preview(inbound.bytes))
            }
        }
    }

    // --- event helpers ---

    private fun waitOpenOrFailure(): WsClient.Event =
        ws.poll(cfg.helloTimeoutSeconds) ?: WsClient.Event.Failed(
            RuntimeException("no WS open within ${cfg.helloTimeoutSeconds}s"), null)

    private fun awaitHelloServer() = awaitMessage(cfg.helloTimeoutSeconds) {
        (it as? Envelopes.Inbound.HelloServer)?.msg
    }

    private fun awaitSessionResponse() = awaitMessage(cfg.helloTimeoutSeconds) {
        (it as? Envelopes.Inbound.SessionResponse)?.msg
    }

    private fun <T> awaitMessage(timeoutSeconds: Long, extract: (Envelopes.Inbound) -> T?): T? {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000_000L).coerceAtLeast(1)
            when (val e = ws.poll(remaining)) {
                is WsClient.Event.Message -> {
                    val v = extract(e.parsed)
                    if (v != null) return v
                    logInbound(e.parsed) // log and keep waiting
                }
                is WsClient.Event.Failed -> { log.error("WS failure: {}", e.t.message); return null }
                is WsClient.Event.Closed -> { log.warn("Closed while waiting: {}", e.reason); return null }
                is WsClient.Event.Open, null -> {}
            }
        }
        return null
    }

    private fun truncate(s: String, keep: Int = 12): String =
        if (s.length <= keep) s else s.take(keep) + "…(${s.length} chars)"
}
