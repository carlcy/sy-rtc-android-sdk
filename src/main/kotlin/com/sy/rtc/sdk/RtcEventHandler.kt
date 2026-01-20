package com.sy.rtc.sdk

/**
 * RTC事件处理器
 */
interface RtcEventHandler {
    /**
     * 用户加入回调
     * 
     * @param uid 用户ID
     * @param elapsed 加入耗时（毫秒）
     */
    fun onUserJoined(uid: String, elapsed: Int) {}

    /**
     * 用户离开回调
     * 
     * @param uid 用户ID
     * @param reason 离开原因
     */
    fun onUserOffline(uid: String, reason: String) {}

    /**
     * 音量指示回调
     * 
     * @param speakers 说话者列表，包含uid和volume
     */
    fun onVolumeIndication(speakers: List<VolumeInfo>) {}
}

/**
 * 音量信息
 */
data class VolumeInfo(
    val uid: String,
    val volume: Int
)

