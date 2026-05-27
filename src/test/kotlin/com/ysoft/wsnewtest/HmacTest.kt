package com.ysoft.wsnewtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HmacTest {

    @Test
    fun `matches independent openssl reference`() {
        // Reference computed with:
        //   printf '%s' 'abc|2026-01-01T00:00:00Z|dom' | openssl dgst -sha256 -hmac 'testkey'
        val expected = "0937ddffa2b8387db5f4a297724a67cc2bdbe6d8ad580b4afec9decfc7980fb1"
        val actual = Hmac.hex(
            apiKey = "testkey",
            uuid = "abc",
            timestamp = "2026-01-01T00:00:00Z",
            accountDomain = "dom",
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `output is lower-case 64-char hex`() {
        val sig = Hmac.hex("k", "u", "t", "d")
        assertEquals(64, sig.length)
        assertTrue(sig.all { it in "0123456789abcdef" }, "expected lower-case hex, got: $sig")
    }
}
