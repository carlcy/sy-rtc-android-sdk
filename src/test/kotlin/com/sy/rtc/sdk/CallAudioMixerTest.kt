package com.sy.rtc.sdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 与 iOS SyRtcPcmMixerTests 相同的期望。 */
class CallAudioMixerTest {
    @Test
    fun stereoDownmixAndResample() {
        // L=1000 R=3000 → 2000
        val stereo = PcmConvert.toLittleEndian(shortArrayOf(1000, 3000, -1000, -3000))
        assertArrayEquals(shortArrayOf(2000, -2000), PcmConvert.toMono(stereo, 2))
        assertEquals(480, PcmConvert.resample(ShortArray(960), 48000, 24000).size)
        assertArrayEquals(shortArrayOf(0, 66, 100), PcmConvert.resample(shortArrayOf(0, 100), 2, 3))
    }

    @Test
    fun mixesSumsClipsAndPadsMissingSource() {
        val m = PcmMixer(maxBufferedSamples = 100)
        m.push("local", shortArrayOf(100, 200, 30000))
        m.push("u2", shortArrayOf(1, 2, 30000, 7))
        assertArrayEquals(shortArrayOf(101, 202, Short.MAX_VALUE), m.mix(3))
        // local 已空补 0，u2 剩 7
        assertArrayEquals(shortArrayOf(7, 0), m.mix(2))
    }

    @Test
    fun boundedBufferDropsOldest() {
        val m = PcmMixer(maxBufferedSamples = 4)
        m.push("a", shortArrayOf(1, 2, 3))
        m.push("a", shortArrayOf(4, 5, 6))
        assertEquals(4, m.bufferedSamples("a"))
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), m.mix(4))
        m.push("a", ShortArray(10) { it.toShort() })
        assertArrayEquals(shortArrayOf(6, 7, 8, 9), m.mix(4))
    }

    @Test
    fun wavHeaderAndFormats() {
        val h = WavHeader.build(dataBytes = 3200, sampleRate = 16000, channels = 1)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(h, 8, 4, Charsets.US_ASCII))
        val bb = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, bb.getInt(24))
        assertEquals(32000, bb.getInt(28))
        assertEquals(3200, bb.getInt(40))
        assertEquals(RecordingFormat.AAC_M4A, RecordingFormat.fromCodec("aacLc"))
        assertEquals(RecordingFormat.WAV, RecordingFormat.fromCodec("WAV"))
        assertNull(RecordingFormat.fromCodec("mp3"))
    }
}
