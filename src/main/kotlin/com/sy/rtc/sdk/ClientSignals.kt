package com.sy.rtc.sdk

/**
 * 端上可判定的质量、路由、重连和信令封装。
 *
 * 这些结果来自本机 WebRTC 统计、PCM 或信令消息，不是 SFU 探测，也不是码流内 SEI。
 */
object SdkInfo {
    const val VERSION = "3.2.0"
}

object AudioRoute {
    const val SPEAKER = 0
    const val HEADPHONE = 1
    const val BLUETOOTH = 2
    const val EARPIECE = 3

    fun resolve(speakerOn: Boolean, wiredHeadset: Boolean, bluetooth: Boolean): Int {
        if (bluetooth && !speakerOn) return BLUETOOTH
        if (wiredHeadset && !speakerOn) return HEADPHONE
        return if (speakerOn) SPEAKER else EARPIECE
    }
}

object NetworkQualityEstimator {
    const val UNKNOWN = "unknown"
    const val EXCELLENT = "excellent"
    const val GOOD = "good"
    const val MEDIUM = "medium"
    const val BAD = "bad"
    const val DIE = "die"

    /**
     * 没有 RTT 也没有丢包样本时返回 [UNKNOWN]，不把缺数据报成 excellent。
     * [lossPercent] 为 0–100。
     */
    fun fromRttAndLoss(rttMs: Int?, lossPercent: Double?): String {
        if (rttMs == null && lossPercent == null) return UNKNOWN
        val loss = lossPercent ?: 0.0
        val rtt = rttMs ?: 0
        return when {
            loss >= 30.0 || rtt >= 1000 -> DIE
            loss >= 15.0 || rtt >= 500 -> BAD
            loss >= 8.0 || rtt >= 300 -> MEDIUM
            loss >= 3.0 || rtt >= 150 -> GOOD
            else -> EXCELLENT
        }
    }

    fun toRank(quality: String): Int = when (quality) {
        EXCELLENT -> 1
        GOOD -> 2
        MEDIUM -> 3
        BAD -> 4
        DIE -> 5
        else -> 0
    }
}

data class StatRecord(
    val type: String,
    val members: Map<String, Any?>
)

data class TransportSample(
    val rttMs: Int?,
    val lossPercent: Double?,
    val audioLevel: Double?,
    val bytesSent: Long?,
    val bytesReceived: Long?
)

object StatsParser {
    fun parse(records: List<StatRecord>): TransportSample {
        var rttMs: Int? = null
        var lossPercent: Double? = null
        var audioLevel: Double? = null
        var bytesSent = 0L
        var bytesReceived = 0L
        var sawSent = false
        var sawRecv = false

        for (record in records) {
            val members = record.members
            when (record.type) {
                "candidate-pair" -> {
                    val selected = asBoolean(members["selected"]) ||
                        asBoolean(members["nominated"]) ||
                        members["state"] == "succeeded"
                    if (selected) {
                        secondsToMs(members["currentRoundTripTime"])?.let { rttMs = it }
                    }
                }
                "remote-inbound-rtp" -> {
                    if (rttMs == null) {
                        secondsToMs(members["roundTripTime"])?.let { rttMs = it }
                    }
                    fractionToPercent(members["fractionLost"])?.let { lossPercent = it }
                }
                "inbound-rtp" -> {
                    asLong(members["bytesReceived"])?.let {
                        bytesReceived += it
                        sawRecv = true
                    }
                    if (isAudio(members)) {
                        asDouble(members["audioLevel"])?.let { audioLevel = it }
                    }
                    if (lossPercent == null) {
                        val lost = asDouble(members["packetsLost"])
                        val received = asDouble(members["packetsReceived"])
                        if (lost != null && received != null) {
                            val total = lost + received
                            if (total > 0) lossPercent = lost * 100.0 / total
                        }
                    }
                }
                "outbound-rtp" -> {
                    asLong(members["bytesSent"])?.let {
                        bytesSent += it
                        sawSent = true
                    }
                    if (audioLevel == null && isAudio(members)) {
                        asDouble(members["audioLevel"])?.let { audioLevel = it }
                    }
                }
            }
        }
        return TransportSample(
            rttMs = rttMs,
            lossPercent = lossPercent,
            audioLevel = audioLevel,
            bytesSent = if (sawSent) bytesSent else null,
            bytesReceived = if (sawRecv) bytesReceived else null
        )
    }

