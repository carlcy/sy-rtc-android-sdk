package com.sy.rtc.sdk

/**
 * RTC事件处理器
 */
open class RtcEventHandler {
    /**
     * 加入频道成功回调
     *
     * @param channelId 频道ID
     * @param uid 用户ID
     * @param elapsed 加入耗时（毫秒）
     */
    open fun onJoinChannelSuccess(channelId: String, uid: String, elapsed: Int) {}

    /**
     * 离开频道回调
     *
     * @param stats 离开时的统计信息
     */
    open fun onLeaveChannel(stats: Map<String, Any?>) {}

    /**
     * 重新加入频道成功回调
     *
     * @param channelId 频道ID
     * @param uid 用户ID
     * @param elapsed 加入耗时（毫秒）
     */
    open fun onRejoinChannelSuccess(channelId: String, uid: String, elapsed: Int) {}

    /**
     * 用户加入回调
     *
     * @param uid 用户ID
     * @param elapsed 加入耗时（毫秒）
     */
    open fun onUserJoined(uid: String, elapsed: Int) {}

    /**
     * 用户离开回调
     *
     * @param uid 用户ID
     * @param reason 离开原因
     */
    open fun onUserOffline(uid: String, reason: String) {}

    /**
     * RTC统计回调
     *
     * @param stats 统计信息
     */
    open fun onRtcStats(stats: Map<String, Any?>) {}

    /**
     * 远端用户静音状态回调
     *
     * @param uid 用户ID
     * @param muted 是否静音
     */
    open fun onUserMuteAudio(uid: String, muted: Boolean) {}

    /**
     * 连接状态变化回调
     *
     * @param state 连接状态
     * @param reason 变化原因
     */
    open fun onConnectionStateChanged(state: String, reason: String) {}

    /**
     * 网络质量回调
     *
     * @param uid 用户ID
     * @param txQuality 上行质量
     * @param rxQuality 下行质量
     */
    open fun onNetworkQuality(uid: String, txQuality: String, rxQuality: String) {}

    /**
     * Token即将过期回调
     */
    open fun onTokenPrivilegeWillExpire() {}

    /**
     * 请求Token回调
     */
    open fun onRequestToken() {}

    /**
     * 本地音频状态变化回调
     *
     * @param state 音频状态
     * @param error 错误信息
     */
    open fun onLocalAudioStateChanged(state: String, error: String) {}

    /**
     * 远端音频状态变化回调
     *
     * @param uid 用户ID
     * @param state 音频状态
     * @param reason 变化原因
     * @param elapsed 耗时（毫秒）
     */
    open fun onRemoteAudioStateChanged(uid: String, state: String, reason: String, elapsed: Int) {}

    /**
     * 本地视频状态变化回调
     *
     * @param state 视频状态
     * @param error 错误信息
     */
    open fun onLocalVideoStateChanged(state: String, error: String) {}

    /**
     * 远端视频状态变化回调
     *
     * @param uid 用户ID
     * @param state 视频状态
     * @param reason 变化原因
     * @param elapsed 耗时（毫秒）
     */
    open fun onRemoteVideoStateChanged(uid: String, state: String, reason: String, elapsed: Int) {}

    /**
     * 首帧远端视频解码回调
     *
     * @param uid 用户ID
     * @param width 宽度
     * @param height 高度
     * @param elapsed 耗时（毫秒）
     */
    open fun onFirstRemoteVideoDecoded(uid: String, width: Int, height: Int, elapsed: Int) {}

    /**
     * 首帧远端视频渲染回调
     *
     * @param uid 用户ID
     * @param width 宽度
     * @param height 高度
     * @param elapsed 耗时（毫秒）
     */
    open fun onFirstRemoteVideoFrame(uid: String, width: Int, height: Int, elapsed: Int) {}

    /**
     * 视频尺寸变化回调
     *
     * @param uid 用户ID
     * @param width 宽度
     * @param height 高度
     * @param rotation 旋转角度
     */
    open fun onVideoSizeChanged(uid: String, width: Int, height: Int, rotation: Int) {}

    /**
     * 音频路由变化回调
     *
     * @param routing 路由类型
     */
    open fun onAudioRoutingChanged(routing: Int) {}

    /**
     * 音频发布状态变化回调
     *
     * @param channelId 频道ID
     * @param oldState 旧状态
     * @param newState 新状态
     * @param elapsed 耗时（毫秒）
     */
    open fun onAudioPublishStateChanged(channelId: String, oldState: String, newState: String, elapsed: Int) {}

    /**
     * 音频订阅状态变化回调
     *
     * @param channelId 频道ID
     * @param uid 用户ID
     * @param oldState 旧状态
     * @param newState 新状态
     * @param elapsed 耗时（毫秒）
     */
    open fun onAudioSubscribeStateChanged(channelId: String, uid: String, oldState: String, newState: String, elapsed: Int) {}

    /**
     * 音量指示回调
     *
     * @param speakers 说话者列表，包含uid和volume
     */
    open fun onVolumeIndication(speakers: List<VolumeInfo>) {}

    /**
     * 错误回调（可选）
     *
     * @param code 错误码（自定义）
     * @param message 错误信息
     */
    open fun onError(code: Int, message: String) {}

    /**
     * 数据流消息回调
     *
     * @param uid 发送方用户ID（未知时可能为空字符串）
     * @param streamId 数据流ID
     * @param data 二进制数据
     */
    open fun onStreamMessage(uid: String, streamId: Int, data: ByteArray) {}

    /**
     * 数据流消息错误回调
     *
     * @param uid 发送方用户ID（未知时可能为空字符串）
     * @param streamId 数据流ID
     * @param code 错误码
     * @param missed 丢失消息数
     * @param cached 缓存消息数
     */
    open fun onStreamMessageError(uid: String, streamId: Int, code: Int, missed: Int, cached: Int) {}

    /**
     * 频道消息回调
     *
     * @param uid 发送方用户ID
     * @param message 消息内容（JSON字符串）
     */
    open fun onChannelMessage(uid: String, message: String) {}
}

/**
 * 音量信息
 */
data class VolumeInfo(
    val uid: String,
    val volume: Int
)
