package com.sy.rtc.sdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClientSignalsTest {
    @Test
    fun versionMatchesFile() {
        assertEquals("3.2.0", SdkInfo.VERSION)
        val file = File("VERSION")
        assertTrue(file.isFile)
        assertEquals(SdkInfo.VERSION, file.readText().trim())
    }

    @Test
    fun networkQualityUsesSamplesOnly() {
        assertEquals(NetworkQualityEstimator.UNKNOWN, NetworkQualityEstimator.fromRttAndLoss(null, null))
        assertEquals(0, NetworkQualityEstimator.toRank(NetworkQualityEstimator.UNKNOWN))
        assertEquals(1, NetworkQualityEstimator.toRank(NetworkQualityEstimator.EXCELLENT))
        assertEquals(5, NetworkQualityEstimator.toRank("die")) // 旧名仍能比较
    }

    /** 与 iOS SyRtcNetworkQualityTests 同一张表，两端必须一致。 */
    @Test
    fun networkQualityThresholdsMatchIos() {
        val cases = listOf(
            // rttMs, lossRate(0-1), expected
            Triple(20.0, 0.0, "excellent"),
            Triple(99.0, 0.009, "excellent"),
            Triple(100.0, 0.0, "good"),
            Triple(20.0, 0.01, "good"),
            Triple(199.0, 0.029, "good"),
            Triple(200.0, 0.0, "poor"),
            Triple(20.0, 0.03, "poor"),
            Triple(400.0, 0.0, "bad"),
            Triple(20.0, 0.08, "bad"),
            Triple(800.0, 0.0, "down"),
            Triple(20.0, 0.20, "down"),
            Triple(null, 0.5, "down"),
            Triple(150.0, null, "good"),
        )
        for ((rtt, loss, expected) in cases) {
            assertEquals("rtt=$rtt loss=$loss", expected, NetworkQualityEstimator.fromRttAndLossRate(rtt, loss))
        }
        assertEquals("poor", NetworkQualityEstimator.fromRttAndLoss(20, 3.0))
        @Suppress("DEPRECATION")
        assertEquals(NetworkQualityEstimator.POOR, NetworkQualityEstimator.MEDIUM)
    }

    @Test
    fun statsParserReadsSelectedPairAndAudio() {
        val sample = StatsParser.parse(
            listOf(
                StatRecord(
                    "candidate-pair",
                    mapOf(
                        "nominated" to false,
                        "state" to "failed",
                        "currentRoundTripTime" to 9.0
                    )
                ),
                StatRecord(
                    "candidate-pair",
                    mapOf(
                        "nominated" to true,
                        "state" to "succeeded",
                        "currentRoundTripTime" to 0.18
                    )
                ),
                StatRecord(
                    "remote-inbound-rtp",
                    mapOf("fractionLost" to 0.04)
                ),
                StatRecord(
                    "inbound-rtp",
                    mapOf(
                        "kind" to "audio",
                        "audioLevel" to 0.5,
                        "bytesReceived" to 1000
                    )
                ),
                StatRecord(
                    "outbound-rtp",
                    mapOf("kind" to "video", "bytesSent" to 4000)
                )
            )
        )
        assertEquals(180, sample.rttMs)
        assertEquals(4.0, sample.lossPercent!!, 0.001)
        assertEquals(0.5, sample.audioLevel!!, 0.001)
        assertEquals(4000L, sample.bytesSent)
        assertEquals(1000L, sample.bytesReceived)
        assertEquals(
            NetworkQualityEstimator.POOR,
            NetworkQualityEstimator.fromRttAndLoss(sample.rttMs, sample.lossPercent)
        )
    }

    @Test
    fun statsParserWithoutSamplesStaysEmpty() {
        val sample = StatsParser.parse(listOf(StatRecord("codec", emptyMap())))
        assertNull(sample.rttMs)
        assertNull(sample.lossPercent)
        assertNull(sample.bytesSent)
        assertNull(sample.audioLevel)
    }

    @Test
    fun bitrateNeedsTwoSamples() {
        assertNull(Bitrate.bps(null, null, 1000L, 2_000L))
        assertEquals(8000L, Bitrate.bps(1000L, 1_000L, 2000L, 2_000L))
        assertNull(Bitrate.bps(2000L, 1_000L, 1000L, 2_000L))
    }

    @Test
    fun pcmSilenceAndFullScale() {
        assertEquals(0, VolumeMeter.pcm16LeRms(ByteArray(8)))
        val full = ByteArray(4)
        full[0] = 0xFF.toByte()
        full[1] = 0x7F
        full[2] = 0xFF.toByte()
        full[3] = 0x7F
        assertEquals(255, VolumeMeter.pcm16LeRms(full))
        assertEquals(128, VolumeMeter.fromUnitInterval(0.5))
        assertEquals(50, VolumeMeter.smooth(0, 100, 2))
    }

    @Test
    fun reconnectDoesNotRejoinUntilLoss() {
        val tracker = ReconnectTracker(maxAttempts = 3)
        assertEquals(RejoinSignal.JOINED, tracker.onConnected())
        assertEquals(RejoinSignal.NONE, tracker.onConnected())
        val first = tracker.onTransportLost()
        assertTrue(first.shouldRetry)
        assertEquals("reconnecting", first.state)
        assertEquals(RejoinSignal.REJOINED, tracker.onConnected())
        assertEquals(RejoinSignal.NONE, tracker.onConnected())
        repeat(3) { tracker.onTransportLost() }
        val failed = tracker.onTransportLost()
        assertFalse(failed.shouldRetry)
        assertEquals("failed", failed.state)
    }

    @Test
    fun firstConnectWinsOverPendingLoss() {
        val tracker = ReconnectTracker()
        tracker.onTransportLost()
        assertEquals(RejoinSignal.JOINED, tracker.onConnected())
        assertEquals(RejoinSignal.NONE, tracker.onConnected())
    }

    @Test
    fun streamExtraAndClientMuteRoundTrip() {
        val extra = StreamExtra.encode("u 1", "hello \"room\"")
        val decoded = StreamExtra.decode(extra)
        assertEquals("u 1", decoded?.uid)
        assertEquals("hello \"room\"", decoded?.extra)
        assertNull(StreamExtra.decode("not-json"))
        assertNull(StreamExtra.decode(ClientMuteNotice.encode("u", "audio", true)))

        val mute = ClientMuteNotice.decode(ClientMuteNotice.encode("remote", "video", false))
        assertEquals("remote", mute?.uid)
        assertEquals("video", mute?.media)
        assertEquals(false, mute?.muted)
    }

    @Test
    fun seiFramingIsPrefixOnly() {
        val wrapped = DataFrame.wrapSei(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), DataFrame.unwrapSei(wrapped))
        assertNull(DataFrame.unwrapSei(byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(7, DataFrame.streamIdFromLabel("sy-7"))
        assertNull(DataFrame.streamIdFromLabel("data"))
    }

    @Test
    fun audioRouteMatchesZegoInts() {
        assertEquals(AudioRoute.SPEAKER, AudioRoute.resolve(speakerOn = true, wiredHeadset = true, bluetooth = true))
        assertEquals(AudioRoute.BLUETOOTH, AudioRoute.resolve(speakerOn = false, wiredHeadset = true, bluetooth = true))
        assertEquals(AudioRoute.HEADPHONE, AudioRoute.resolve(speakerOn = false, wiredHeadset = true, bluetooth = false))
        assertEquals(AudioRoute.EARPIECE, AudioRoute.resolve(speakerOn = false, wiredHeadset = false, bluetooth = false))
        assertEquals(0, AudioRoute.SPEAKER)
        assertEquals(3, AudioRoute.EARPIECE)
    }

    @Test
    fun lighteningRaisesLumaAndZeroIsCopy() {
        val y = byteArrayOf(100, 200.toByte())
        val same = BeautyMath.applyLightening(y, 0f)
        assertArrayEquals(y, same)
        assertFalse(same === y)
        val brighter = BeautyMath.applyLightening(byteArrayOf(100), 1f)
        assertTrue((brighter[0].toInt() and 0xFF) > 100)
    }
}
