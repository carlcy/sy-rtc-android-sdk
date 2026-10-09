package com.sy.rtc.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JoinCredentialsTest {
    private val wired = """{"token":"b.s","mediaWired":true,"sfuKind":"livekit","sfuUrl":"wss://h/livekit",
        |"sfuToken":"lk","sfuRoom":"APP__ch","sfuIdentity":"u1","sfuExpireAt":1791500000,"canPublish":false}""".trimMargin()

    @Test
    fun plainTokenHasNoSfu() {
        val c = JoinCredentials.parse("  body.sig ")
        assertEquals("body.sig", c.token)
        assertNull(c.sfu)
        assertNull(c.canPublish)
    }

    @Test
    fun metaWithMediaWiredUsesLiveKit() {
        val c = JoinCredentials.parse(wired)
        assertEquals("b.s", c.token)
        assertNotNull(c.sfu)
        val sfu = c.sfu!!
        assertEquals("wss://h/livekit", sfu.url)
        assertEquals("lk", sfu.token)
        assertEquals("APP__ch", sfu.room)
        assertEquals("u1", sfu.identity)
        assertEquals(1791500000L, sfu.expireAt)
        assertEquals(false, c.canPublish)
    }

    @Test
    fun envelopeIsUnwrapped() {
        val c = JoinCredentials.parse("""{"code":0,"data":$wired}""")
        assertEquals("b.s", c.token)
        assertEquals("lk", c.sfu?.token)
    }

    @Test
    fun registryOnlyNodeStaysOnMesh() {
        // mediaWired=false: node registered but no SFU plugged in
        val c = JoinCredentials.parse("""{"token":"b.s","mediaWired":false,"mediaNodeUrl":"http://n1"}""")
        assertEquals("b.s", c.token)
        assertNull(c.sfu)
    }

    @Test
    fun missingSfuTokenStaysOnMesh() {
        val c = JoinCredentials.parse("""{"token":"b.s","mediaWired":true,"sfuUrl":"wss://h"}""")
        assertNull(c.sfu)
    }

    @Test
    fun unknownSfuKindStaysOnMesh() {
        val c = JoinCredentials.parse("""{"token":"b.s","mediaWired":true,"sfuKind":"mediasoup","sfuUrl":"wss://h","sfuToken":"x"}""")
        assertNull(c.sfu)
    }

    @Test
    fun brokenJsonIsPassedThrough() {
        val c = JoinCredentials.parse("{not json")
        assertEquals("{not json", c.token)
        assertNull(c.sfu)
    }
}
