package com.ysoft.wsnewtest

import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fetches a user access token (JWT) from the HCP public API login endpoint:
 *   GET https://<host>:<port>/api/v1/login?authtype=0&userid=<u>&password=<p>
 *   X-API-Key: <apiKey>
 * so testing never requires pasting a token by hand.
 */
object Login {
    private val log = LoggerFactory.getLogger(Login::class.java)
    private val ACCESS_TOKEN = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"")

    fun fetchAccessToken(cfg: ClientConfig.LoginConfig, trustAllCerts: Boolean): String {
        val url = "https://${cfg.host}:${cfg.port}/api/v1/login" +
            "?authtype=0&userid=${enc(cfg.userId)}&password=${enc(cfg.password)}"
        log.info("Logging in as '{}' @ https://{}:{} ...", cfg.userId, cfg.host, cfg.port)

        val client = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .apply { if (trustAllCerts) Tls.applyTrustAll(this) }
            .build()
        val req = Request.Builder().url(url).header("X-API-Key", cfg.apiKey).get().build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            require(resp.isSuccessful) { "login failed: HTTP ${resp.code} ${resp.message}" }
            val token = ACCESS_TOKEN.find(body)?.groupValues?.get(1)
                ?: error("login response had no access_token")
            log.info("Obtained access_token ({} chars)", token.length)
            return token
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
