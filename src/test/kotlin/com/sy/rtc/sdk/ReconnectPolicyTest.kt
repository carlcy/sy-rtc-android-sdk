package com.sy.rtc.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 与 iOS SyRtcReconnectPolicyTests 相同的期望。 */
class ReconnectPolicyTest {
    @Test
    fun backoffIsOneTwoFourEightSixteenSeconds() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L), (1..5).map(ReconnectPolicy::delayMs))
        assertEquals(16000L, ReconnectPolicy.delayMs(9))
        assertEquals(1000L, ReconnectPolicy.delayMs(0))
        assertEquals(5, ReconnectPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun fiveRetriesThenFailedAndSuccessResets() {
        val tracker = ReconnectTracker()
        assertEquals(RejoinSignal.JOINED, tracker.onConnected())
        val decisions = (1..5).map { tracker.onTransportLost() }
        assertTrue(decisions.all { it.shouldRetry && it.state == "reconnecting" })
        assertEquals(listOf(1, 2, 3, 4, 5), decisions.map { it.attempt })
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L), decisions.map { it.delayMs })
        val sixth = tracker.onTransportLost()
        assertFalse(sixth.shouldRetry)
        assertEquals("failed", sixth.state)

        val fresh = ReconnectTracker()
        fresh.onConnected()
        fresh.onTransportLost()
        fresh.onTransportLost()
        assertTrue(fresh.isRecovering())
        assertEquals(RejoinSignal.REJOINED, fresh.onConnected())
        assertEquals(0, fresh.attemptCount())
        assertEquals(1, fresh.onTransportLost().attempt)
    }

    @Test
    fun repeatedConnectedWithoutLossIsNone() {
        val tracker = ReconnectTracker()
        assertFalse(tracker.hasJoined())
        tracker.onConnected()
        assertTrue(tracker.hasJoined())
        assertEquals(RejoinSignal.NONE, tracker.onConnected())
    }
}
