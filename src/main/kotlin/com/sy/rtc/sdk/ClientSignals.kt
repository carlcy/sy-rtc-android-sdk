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

/**
 * 网络质量档位。Android 与 iOS（`SyRtcNetworkQuality`）使用同一套名字和阈值，改动需两端同步。
 *
 * 名字：`excellent` / `good` / `poor` / `bad` / `down` / `unknown`（与 Flutter `SyNetworkQualityLevel` 相同）。
 * 阈值参考即构 Express 的质量分级：取 RTT 与丢包各自落入的档位中较差的一个。
 *
 * | 档位 | RTT (ms) | 丢包 |
 * |---|---|---|
 * | excellent | < 100 | < 1% |
 * | good | < 200 | < 3% |
 * | poor | < 400 | < 8% |
 * | bad | < 800 | < 20% |
 * | down | ≥ 800 | ≥ 20% |
 *
 * 没有 RTT 也没有丢包样本时为 `unknown`。
 */
object NetworkQualityEstimator {
    const val UNKNOWN = "unknown"
    const val EXCELLENT = "excellent"
    const val GOOD = "good"
    const val POOR = "poor"
    const val BAD = "bad"
    const val DOWN = "down"

    @Deprecated("3.2.0 起与 iOS 统一为 poor", ReplaceWith("POOR"))
    const val MEDIUM = POOR
    @Deprecated("3.2.0 起与 iOS 统一为 down", ReplaceWith("DOWN"))
    const val DIE = DOWN

    const val RTT_EXCELLENT_MS = 100
    const val RTT_GOOD_MS = 200
    const val RTT_POOR_MS = 400
    const val RTT_BAD_MS = 800
    const val LOSS_EXCELLENT = 0.01
    const val LOSS_GOOD = 0.03
    const val LOSS_POOR = 0.08
    const val LOSS_BAD = 0.20

    /** [lossPercent] 为 0–100（兼容旧调用）。 */
    fun fromRttAndLoss(rttMs: Int?, lossPercent: Double?): String =
        fromRttAndLossRate(rttMs?.toDouble(), lossPercent?.let { it / 100.0 })

    /** [lossRate] 为 0–1，与 iOS `SyRtcNetworkQuality.level(rttMs:packetLossRatio:)` 同签名语义。 */
    fun fromRttAndLossRate(rttMs: Double?, lossRate: Double?): String {
        if (rttMs == null && lossRate == null) return UNKNOWN
        val rtt = (rttMs ?: 0.0).coerceAtLeast(0.0)
        val loss = (lossRate ?: 0.0).coerceIn(0.0, 1.0)
        return when {
            loss >= LOSS_BAD || rtt >= RTT_BAD_MS -> DOWN
            loss >= LOSS_POOR || rtt >= RTT_POOR_MS -> BAD
            loss >= LOSS_GOOD || rtt >= RTT_GOOD_MS -> POOR
            loss >= LOSS_EXCELLENT || rtt >= RTT_EXCELLENT_MS -> GOOD
            else -> EXCELLENT
        }
    }

