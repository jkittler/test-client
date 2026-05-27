package com.ysoft.wsnewtest

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hmac {
    /**
     * Mirrors HelloHandler.computeHmac on the server:
     *   hex( HmacSHA256( apiKey, "uuid|timestamp|accountDomain" ) )
     * lower-case hex, matching Apache commons-codec Hex.encodeHexString.
     */
    fun hex(apiKey: String, uuid: String, timestamp: String, accountDomain: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val data = "$uuid|$timestamp|$accountDomain"
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
