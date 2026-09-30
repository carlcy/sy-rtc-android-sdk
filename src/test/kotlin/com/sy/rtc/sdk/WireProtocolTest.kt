package com.sy.rtc.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 与 iOS SyRtcEngineImpl 使用同一套线格式，改动需三端同步。 */
class WireProtocolTest {

    @Test
    fun streamExtraUsesTheIosPrefix() {
        val wire = WireProtocol.encodeStreamExtra("座位:1")
        assertEquals("sy-extra:座位:1", wire)
        assertEquals("座位:1", WireProtocol.decodeStreamExtra(wire))
        assertEquals("", WireProtocol.decodeStreamExtra("sy-extra:"))
        assertNull(WireProtocol.decodeStreamExtra("hello"))
        assertTrue(WireProtocol.isReservedChannelMessage(wire))
        assertFalse(WireProtocol.isReservedChannelMessage("hello"))
    }

    @Test
    fun legacyAndroidEnvelopesAreStillReserved() {
        assertTrue(WireProtocol.isReservedChannelMessage(StreamExtra.encode("u1", "x")))
        assertTrue(WireProtocol.isReservedChannelMessage(ClientMuteNotice.encode("u1", "audio", true)))
    }

    @Test
    fun userMediaParsesOptionalFlags() {
        assertEquals(true to null, WireProtocol.decodeUserMedia(mapOf("uid" to "u2", "audioMuted" to true)))
        assertEquals(null to false, WireProtocol.decodeUserMedia(mapOf("videoMuted" to false)))
        assertEquals(true to false, WireProtocol.decodeUserMedia(mapOf("audioMuted" to 1, "videoMuted" to "false")))
        assertEquals(null to null, WireProtocol.decodeUserMedia(emptyMap()))
        assertEquals("user-media", WireProtocol.USER_MEDIA_TYPE)
        assertEquals(1024, WireProtocol.MAX_STREAM_EXTRA_BYTES)
    }

    @Test
    fun networkTypeNamesMatchIos() {
        assertEquals("none", NetworkTypes.classify(false, true, false, false))
        assertEquals("wifi", NetworkTypes.classify(true, true, true, false))
        assertEquals("ethernet", NetworkTypes.classify(true, false, false, true))
        assertEquals("cellular", NetworkTypes.classify(true, false, true, false))
        assertEquals("unknown", NetworkTypes.classify(true, false, false, false))
    }
}
