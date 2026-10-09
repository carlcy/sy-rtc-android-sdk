package com.sy.rtc.sdk

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import io.livekit.android.LiveKit
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Media over a LiveKit SFU, used when the token carries `sfuUrl` / `sfuToken`.
 *
 * Only media lives here. `/ws/signaling` keeps carrying business events and the roster.
 * LiveKit types stay inside this class; events go out through [SfuEventMapper].
 */
internal class LiveKitMediaSession(
    context: Context,
    private val localUid: String,
    private val mapper: SfuEventMapper,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onConnected(reconnect: Boolean)
        fun onConnectFailed(error: Throwable)
        fun onFirstRemoteVideo(uid: String)
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var room: Room? = null
    private var eventsJob: Job? = null
    private var levelsJob: Job? = null

    @Volatile var credentials: SfuJoinInfo? = null
        private set
    @Volatile private var wantMic = false
    @Volatile private var wantCamera = false
    @Volatile private var released = false

    private val remoteVideo = ConcurrentHashMap<String, VideoTrack>()
    private val remoteContainers = ConcurrentHashMap<String, WeakReference<ViewGroup>>()
    private val remoteRenderers = ConcurrentHashMap<String, TextureViewRenderer>()
    private val firstVideoSeen = ConcurrentHashMap.newKeySet<String>()
    private var localContainer: WeakReference<ViewGroup>? = null
    private var localRenderer: TextureViewRenderer? = null

    fun connect(info: SfuJoinInfo, publishMic: Boolean, publishCamera: Boolean, reconnect: Boolean = false) {
        credentials = info
        wantMic = publishMic
        wantCamera = publishCamera
        scope.launch {
            val r = room ?: LiveKit.create(appContext).also { room = it; collectEvents(it) }
            try {
                r.connect(info.url, info.token)
                if (released) return@launch
                applyPublishing(r)
                bindLocal(r)
                startLevels(r)
                callbacks.onConnected(reconnect)
            } catch (e: Throwable) {
                Log.w(TAG, "LiveKit connect failed", e)
                if (!released) callbacks.onConnectFailed(e)
            }
        }
    }

    /** New SY token renewed: keep the matching sfuToken for the next (re)connect. */
    fun updateCredentials(info: SfuJoinInfo) {
        credentials = info
    }

    /** Reconnect after a media loss with the latest credentials. */
    fun reconnect() {
        val info = credentials ?: return
        scope.launch {
            room?.disconnect()
            connect(info, wantMic, wantCamera, reconnect = true)
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        wantMic = enabled
        val r = room ?: return
        scope.launch {
            try { r.localParticipant.setMicrophoneEnabled(enabled) } catch (e: Throwable) { Log.w(TAG, "mic", e) }
        }
    }

    fun setCameraEnabled(enabled: Boolean) {
        wantCamera = enabled
        val r = room ?: return
        scope.launch {
            try {
                r.localParticipant.setCameraEnabled(enabled)
                bindLocal(r)
            } catch (e: Throwable) { Log.w(TAG, "camera", e) }
        }
    }

    fun attachLocal(container: ViewGroup) {
        localContainer = WeakReference(container)
        room?.let { r -> scope.launch { bindLocal(r) } }
    }

    fun attachRemote(uid: String, container: ViewGroup) {
        remoteContainers[uid] = WeakReference(container)
        scope.launch { bindRemote(uid) }
    }

    fun disconnect() {
        released = true
        scope.launch {
            try {
                remoteRenderers.values.forEach { it.release() }
                localRenderer?.release()
                room?.disconnect()
                room?.release()
            } catch (e: Throwable) {
                Log.w(TAG, "LiveKit release", e)
            } finally {
                room = null
                remoteRenderers.clear(); remoteVideo.clear(); remoteContainers.clear()
                scope.cancel()
            }
        }
    }

    private suspend fun applyPublishing(r: Room) {
        try { r.localParticipant.setMicrophoneEnabled(wantMic) } catch (e: Throwable) { Log.w(TAG, "mic", e) }
        if (wantCamera) {
            try { r.localParticipant.setCameraEnabled(true) } catch (e: Throwable) { Log.w(TAG, "camera", e) }
        }
    }

    private fun collectEvents(r: Room) {
        eventsJob = scope.launch {
            r.events.events.collect { e -> handle(e) }
        }
    }

    private fun uidOf(p: Participant?): String = p?.identity?.value ?: ""

    private fun handle(e: RoomEvent) {
        when (e) {
            is RoomEvent.TrackSubscribed -> {
                val uid = uidOf(e.participant)
                val track = e.track
                if (track is VideoTrack && uid.isNotEmpty()) {
                    remoteVideo[uid] = track
                    bindRemote(uid)
                }
            }
            is RoomEvent.TrackUnsubscribed -> {
                val uid = uidOf(e.participant)
                if (e.track is VideoTrack) {
                    remoteVideo.remove(uid)
                    remoteRenderers.remove(uid)?.let { rr ->
                        try { (e.track as VideoTrack).removeRenderer(rr) } catch (_: Throwable) {}
                        rr.release()
                        (rr.parent as? ViewGroup)?.removeView(rr)
                    }
                    firstVideoSeen.remove(uid)
                }
            }
            is RoomEvent.TrackMuted -> mapMute(e.participant, e.publication.kind, true)
            is RoomEvent.TrackUnmuted -> mapMute(e.participant, e.publication.kind, false)
            is RoomEvent.ConnectionQualityChanged -> {
                val uid = uidOf(e.participant)
                val local = uid == localUid
                mapper.on(SfuSignal.Quality(if (local) localUid else uid, local, quality(e.quality)))
            }
            is RoomEvent.Reconnecting -> mapper.on(SfuSignal.Reconnecting)
            is RoomEvent.Reconnected -> mapper.on(SfuSignal.Reconnected)
            is RoomEvent.Disconnected -> {
                if (released) return
                mapper.on(SfuSignal.Disconnected(reason(e.reason), e.error?.message ?: e.reason?.name.orEmpty()))
            }
            else -> Unit
        }
    }

    private fun mapMute(p: Participant, kind: Track.Kind, muted: Boolean) {
        val uid = uidOf(p)
        val local = uid == localUid
        when (kind) {
            Track.Kind.AUDIO -> mapper.on(SfuSignal.TrackMuted(uid, local, audio = true, muted = muted))
            Track.Kind.VIDEO -> mapper.on(SfuSignal.TrackMuted(uid, local, audio = false, muted = muted))
            else -> Unit
        }
    }

    private fun bindRemote(uid: String) {
        val r = room ?: return
        val track = remoteVideo[uid] ?: return
        val container = remoteContainers[uid]?.get() ?: return
        remoteRenderers.remove(uid)?.let { old ->
            try { track.removeRenderer(old) } catch (_: Throwable) {}
            old.release()
            (old.parent as? ViewGroup)?.removeView(old)
        }
        val renderer = TextureViewRenderer(container.context)
        r.initVideoRenderer(renderer)
        container.removeAllViews()
        container.addView(renderer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        track.addRenderer(renderer)
        remoteRenderers[uid] = renderer
        if (firstVideoSeen.add(uid)) callbacks.onFirstRemoteVideo(uid)
    }

    private fun bindLocal(r: Room) {
        val container = localContainer?.get() ?: return
        val track = r.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack ?: return
        localRenderer?.let { old ->
            try { track.removeRenderer(old) } catch (_: Throwable) {}
            old.release()
            (old.parent as? ViewGroup)?.removeView(old)
        }
        val renderer = TextureViewRenderer(container.context)
        r.initVideoRenderer(renderer)
        renderer.setMirror(true)
        container.removeAllViews()
        container.addView(renderer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        track.addRenderer(renderer)
        localRenderer = renderer
    }

    /** LiveKit audio levels (0..1) every 200 ms, fed into the existing volume indication. */
    private fun startLevels(r: Room) {
        levelsJob?.cancel()
        levelsJob = scope.launch {
            while (isActive && !released) {
                val remote = r.remoteParticipants.values.associate { uidOf(it) to it.audioLevel }
                    .filterKeys { it.isNotEmpty() }
                mapper.on(SfuSignal.Levels(r.localParticipant.audioLevel, remote))
                delay(200)
            }
        }
    }

    companion object {
        private const val TAG = "LiveKitMediaSession"

        fun reason(r: DisconnectReason?): SfuDisconnect = when (r) {
            DisconnectReason.CLIENT_INITIATED -> SfuDisconnect.CLIENT
            DisconnectReason.PARTICIPANT_REMOVED -> SfuDisconnect.REMOVED
            DisconnectReason.ROOM_DELETED, DisconnectReason.ROOM_CLOSED -> SfuDisconnect.ROOM_DELETED
            DisconnectReason.DUPLICATE_IDENTITY -> SfuDisconnect.DUPLICATE_IDENTITY
            else -> SfuDisconnect.OTHER
        }

        fun quality(q: ConnectionQuality): SfuQuality = when (q) {
            ConnectionQuality.EXCELLENT -> SfuQuality.EXCELLENT
            ConnectionQuality.GOOD -> SfuQuality.GOOD
            ConnectionQuality.POOR -> SfuQuality.POOR
            ConnectionQuality.LOST -> SfuQuality.LOST
            else -> SfuQuality.UNKNOWN
        }
    }
}
