package com.sy.rtc.sdk

/**
 * 通话录音的 PCM 处理（纯 JVM，可单测）。与 iOS `SyRtcPcmMixer` 规则相同。
 *
 * - 每个声源（本端麦克风、每个远端）各一条队列，先转单声道、重采样到录音采样率再入队。
 * - 混音线程按真实时间取样：每个声源取 n 个样本（不足补 0），相加并限幅到 16 bit。
 * - 单个声源最多缓存 [maxBufferedSamples] 个样本，超出丢弃最旧的，避免某一路卡住后延迟越积越大。
 */
object PcmConvert {
    /** 16 bit 小端交错 PCM → 单声道（各声道取平均）。 */
    fun toMono(bytes: ByteArray, channels: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = bytes.size / 2 / ch
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until ch) {
                val i = (f * ch + c) * 2
                sum += (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
            }
            out[f] = (sum / ch).toShort()
        }
        return out
    }

    /** 线性插值重采样。 */
    fun resample(src: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate || src.isEmpty()) return src
        val outLen = ((src.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = pos - i0
            out[i] = (src[i0] + (src[i1] - src[i0]) * frac).toInt().toShort()
        }
        return out
    }

    fun toLittleEndian(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}

class PcmMixer(private val maxBufferedSamples: Int) {
    private class Queue(capacity: Int) {
        var data = ShortArray(capacity)
        var size = 0
    }

    private val sources = LinkedHashMap<String, Queue>()

    @Synchronized
    fun push(sourceId: String, samples: ShortArray) {
        if (samples.isEmpty()) return
        val q = sources.getOrPut(sourceId) { Queue(maxBufferedSamples) }
        var incoming = samples
        if (incoming.size >= maxBufferedSamples) {
            incoming = incoming.copyOfRange(incoming.size - maxBufferedSamples, incoming.size)
            q.size = 0
        }
        val overflow = q.size + incoming.size - maxBufferedSamples
        if (overflow > 0) {
            System.arraycopy(q.data, overflow, q.data, 0, q.size - overflow)
            q.size -= overflow
        }
        System.arraycopy(incoming, 0, q.data, q.size, incoming.size)
        q.size += incoming.size
    }

    @Synchronized
    fun removeSource(sourceId: String) {
        sources.remove(sourceId)
    }

    @Synchronized
    fun bufferedSamples(sourceId: String): Int = sources[sourceId]?.size ?: 0

    /** 取出 n 个混音后的样本；某一路不足时这一路补 0。 */
    @Synchronized
    fun mix(n: Int): ShortArray {
        val acc = IntArray(n)
        for (q in sources.values) {
            val take = minOf(n, q.size)
            for (i in 0 until take) acc[i] += q.data[i].toInt()
            System.arraycopy(q.data, take, q.data, 0, q.size - take)
            q.size -= take
        }
        return ShortArray(n) { acc[it].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }
}

/** 44 字节 WAV（RIFF / PCM 16 bit）头。 */
object WavHeader {
    fun build(dataBytes: Int, sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)); b.putInt(36 + dataBytes)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)); b.putInt(16); b.putShort(1); b.putShort(channels.toShort())
        b.putInt(sampleRate); b.putInt(byteRate); b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)); b.putInt(dataBytes)
        return b.array()
    }
}

/** 录音格式。`mp3` 不支持（此前 Android 的 mp3 实际输出 AMR-NB）。 */
enum class RecordingFormat { AAC_M4A, WAV;
    companion object {
        /** `aac` / `aaclc` / `m4a` → AAC_M4A，`wav` / `pcm` → WAV，其余（含 mp3）→ null。 */
        fun fromCodec(codec: String): RecordingFormat? = when (codec.trim().lowercase()) {
            "aac", "aaclc", "aac_lc", "m4a" -> AAC_M4A
            "wav", "pcm" -> WAV
            else -> null
        }
    }
}
