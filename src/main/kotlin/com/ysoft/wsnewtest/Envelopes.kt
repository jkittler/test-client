package com.ysoft.wsnewtest

import com.ysoft.wsnewtest.proto.Wsnew
import java.time.Instant
import java.util.UUID

/** Builders for outgoing envelopes and a sealed type for parsed inbound ones. */
object Envelopes {

    private fun base(cfg: ClientConfig): Wsnew.R_Envelope.Builder =
        Wsnew.R_Envelope.newBuilder()
            .setProtocolVersion(cfg.protocolVersion)
            .setApplicationName(cfg.applicationName)

    fun helloClient(cfg: ClientConfig): Wsnew.R_Envelope {
        val hello = Wsnew.R_HelloClient.newBuilder()
            .setUuid(cfg.uuid)
            .setAccountDomain(cfg.accountDomain)
        if (cfg.useHmac) {
            val ts = Instant.now().toString() // ISO-8601 / RFC3339 UTC
            hello.timestamp = ts
            hello.hmacSignature = Hmac.hex(cfg.apiKey, cfg.uuid, ts, cfg.accountDomain)
        }
        return base(cfg).setHelloClient(hello).build()
    }

    fun initiateUserSession(cfg: ClientConfig, jwt: String): Pair<String, Wsnew.R_Envelope> {
        val requestId = UUID.randomUUID().toString()
        val msg = Wsnew.R_InitiateUserSession.newBuilder()
            .setRequestId(requestId)
            .setUserToken(jwt)
            .build()
        return requestId to base(cfg).setInitiateUserSession(msg).build()
    }

    fun remoteDeliveryAddDocument(cfg: ClientConfig, sessionCookie: String?): Wsnew.R_Envelope {
        val doc = Wsnew.R_Document.newBuilder()
            .setDocumentId(cfg.rd.documentUuid)
            .setDocumentName(cfg.rd.documentName)
            .setDocumentType(Wsnew.R_Document.R_DocumentType.PDF)
            .setDocumentStatus(Wsnew.R_Document.R_DocumentStatus.STORED)
            .setInputPortName(cfg.rd.inputPortName)
            .setCreatedDate(System.currentTimeMillis())
            .build()
        val add = Wsnew.R_AddDocumentRequest.newBuilder()
            .setProviderId(cfg.rd.providerId)
            .setDocument(doc)
            .build()
        val mid = Wsnew.R_MidLevelRequest.newBuilder().setAddDocument(add).build()
        val rdm = Wsnew.R_RemoteDeliveryMessage.newBuilder().setMidLevelRequest(mid).build()
        val rd = Wsnew.R_RemoteDelivery.newBuilder()
            .setMessage(rdm)
            .setTargetHost(cfg.rd.targetHost)
            .setTargetActorPath(cfg.rd.targetActorPath)
            .setIsResponse(false)
        if (sessionCookie != null) rd.userSessionCookie = sessionCookie
        return base(cfg).setRemoteDelivery(rd).build()
    }

    /** Parsed inbound envelope, dispatched by content type. */
    sealed interface Inbound {
        data class HelloServer(val msg: Wsnew.R_HelloServer) : Inbound
        data class SessionResponse(val msg: Wsnew.R_InitiateUserSessionResponse) : Inbound
        data class PrintLocal(val msg: Wsnew.R_PrintLocalDocument) : Inbound
        data class LocalJobStored(val msg: Wsnew.R_LocalJobStored) : Inbound
        data class Other(val envelope: Wsnew.R_Envelope) : Inbound
        data class Undecodable(val bytes: ByteArray, val error: Exception) : Inbound
    }

    /** One-line human-readable summary of an envelope, for DEBUG logging. */
    fun summarize(env: Wsnew.R_Envelope): String = buildString {
        append("Envelope(v=").append(env.protocolVersion)
        append(", app=").append(env.applicationName)
        append(", ").append(env.contentTypeCase)
        when (env.contentTypeCase) {
            Wsnew.R_Envelope.ContentTypeCase.HELLOCLIENT -> {
                val h = env.helloClient
                append(" uuid=").append(h.uuid)
                append(" domain=").append(h.accountDomain)
                append(" hmac=").append(if (h.hasHmacSignature()) h.hmacSignature.masked(8) else "<none>")
                append(" ts=").append(if (h.hasTimestamp()) h.timestamp else "<none>")
            }
            Wsnew.R_Envelope.ContentTypeCase.HELLOSERVER -> {
                val h = env.helloServer
                append(" accepted=").append(h.connectionAccepted)
                append(" serverUuid=").append(h.uuid)
                if (h.hasRejectionReason()) append(" reject=\"").append(h.rejectionReason).append("\"")
            }
            Wsnew.R_Envelope.ContentTypeCase.INITIATEUSERSESSION ->
                append(" requestId=").append(env.initiateUserSession.requestId)
            Wsnew.R_Envelope.ContentTypeCase.INITIATEUSERSESSIONRESPONSE -> {
                val r = env.initiateUserSessionResponse
                append(" requestId=").append(r.requestId)
                append(if (r.hasSessionCookie()) " cookie=${r.sessionCookie.masked()}" else " error=\"${r.errorMessage}\"")
            }
            Wsnew.R_Envelope.ContentTypeCase.PRINTLOCALDOCUMENT -> {
                val p = env.printLocalDocument
                append(" docId=").append(p.documentId).append(" portId=").append(p.outputPortId)
            }
            Wsnew.R_Envelope.ContentTypeCase.REMOTEDELIVERY -> {
                val rd = env.remoteDelivery
                append(" target=").append(rd.targetActorPath)
                append(" cookie=").append(if (rd.hasUserSessionCookie()) rd.userSessionCookie.masked() else "<none>")
            }
            else -> {}
        }
        append(")")
    }

    fun parse(bytes: ByteArray): Inbound =
        try {
            val env = Wsnew.R_Envelope.parseFrom(bytes)
            when (env.contentTypeCase) {
                Wsnew.R_Envelope.ContentTypeCase.HELLOSERVER -> Inbound.HelloServer(env.helloServer)
                Wsnew.R_Envelope.ContentTypeCase.INITIATEUSERSESSIONRESPONSE ->
                    Inbound.SessionResponse(env.initiateUserSessionResponse)
                Wsnew.R_Envelope.ContentTypeCase.PRINTLOCALDOCUMENT -> Inbound.PrintLocal(env.printLocalDocument)
                Wsnew.R_Envelope.ContentTypeCase.LOCALJOBSTORED -> Inbound.LocalJobStored(env.localJobStored)
                else -> Inbound.Other(env)
            }
        } catch (e: Exception) {
            Inbound.Undecodable(bytes, e)
        }
}