    /** 0 unknown，1 excellent … 5 down。两端相同。 */
    fun toRank(quality: String): Int = when (quality) {
        EXCELLENT -> 1
        GOOD -> 2
        POOR, "medium" -> 3
        BAD -> 4
        DOWN, "die" -> 5
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

/**
 * 重连策略。Android 与 iOS（`SyRtcReconnectPolicy`）相同，改动需两端同步。
 *
 * 信令或 ICE 断开后最多重试 [MAX_ATTEMPTS] 次，第 n 次等待 `2^(n-1)` 秒：1、2、4、8、16 秒。
 * ICE 断开时由字典序较小的一方 `restartIce` 并重新发 offer；另一方等对端 offer。
 * 连接状态回调（两端同名）：
 * - `connecting` / `joining` → `connected` / `join_success`
 * - `reconnecting` / `signaling` 或 `ice`（并回调 `onReconnecting`）
 * - `connected` / `rejoin_success`（并回调 `onRejoinChannelSuccess`、`onReconnected`）
 * - `failed` / `signaling` 或 `ice`（并回调 `onReconnectFailed`、`onError(1003)`）
 * - `disconnecting` / `leaving` → `disconnected` / `leave`
 */
object ReconnectPolicy {
    const val MAX_ATTEMPTS = 5
    const val BASE_DELAY_MS = 1000L
    const val MAX_DELAY_MS = 16_000L

    /** [attempt] 从 1 开始。 */
    fun delayMs(attempt: Int): Long {
        val n = attempt.coerceIn(1, 31) - 1
        return (BASE_DELAY_MS shl n.coerceAtMost(20)).coerceAtMost(MAX_DELAY_MS)
    }
}

data class TransportLoss(
    val shouldRetry: Boolean,
    val state: String,
    val attempt: Int = 0,
    val delayMs: Long = 0,
)

class ReconnectTracker(private val maxAttempts: Int = ReconnectPolicy.MAX_ATTEMPTS) {
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

    @Synchronized
    fun hasJoined(): Boolean = joinedOnce

    @Synchronized
    fun isRecovering(): Boolean = pendingRejoin

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
            TransportLoss(shouldRetry = true, state = "reconnecting", attempt = attempts, delayMs = ReconnectPolicy.delayMs(attempts))
        } else {
            pendingRejoin = false
            TransportLoss(shouldRetry = false, state = "failed", attempt = attempts)
        }
    }
}

data class StreamExtraPayload(val uid: String, val extra: String)

/**
 * Android / iOS / Flutter 共用的频道信令约定。两端互通靠它，改动必须三端同步。
 *
 * - 流附加信息：`channel-message`，正文以 [STREAM_EXTRA_PREFIX] 开头，其后是原文。
 *   这类消息只回调 `onStreamExtraInfoUpdated`，不进 `onChannelMessage`。
 * - 本端静音通知：信令类型 [USER_MEDIA_TYPE]，`data` 为 `{uid, audioMuted?, videoMuted?}`。
 *   不是服务端强制静音（那是 `mute-audio`，回调 `onServerMuteAudio`）。
 * - SEI 风格消息：DataChannel 二进制，前 5 字节为 `SYSEI`，见 [DataFrame]。
 */
object WireProtocol {
    const val STREAM_EXTRA_PREFIX = "sy-extra:"
    const val USER_MEDIA_TYPE = "user-media"
    const val MAX_STREAM_EXTRA_BYTES = 1024

    fun encodeStreamExtra(extra: String): String = STREAM_EXTRA_PREFIX + extra

    fun decodeStreamExtra(message: String): String? =
        if (message.startsWith(STREAM_EXTRA_PREFIX)) message.substring(STREAM_EXTRA_PREFIX.length) else null

    /** 旧的 Android JSON 信封（`stream-extra` / `client-mute`），只收不发。 */
    fun isLegacyEnvelope(message: String): Boolean =
        StreamExtra.decode(message) != null || ClientMuteNotice.decode(message) != null

    /** 是否为 SDK 保留的频道消息（不应回调 `onChannelMessage`）。 */
    fun isReservedChannelMessage(message: String): Boolean =
        decodeStreamExtra(message) != null || isLegacyEnvelope(message)

    /** 解析 `user-media` 的 data。字段缺失时为 null。 */
    fun decodeUserMedia(data: Map<String, Any?>): Pair<Boolean?, Boolean?> =
        bool(data["audioMuted"]) to bool(data["videoMuted"])

    private fun bool(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
    }
}

/** 网络类型名字与 iOS `getNetworkType` 相同：wifi / cellular / ethernet / none / unknown。 */
object NetworkTypes {
    const val WIFI = "wifi"
    const val CELLULAR = "cellular"
    const val ETHERNET = "ethernet"
    const val NONE = "none"
    const val UNKNOWN = "unknown"

    fun classify(connected: Boolean, wifi: Boolean, cellular: Boolean, ethernet: Boolean): String = when {
        !connected -> NONE
        wifi -> WIFI
        ethernet -> ETHERNET
        cellular -> CELLULAR
        else -> UNKNOWN
    }
}

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
