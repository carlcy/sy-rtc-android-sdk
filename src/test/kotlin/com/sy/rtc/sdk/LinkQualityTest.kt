package com.sy.rtc.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 与 iOS SyRtcLinkQualityTests 相同的期望。 */
class LinkQualityTest {
    @Test
    fun txUsesRttAndOutboundLoss() {
        assertEquals("excellent", LinkQuality.tx(50, 0.0))
        assertEquals("poor", LinkQuality.tx(50, 0.05))
        assertEquals("bad", LinkQuality.tx(450, 0.0))
        assertEquals("good", LinkQuality.tx(150, null))
        assertEquals("unknown", LinkQuality.tx(null, null))
    }

    @Test
    fun rxUsesInboundLossAndJitter() {
        assertEquals("excellent", LinkQuality.rx(0.0, 10.0))
        assertEquals("good", LinkQuality.rx(0.0, 40.0))
        assertEquals("poor", LinkQuality.rx(0.0, 60.0))
        assertEquals("bad", LinkQuality.rx(0.1, 10.0))
        assertEquals("down", LinkQuality.rx(null, 250.0))
        assertEquals("unknown", LinkQuality.rx(null, null))
    }

    @Test
    fun intervalLossUsesDeltas() {
        assertEquals(0.1, LinkQuality.intervalLossRate(null, null, 10, 90)!!, 1e-9)
        assertEquals(0.5, LinkQuality.intervalLossRate(10, 90, 20, 100)!!, 1e-9)
        assertNull(LinkQuality.intervalLossRate(10, 90, 10, 90))
        assertEquals(0.0, LinkQuality.intervalLossRate(10, 90, 0, 50)!!, 1e-9) // 计数器重置
        assertNull(LinkQuality.intervalLossRate(null, null, null, 5))
    }

    @Test
    fun parserSplitsUplinkAndDownlink() {
        val s = StatsParser.parse(listOf(
            StatRecord("remote-inbound-rtp", mapOf("fractionLost" to 0.04, "roundTripTime" to 0.12)),
            StatRecord("remote-inbound-rtp", mapOf("fractionLost" to 0.02)),
            StatRecord("inbound-rtp", mapOf("kind" to "audio", "packetsLost" to 3, "packetsReceived" to 97, "jitter" to 0.02)),
            StatRecord("inbound-rtp", mapOf("kind" to "video", "packetsLost" to 1, "packetsReceived" to 99, "jitter" to 0.045)),
        ))
        assertEquals(0.04, s.outboundLossRate!!, 1e-9)
        assertEquals(4L, s.inboundPacketsLost)
        assertEquals(196L, s.inboundPacketsReceived)
        assertEquals(45.0, s.jitterMs!!, 1e-9)
        assertEquals(120, s.rttMs)
    }
}
