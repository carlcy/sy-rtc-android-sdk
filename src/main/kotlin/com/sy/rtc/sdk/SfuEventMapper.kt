package com.sy.rtc.sdk

import java.util.concurrent.atomic.AtomicBoolean

/** Why the SFU connection ended, reduced from LiveKit's DisconnectReason. */
internal enum class SfuDisconnect { CLIENT, REMOVED, ROOM_DELETED, DUPLICATE_IDENTITY, OTHER }

/** LiveKit ConnectionQuality, reduced. */
internal enum class SfuQuality { EXCELLENT, GOOD, POOR, LOST, UNKNOWN }

/** Media-plane events, independent of LiveKit types so the mapping is unit-testable. */
internal sealed class SfuSignal {
    data class TrackMuted(val uid: String, val isLocal: Boolean, val audio: Boolean, val muted: Boolean) : SfuSignal()
    data class Disconnected(val reason: SfuDisconnect, val detail: String = "") : SfuSignal()
    data class Quality(val uid: String, val isLocal: Boolean, val quality: SfuQuality) : SfuSignal()
    data class Levels(val local: Float?, val remote: Map<String, Float>) : SfuSignal()
    object Reconnecting : SfuSignal()
    object Reconnected : SfuSignal()
}

/** What the engine does with a mapped media event. */
internal interface SfuSink {
    fun remoteAudioMuted(uid: String, muted: Boolean)
    fun remoteVideoMuted(uid: String, muted: Boolean)
    /** The server muted (or released) our microphone. Must not be auto-restored by the SDK. */
    fun serverMutedLocalAudio(muted: Boolean)
    /** Removed from the room by the server. Engine dedupes with signaling `kicked` and leaves. */
    fun kicked(reason: String)
    /** Media connection lost for a reason other than kick / leave. */
    fun mediaLost(detail: String)
    fun networkQuality(uid: String, quality: String)
    fun levels(local: Int?, remote: Map<String, Int>)
    fun reconnecting()
    fun reconnected()
}

/**
 * Maps LiveKit media events onto SY callbacks.
 *
 * Roster (onUserJoined / onUserOffline) still comes from `/ws/signaling`, which every
 * SY client joins; LiveKit participant events are not reported again to avoid doubles.
 */
internal class SfuEventMapper(private val sink: SfuSink) {
    /** Last mute state the app asked for. A local mute that differs came from the server. */
    @Volatile var localAudioMuteRequested: Boolean = false
    @Volatile private var serverMuted = false

    fun on(signal: SfuSignal) {
        when (signal) {
            is SfuSignal.TrackMuted -> onTrackMuted(signal)
            is SfuSignal.Disconnected -> when (signal.reason) {
                SfuDisconnect.CLIENT -> Unit
                SfuDisconnect.REMOVED -> sink.kicked("removed by server")
                SfuDisconnect.ROOM_DELETED -> sink.kicked("room deleted")
                SfuDisconnect.DUPLICATE_IDENTITY -> sink.kicked("duplicate identity")
                SfuDisconnect.OTHER -> sink.mediaLost(signal.detail.ifBlank { "sfu disconnected" })
            }
            is SfuSignal.Quality -> sink.networkQuality(signal.uid, qualityLabel(signal.quality))
            is SfuSignal.Levels -> sink.levels(
                signal.local?.let { VolumeMeter.fromUnitInterval(it.toDouble()) },
                signal.remote.mapValues { VolumeMeter.fromUnitInterval(it.value.toDouble()) },
            )
            SfuSignal.Reconnecting -> sink.reconnecting()
            SfuSignal.Reconnected -> sink.reconnected()
        }
    }

    private fun onTrackMuted(s: SfuSignal.TrackMuted) {
        if (!s.isLocal) {
            if (s.audio) sink.remoteAudioMuted(s.uid, s.muted) else sink.remoteVideoMuted(s.uid, s.muted)
            return
        }
        if (!s.audio) return
        if (s.muted && !localAudioMuteRequested && !serverMuted) {
            serverMuted = true
            sink.serverMutedLocalAudio(true)
        } else if (!s.muted && serverMuted) {
            serverMuted = false
            sink.serverMutedLocalAudio(false)
        }
    }

    fun isServerMuted(): Boolean = serverMuted

    companion object {
        fun qualityLabel(q: SfuQuality): String = when (q) {
            SfuQuality.EXCELLENT -> NetworkQualityEstimator.EXCELLENT
            SfuQuality.GOOD -> NetworkQualityEstimator.GOOD
            SfuQuality.POOR -> NetworkQualityEstimator.POOR
            SfuQuality.LOST -> NetworkQualityEstimator.DOWN
            SfuQuality.UNKNOWN -> NetworkQualityEstimator.UNKNOWN
        }
    }
}

/** onKicked fires once per join even when LiveKit, signaling and the member poll all report it. */
internal class OnceFlag {
    private val fired = AtomicBoolean(false)
    fun tryFire(): Boolean = fired.compareAndSet(false, true)
    fun reset() = fired.set(false)
}
