package com.ysoft.wsnewtest

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object Tls {
    /** Dev-only: trust every cert + skip hostname verification. Never use against production. */
    fun applyTrustAll(b: OkHttpClient.Builder): OkHttpClient.Builder {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        return b.sslSocketFactory(ctx.socketFactory, trustAll).hostnameVerifier { _, _ -> true }
    }
}
