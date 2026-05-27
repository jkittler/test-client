package com.ysoft.wsnewtest

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.File

/** Resolved client configuration. */
data class ClientConfig(
    val host: String,
    val port: Int,
    val path: String,
    val tls: Boolean,
    val trustAllCerts: Boolean,
    val protocolVersion: Int,
    val applicationName: String,
    val useHmac: Boolean,
    val helloTimeoutSeconds: Long,
    val uuid: String,
    val accountDomain: String,
    val apiKey: String,
    val jwt: String?,
    val sendRemoteDelivery: Boolean,
    val rd: RemoteDeliveryConfig,
) {
    val wsUrl: String get() = "${if (tls) "wss" else "ws"}://$host:$port$path"

    /** Multi-line, secret-masked dump for DEBUG logging. */
    fun describe(): String = """
        |resolved config:
        |  wsUrl=$wsUrl  tls=$tls trustAllCerts=$trustAllCerts helloTimeoutSeconds=$helloTimeoutSeconds
        |  protocolVersion=$protocolVersion applicationName=$applicationName
        |  uuid=$uuid accountDomain=$accountDomain
        |  useHmac=$useHmac apiKey=${apiKey.masked()} jwt=${jwt.masked()}
        |  sendRemoteDelivery=$sendRemoteDelivery
        |  remoteDelivery: targetHost=${rd.targetHost} targetActorPath=${rd.targetActorPath} providerId=${rd.providerId}
        |                  document.uuid=${rd.documentUuid} document.name=${rd.documentName}
    """.trimMargin()

    data class RemoteDeliveryConfig(
        val targetHost: String,
        val targetActorPath: String,
        val providerId: Int,
        val documentUuid: String,
        val documentName: String,
        val inputPortName: String,
    )

    companion object {
        /**
         * Load defaults from classpath application.conf, then overlay
         * ./application.local.conf (working dir) if present.
         */
        fun load(localFile: String = "application.local.conf"): ClientConfig {
            val base = ConfigFactory.load()
            val local = File(localFile)
            val merged: Config =
                if (local.isFile) ConfigFactory.parseFile(local).withFallback(base).resolve()
                else base
            val c = merged.getConfig("wsnew")

            fun reqStr(key: String): String {
                val v = c.getString(key)
                require(v.isNotBlank()) { "Missing required config: wsnew.$key (set it in $localFile)" }
                return v
            }

            val rd = c.getConfig("remoteDelivery")
            return ClientConfig(
                host = c.getString("host"),
                port = c.getInt("port"),
                path = c.getString("path"),
                tls = c.getBoolean("tls"),
                trustAllCerts = c.getBoolean("trustAllCerts"),
                protocolVersion = c.getInt("protocolVersion"),
                applicationName = c.getString("applicationName"),
                useHmac = c.getBoolean("useHmac"),
                helloTimeoutSeconds = c.getLong("helloTimeoutSeconds"),
                uuid = reqStr("uuid"),
                accountDomain = reqStr("accountDomain"),
                apiKey = if (c.getBoolean("useHmac")) reqStr("apiKey") else c.getString("apiKey"),
                jwt = c.getString("jwt").ifBlank { null },
                sendRemoteDelivery = c.getBoolean("sendRemoteDelivery"),
                rd = RemoteDeliveryConfig(
                    targetHost = rd.getString("targetHost"),
                    targetActorPath = rd.getString("targetActorPath"),
                    providerId = rd.getInt("providerId"),
                    documentUuid = rd.getString("document.uuid"),
                    documentName = rd.getString("document.name"),
                    inputPortName = rd.getString("document.inputPortName"),
                ),
            )
        }
    }
}
