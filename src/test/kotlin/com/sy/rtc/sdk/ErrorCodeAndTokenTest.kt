package com.sy.rtc.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorCodeAndTokenTest {
    private fun b64url(s: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(alphabet[b0 shr 2])
            sb.append(alphabet[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) sb.append(alphabet[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            if (b2 >= 0) sb.append(alphabet[b2 and 63])
            i += 3
        }
        return sb.toString()
    }

    @Test
    fun errorCodesMatchOtherPlatforms() {
        assertEquals(1000, RtcErrorCode.INVALID_ARGUMENT)
        assertEquals(1002, RtcErrorCode.SIGNALING)
        assertEquals(1003, RtcErrorCode.RECONNECT_FAILED)
        assertEquals(1004, RtcErrorCode.KICKED)
        assertEquals(1005, RtcErrorCode.CAMERA)
        assertEquals(1006, RtcErrorCode.SCREEN_SHARE)
        assertEquals(1007, RtcErrorCode.CUSTOM_CAPTURE)
        assertEquals(1009, RtcErrorCode.AUDIO_ROUTE)
        assertEquals(4031, RtcErrorCode.CREDENTIAL_SUSPENDED)
        assertEquals(4032, RtcErrorCode.CREDENTIAL_REVOKED)
        assertEquals(4033, RtcErrorCode.CREDENTIAL_EXPIRED)
    }

    @Test
    fun signalingFramesMapToCodes() {
        assertEquals(1004, RtcErrorCode.forKicked(mapOf("reason" to "spam")))
        assertEquals(4032, RtcErrorCode.forKicked(mapOf("reason" to "revoked", "code" to 4032.0)))
        assertEquals(1004, RtcErrorCode.forKicked(mapOf("code" to 403.0)))
        assertEquals(1002, RtcErrorCode.forSignalingError(mapOf("message" to "x")))
        assertEquals(1002, RtcErrorCode.forSignalingError(mapOf("code" to 400.0)))
        assertEquals(403, RtcErrorCode.forSignalingError(mapOf("code" to 403.0)))
        assertEquals(4031, RtcErrorCode.forSignalingError(mapOf("code" to 4031)))
        assertEquals("房间已锁定", RtcErrorCode.signalingErrorMessage(mapOf("message" to "房间已锁定")))
        assertEquals("old", RtcErrorCode.signalingErrorMessage(mapOf("error" to "old")))
        assertEquals("信令错误", RtcErrorCode.signalingErrorMessage(emptyMap()))
    }

    @Test
    fun parsesSyTokenExpireAt() {
        val payload = """{"appId":"a","channelId":"c","uid":"u","expireAt":1790000000,"tierWeight":1.5,"canPublish":true}"""
        assertEquals(1790000000L, TokenExpiry.expireAtSeconds(b64url(payload) + ".c2ln"))
    }

    @Test
    fun parsesJwtExp() {
        val jwt = b64url("""{"alg":"HS256"}""") + "." + b64url("""{"sub":"u","exp":1700000123}""") + ".sig"
        assertEquals(1700000123L, TokenExpiry.expireAtSeconds(jwt))
    }

    @Test
    fun rejectsTokensWithoutExpiry() {
        assertNull(TokenExpiry.expireAtSeconds("plain-token"))
        assertNull(TokenExpiry.expireAtSeconds(b64url("""{"expireAt":0}""") + ".s"))
        assertNull(TokenExpiry.expireAtSeconds("!!!.s"))
    }

    @Test
    fun warnsThirtySecondsBeforeExpiry() {
        val now = 1_000_000_000L
        assertEquals(70_000L to 100_000L, TokenExpiry.delaysMs(now / 1000 + 100, now))
        assertEquals(0L to 10_000L, TokenExpiry.delaysMs(now / 1000 + 10, now))
        assertEquals(0L to 0L, TokenExpiry.delaysMs(now / 1000 - 5, now))
    }
}
