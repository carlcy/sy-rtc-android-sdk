package com.sy.rtc.sdk

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manual E2E against a real backend + LiveKit node. Skipped unless run with:
 *   -Pandroid.testInstrumentationRunnerArguments.sfuMeta=<base64 of /api/server/rtc/token meta=true response>
 *   -Pandroid.testInstrumentationRunnerArguments.signalingUrl=ws://10.0.2.2:8080/ws/signaling
 *   -Pandroid.testInstrumentationRunnerArguments.expectRemote=<uid of a peer already in the LiveKit room>
 * The host side is expected to remove this participant from the LiveKit room (lk / admin kick) while the test waits.
 */
@RunWith(AndroidJUnit4::class)
class LiveKitE2ETest {
    @Test
    fun joinSeesRemoteAndKickFiresOnce() {
        val args = InstrumentationRegistry.getArguments()
        val metaB64 = args.getString("sfuMeta")
        assumeTrue("sfuMeta not provided; skipping E2E", !metaB64.isNullOrBlank())
        val meta = String(Base64.decode(metaB64, Base64.DEFAULT))
        val data = JSONObject(meta).let { it.optJSONObject("data") ?: it }
        val channel = data.getString("channelId")
        val uid = data.getString("uid")
        val expectRemote = args.getString("expectRemote") ?: ""

        val joined = CountDownLatch(1)
        val sawRemote = CountDownLatch(1)
        val kicked = CountDownLatch(1)
        val kickCount = AtomicInteger(0)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val qualities = Collections.synchronizedList(mutableListOf<String>())

        val engine = RtcEngine.create()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.init(data.getString("appId"), InstrumentationRegistry.getInstrumentation().targetContext)
            args.getString("signalingUrl")?.let { engine.setSignalingServerUrl(it) }
            engine.setEventHandler(object : RtcEventHandler() {
                override fun onJoinChannelSuccess(channelId: String, uid: String, elapsed: Int) { joined.countDown() }
                override fun onVolumeIndication(speakers: List<VolumeInfo>) {
                    if (expectRemote.isNotEmpty() && speakers.any { it.uid == expectRemote }) sawRemote.countDown()
                }
                override fun onNetworkQuality(uid: String, txQuality: String, rxQuality: String) { qualities.add("$uid=$txQuality") }
                override fun onKicked(channelId: String, reason: String) {
                    kickCount.incrementAndGet(); android.util.Log.i("LKE2E", "KICKED $channelId $reason"); kicked.countDown()
                }
                override fun onError(code: Int, message: String) { errors.add("$code:$message") }
            })
            engine.enableAudioVolumeIndication(200, 3, false)
            engine.join(channel, uid, meta)
        }
        assertTrue("onJoinChannelSuccess not received; errors=$errors", joined.await(20, TimeUnit.SECONDS))
        android.util.Log.i("LKE2E", "JOINED $channel as $uid")
        if (expectRemote.isNotEmpty()) {
            assertTrue("remote $expectRemote not seen via LiveKit; errors=$errors", sawRemote.await(20, TimeUnit.SECONDS))
            android.util.Log.i("LKE2E", "SAW_REMOTE $expectRemote")
        }
        assertTrue("onKicked not received; errors=$errors quality=$qualities", kicked.await(90, TimeUnit.SECONDS))
        Thread.sleep(3000)
        android.util.Log.i("LKE2E", "DONE kicks=${kickCount.get()} quality=${qualities.take(5)} errors=$errors")
        assertEquals("onKicked must fire exactly once", 1, kickCount.get())
        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.leave() }
    }
}
