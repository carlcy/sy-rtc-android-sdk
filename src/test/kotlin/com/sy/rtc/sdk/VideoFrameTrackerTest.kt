package com.sy.rtc.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/** 与 iOS SyRtcVideoFrameTrackerTests 相同的期望。 */
class VideoFrameTrackerTest {
    @Test
    fun firstFrameThenOnlyChanges() {
        val t = VideoFrameTracker()
        assertEquals(VideoFrameTracker.Change(first = true, sizeChanged = true), t.onFrame(640, 360, 0))
        assertEquals(VideoFrameTracker.Change(first = false, sizeChanged = false), t.onFrame(640, 360, 0))
        assertEquals(VideoFrameTracker.Change(first = false, sizeChanged = true), t.onFrame(1280, 720, 0))
        assertEquals(VideoFrameTracker.Change(first = false, sizeChanged = true), t.onFrame(1280, 720, 90))
        assertEquals(VideoFrameTracker.Change(first = false, sizeChanged = false), t.onFrame(1280, 720, 90))
    }
}