    private fun isAudio(members: Map<String, Any?>): Boolean {
        val kind = members["kind"] ?: members["mediaType"]
        return kind == "audio"
    }

    private fun asDouble(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun asLong(value: Any?): Long? = asDouble(value)?.toLong()

    private fun asBoolean(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true)
        else -> false
    }

    private fun secondsToMs(value: Any?): Int? {
        val seconds = asDouble(value) ?: return null
        if (seconds < 0) return null
        return (seconds * 1000.0).toInt()
    }

    private fun fractionToPercent(value: Any?): Double? {
        val raw = asDouble(value) ?: return null
        if (raw < 0) return null
        return if (raw <= 1.0) raw * 100.0 else raw
    }
}

object Bitrate {
    fun bps(previousBytes: Long?, previousMs: Long?, bytes: Long?, nowMs: Long): Long? {
        if (previousBytes == null || previousMs == null || bytes == null) return null
        val elapsed = nowMs - previousMs
        if (elapsed <= 0) return null
        val delta = bytes - previousBytes
        if (delta < 0) return null
        return delta * 8 * 1000 / elapsed
    }
}

object VolumeMeter {
    fun pcm16LeRms(pcm: ByteArray): Int {
        if (pcm.size < 2) return 0
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < pcm.size) {
            val sample = (pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)
            val signed = sample.toShort().toInt()
            sum += signed.toDouble() * signed.toDouble()
            count++
            index += 2
        }
        if (count == 0) return 0
        val rms = kotlin.math.sqrt(sum / count)
        return kotlin.math.round(rms / 32768.0 * 255.0).toInt().coerceIn(0, 255)
    }

    fun fromUnitInterval(level: Double): Int {
        return kotlin.math.round(level.coerceIn(0.0, 1.0) * 255.0).toInt()
    }

    fun smooth(previous: Int, sample: Int, smooth: Int): Int {
        if (smooth <= 1) return sample.coerceIn(0, 255)
        val alpha = 1.0 / smooth
        return (previous * (1.0 - alpha) + sample * alpha).toInt().coerceIn(0, 255)
    }
}

enum class RejoinSignal {
    NONE,
    JOINED,
    REJOINED
}

data class TransportLoss(
    val shouldRetry: Boolean,
    val state: String
)

class ReconnectTracker(private val maxAttempts: Int = 3) {
    private var joinedOnce = false
    private var pendingRejoin = false
    private var attempts = 0

    @Synchronized
    fun reset() {
        joinedOnce = false
        pendingRejoin = false
        attempts = 0
    }

    @Synchronized
    fun attemptCount(): Int = attempts

    /**
     * 第一次连通记为 JOINED。只有发生过掉线并准备重连时，下一次连通才是 REJOINED。
     * 已经连通后的重复 ICE 回调返回 NONE。
     */
    @Synchronized
    fun onConnected(): RejoinSignal {
        if (!joinedOnce) {
            joinedOnce = true
            pendingRejoin = false
            attempts = 0
            return RejoinSignal.JOINED
        }
        if (pendingRejoin) {
            pendingRejoin = false
            attempts = 0
            return RejoinSignal.REJOINED
        }
        return RejoinSignal.NONE
    }

    @Synchronized
    fun onTransportLost(): TransportLoss {
        attempts += 1
        return if (attempts <= maxAttempts) {
            pendingRejoin = true
            TransportLoss(shouldRetry = true, state = "reconnecting")
        } else {
            pendingRejoin = false
            TransportLoss(shouldRetry = false, state = "failed")
        }
    }
}

data class StreamExtraPayload(val uid: String, val extra: String)

data class ClientMutePayload(val uid: String, val media: String, val muted: Boolean)

object StreamExtra {
    const val TYPE = "stream-extra"

    fun encode(uid: String, extra: String): String {
        return FlatJson.obj(
            "type" to TYPE,
            "uid" to uid,
            "extra" to extra
        )
    }

