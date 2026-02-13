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

    /**
     * 错误回调（可选）
     *
     * @param code 错误码（自定义）
     * @param message 错误信息
     */
    fun onError(code: Int, message: String) {}

    /**
     * 数据流消息回调
     *
     * @param uid 发送方用户ID（未知时可能为空字符串）
     * @param streamId 数据流ID
     * @param data 二进制数据
     */
    fun onStreamMessage(uid: String, streamId: Int, data: ByteArray) {}

    /**
     * 数据流消息错误回调
     *
     * @param uid 发送方用户ID（未知时可能为空字符串）
     * @param streamId 数据流ID
     * @param code 错误码
     * @param missed 丢失消息数
     * @param cached 缓存消息数
     */
    fun onStreamMessageError(uid: String, streamId: Int, code: Int, missed: Int, cached: Int) {}
}

/**
 * 音量信息
 */
data class VolumeInfo(
    val uid: String,
    val volume: Int
)
