package com.sy.rtc.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 与 iOS SyRtcTokenExpiryDedupeTests 相同的期望。 */
class TokenExpiryDedupeTest {
    private val warn = TokenExpiryDedupe.Kind.WILL_EXPIRE
    private val expired = TokenExpiryDedupe.Kind.EXPIRED

    @Test
    fun localAndServerFireOncePerToken() {
        val d = TokenExpiryDedupe()
        d.reset(1000)
        assertTrue(d.shouldFire(warn))             // 本地定时器
        assertFalse(d.shouldFire(warn, 1000))      // 服务端推送同一 Token
        assertTrue(d.shouldFire(expired, 1000))    // 服务端先到
        assertFalse(d.shouldFire(expired))         // 本地定时器迟到
        assertFalse(d.shouldFire(warn))            // 过期后不补提醒
    }

    @Test
    fun renewResetsAndStalePushIgnored() {
        val d = TokenExpiryDedupe()
        d.reset(1000)
        assertTrue(d.shouldFire(expired))
        d.reset(2000)                              // renewToken
        assertFalse(d.shouldFire(warn, 1000))      // 旧连接迟到的推送
        assertTrue(d.shouldFire(warn, 2000))
        assertTrue(d.shouldFire(expired))
    }

    @Test
    fun tokenWithoutExpiryStillDedupesServerPush() {
        val d = TokenExpiryDedupe()
        d.reset(null)
        assertTrue(d.shouldFire(warn, 1500))
        assertFalse(d.shouldFire(warn, 1500))
    }

    @Test
    fun parsesExpireAtFromPush() {
        assertEquals(1790000000L, TokenExpiryDedupe.expireAtOf(mapOf("expireAt" to 1.79e9)))
        assertEquals(42L, TokenExpiryDedupe.expireAtOf(mapOf("expireAt" to "42")))
        assertNull(TokenExpiryDedupe.expireAtOf(mapOf("expireAt" to 0)))
        assertNull(TokenExpiryDedupe.expireAtOf(emptyMap()))
    }
}