    fun decode(message: String): StreamExtraPayload? {
        val map = FlatJson.parse(message) ?: return null
        if (map["type"] != TYPE) return null
        return StreamExtraPayload(
            uid = map["uid"] as? String ?: "",
            extra = map["extra"] as? String ?: ""
        )
    }
}

object ClientMuteNotice {
    const val TYPE = "client-mute"

    fun encode(uid: String, media: String, muted: Boolean): String {
        return FlatJson.obj(
            "type" to TYPE,
            "uid" to uid,
            "media" to media,
            "muted" to muted
        )
    }

    fun decode(message: String): ClientMutePayload? {
        val map = FlatJson.parse(message) ?: return null
        if (map["type"] != TYPE) return null
        val media = map["media"] as? String ?: return null
        val muted = map["muted"] as? Boolean ?: return null
        return ClientMutePayload(
            uid = map["uid"] as? String ?: "",
            media = media,
            muted = muted
        )
    }
}

object DataFrame {
    private val MAGIC = byteArrayOf(0x53, 0x59, 0x53, 0x45, 0x49) // SYSEI

    fun wrapSei(payload: ByteArray): ByteArray {
        val out = ByteArray(MAGIC.size + payload.size)
        MAGIC.copyInto(out)
        payload.copyInto(out, MAGIC.size)
        return out
    }

    fun unwrapSei(data: ByteArray): ByteArray? {
        if (data.size < MAGIC.size) return null
        for (index in MAGIC.indices) {
            if (data[index] != MAGIC[index]) return null
        }
        return data.copyOfRange(MAGIC.size, data.size)
    }

    fun streamIdFromLabel(label: String): Int? {
        if (!label.startsWith("sy-")) return null
        return label.removePrefix("sy-").toIntOrNull()
    }
}

object BeautyMath {
    /**
     * 抬高 Y 平面。level 为 0 时返回拷贝，不改变亮度。
     */
    fun applyLightening(y: ByteArray, level: Float): ByteArray {
        val gain = 1f + level.coerceIn(0f, 1f) * 0.6f
        if (gain == 1f) return y.copyOf()
        val out = ByteArray(y.size)
        for (index in y.indices) {
            val value = y[index].toInt() and 0xFF
            out[index] = (value * gain).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }
}

internal object FlatJson {
    fun obj(vararg pairs: Pair<String, Any?>): String {
        val body = pairs.joinToString(",") { (key, value) ->
            "${quote(key)}:${encodeValue(value)}"
        }
        return "{$body}"
    }

    fun parse(text: String): Map<String, Any?>? {
        val parser = Parser(text.trim())
        return try {
            parser.parseObject()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun encodeValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Number -> value.toString()
        else -> quote(value.toString())
    }

    private fun quote(raw: String): String {
        val escaped = buildString {
            append('"')
            for (ch in raw) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }
        return escaped
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parseObject(): Map<String, Any?> {
            skip()
            expect('{')
            val map = linkedMapOf<String, Any?>()
            skip()
            if (peek() == '}') {
                index++
                return map
            }
            while (index < text.length) {
                skip()
                val key = parseString()
                skip()
                expect(':')
                skip()
                map[key] = parseValue()
                skip()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return map
                    }
                    else -> throw IllegalArgumentException("expected , or }")
                }
            }
            throw IllegalArgumentException("unterminated object")
        }

        private fun parseValue(): Any? {
            return when (peek()) {
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> parseNumber()
            }
        }

        private fun literal(token: String, value: Any?): Any? {
            if (!text.startsWith(token, index)) throw IllegalArgumentException(token)
            index += token.length
            return value
        }

        private fun parseNumber(): Double {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
            if (start == index) throw IllegalArgumentException("number")
            return text.substring(start, index).toDouble()
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < text.length) {
                val ch = text[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) throw IllegalArgumentException("escape")
                        when (val escaped = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            else -> out.append(escaped)
                        }
                    }
                    else -> out.append(ch)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun expect(ch: Char) {
            if (peek() != ch) throw IllegalArgumentException("expected $ch")
            index++
        }

        private fun peek(): Char {
            if (index >= text.length) throw IllegalArgumentException("eof")
            return text[index]
        }

        private fun skip() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }
}
