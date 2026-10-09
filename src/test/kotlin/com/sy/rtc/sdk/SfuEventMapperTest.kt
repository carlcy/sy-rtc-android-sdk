package com.sy.rtc.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SfuEventMapperTest {
    private class Rec : SfuSink {
        val ev = mutableListOf<String>()
        override fun remoteAudioMuted(uid: String, muted: Boolean) { ev += "ra:$uid:$muted" }
        override fun remoteVideoMuted(uid: String, muted: Boolean) { ev += "rv:$uid:$muted" }
        override fun serverMutedLocalAudio(muted: Boolean) { ev += "srv:$muted" }
        override fun kicked(reason: String) { ev += "kicked:$reason" }
        override fun mediaLost(detail: String) { ev += "lost:$detail" }
        override fun networkQuality(uid: String, quality: String) { ev += "q:$uid:$quality" }
        override fun levels(local: Int?, remote: Map<String, Int>) { ev += "lv:$local:$remote" }
        override fun reconnecting() { ev += "reconnecting" }
        override fun reconnected() { ev += "reconnected" }
    }

    @Test
    fun removedByServerIsKick_clientLeaveIsSilent_otherIsMediaLost() {
        val r = Rec(); val m = SfuEventMapper(r)
        m.on(SfuSignal.Disconnected(SfuDisconnect.CLIENT))
        m.on(SfuSignal.Disconnected(SfuDisconnect.REMOVED))
        m.on(SfuSignal.Disconnected(SfuDisconnect.ROOM_DELETED))
        m.on(SfuSignal.Disconnected(SfuDisconnect.OTHER, "signal close"))
        assertEquals(listOf("kicked:removed by server", "kicked:room deleted", "lost:signal close"), r.ev)
    }

    @Test
    fun localMuteNotRequestedIsServerMute_andSticky() {
        val r = Rec(); val m = SfuEventMapper(r)
        m.on(SfuSignal.TrackMuted("me", isLocal = true, audio = true, muted = true))
        assertTrue(m.isServerMuted())
        // repeated mute event does not re-fire
        m.on(SfuSignal.TrackMuted("me", isLocal = true, audio = true, muted = true))
        m.on(SfuSignal.TrackMuted("me", isLocal = true, audio = true, muted = false))
        assertFalse(m.isServerMuted())
        assertEquals(listOf("srv:true", "srv:false"), r.ev)
    }

    @Test
    fun appRequestedLocalMuteIsNotServerMute() {
        val r = Rec(); val m = SfuEventMapper(r)
        m.localAudioMuteRequested = true
        m.on(SfuSignal.TrackMuted("me", isLocal = true, audio = true, muted = true))
        m.on(SfuSignal.TrackMuted("me", isLocal = true, audio = false, muted = true))
        assertTrue(r.ev.isEmpty())
        assertFalse(m.isServerMuted())
    }

    @Test
    fun remoteMutesMapByKind() {
        val r = Rec(); val m = SfuEventMapper(r)
        m.on(SfuSignal.TrackMuted("u2", isLocal = false, audio = true, muted = true))
        m.on(SfuSignal.TrackMuted("u2", isLocal = false, audio = false, muted = false))
        assertEquals(listOf("ra:u2:true", "rv:u2:false"), r.ev)
    }

    @Test
    fun qualityAndLevels() {
        val r = Rec(); val m = SfuEventMapper(r)
        m.on(SfuSignal.Quality("u2", false, SfuQuality.LOST))
        m.on(SfuSignal.Quality("me", true, SfuQuality.EXCELLENT))
        m.on(SfuSignal.Levels(1f, mapOf("u2" to 0.5f)))
        assertEquals(listOf("q:u2:down", "q:me:excellent", "lv:255:{u2=128}"), r.ev)
    }

    @Test
    fun onceFlagFiresOncePerJoin() {
        val f = OnceFlag()
        assertTrue(f.tryFire()); assertFalse(f.tryFire())
        f.reset(); assertTrue(f.tryFire())
    }
}
