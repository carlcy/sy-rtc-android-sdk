package com.sy.rtc.sdk

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * 通话中录音：不另开麦克风，直接用 WebRTC 已有的 PCM（本端采集轨、各远端解码轨的 AudioTrackSink），
 * 混成单声道后写 AAC（.m4a）或 WAV。混音线程按真实时间出样，某一路没数据时补静音。
 */
internal class CallAudioRecorder(
    private val file: File,
    private val format: RecordingFormat,
    private val sampleRate: Int,
    private val bitrate: Int,
) {
    private val mixer = PcmMixer(maxBufferedSamples = sampleRate) // 每路最多缓存 1 秒
    @Volatile private var running = false
    private var thread: Thread? = null
    private var writer: Writer? = null

    /** 由 AudioTrackSink 线程调用。 */
    fun push(sourceId: String, pcm16: ByteArray, sourceRate: Int, channels: Int) {
        if (!running) return
        val mono = PcmConvert.toMono(pcm16, channels)
        mixer.push(sourceId, PcmConvert.resample(mono, sourceRate, sampleRate))
    }

    fun removeSource(sourceId: String) = mixer.removeSource(sourceId)

    fun start() {
        file.parentFile?.mkdirs()
        writer = when (format) {
            RecordingFormat.WAV -> WavWriter(file, sampleRate)
            RecordingFormat.AAC_M4A -> AacWriter(file, sampleRate, bitrate)
        }
        running = true
        thread = Thread({ loop() }, "sy-call-recorder").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
        try { writer?.finish() } catch (e: Exception) { Log.w(TAG, "录音收尾失败", e) }
        writer = null
    }

    private fun loop() {
        val startNs = System.nanoTime()
        var written = 0L
        while (running) {
            try { Thread.sleep(TICK_MS) } catch (_: InterruptedException) { break }
            val due = (System.nanoTime() - startNs) * sampleRate / 1_000_000_000L - written
            if (due <= 0) continue
            val n = due.coerceAtMost(sampleRate.toLong()).toInt()
            try {
                writer?.write(mixer.mix(n))
            } catch (e: Exception) {
                Log.e(TAG, "写录音失败", e)
                running = false
            }
            written += due
        }
    }

    private interface Writer {
        fun write(samples: ShortArray)
        fun finish()
    }

    private class WavWriter(file: File, private val rate: Int) : Writer {
        private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(WavHeader.build(0, rate, 1)) }
        private var dataBytes = 0
        override fun write(samples: ShortArray) {
            raf.write(PcmConvert.toLittleEndian(samples))
            dataBytes += samples.size * 2
        }
        override fun finish() {
            raf.seek(0)
            raf.write(WavHeader.build(dataBytes, rate, 1))
            raf.close()
        }
    }

    private class AacWriter(file: File, private val rate: Int, bitrate: Int) : Writer {
        private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var muxing = false
        private var ptsUs = 0L

        init {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        override fun write(samples: ShortArray) {
            val bytes = PcmConvert.toLittleEndian(samples)
            var offset = 0
            while (offset < bytes.size) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx < 0) { drain(false); continue }
                val buf = codec.getInputBuffer(idx) ?: continue
                buf.clear()
                val len = minOf(buf.remaining(), bytes.size - offset)
                buf.put(bytes, offset, len)
                codec.queueInputBuffer(idx, 0, len, ptsUs, 0)
                ptsUs += len / 2 * 1_000_000L / rate
                offset += len
                drain(false)
            }
        }

        override fun finish() {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            codec.stop(); codec.release()
            if (muxing) muxer.stop()
            muxer.release()
        }

        private fun drain(endOfStream: Boolean) {
            var spins = 0
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream || ++spins > 100) return
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    idx >= 0 -> {
                        val out = codec.getOutputBuffer(idx)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (out != null && muxing && !isConfig && info.size > 0) muxer.writeSampleData(track, out, info)
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "CallAudioRecorder"
        private const val TICK_MS = 20L
        const val LOCAL_SOURCE = "__local__"
    }
}
