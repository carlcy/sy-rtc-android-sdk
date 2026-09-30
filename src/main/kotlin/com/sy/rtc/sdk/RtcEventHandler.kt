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
     * 开始第 [attempt] 次重连（共 [maxAttempts] 次），[delayMs] 后执行。[reason] 为 `signaling` 或 `ice`。
     * 策略见 [ReconnectPolicy]，与 iOS 相同。
     */
    open fun onReconnecting(reason: String, attempt: Int, maxAttempts: Int, delayMs: Long) {}

    /** 重连成功。[reason] 为 `signaling` 或 `ice`，同时也会回调 [onRejoinChannelSuccess]。 */
    open fun onReconnected(reason: String) {}

    /** 重连次数用完。之后还会回调 `onError(1003)`；需要业务层 leave 后重新 join。 */
    open fun onReconnectFailed(reason: String) {}

    /**
     * 网络质量，每 2 秒一轮，与 iOS 相同：
     * 先回调一次本端 uid（`join` 时的 uid），质量为所有对端链路中最差的一档；
     * 然后每个对端各一次（该对端链路的质量）。房间里没有对端时只回调本端 `unknown`。
     *
     * 目前 tx 与 rx 取同一个值（由该链路 RTT 和丢包算出，见 README「网络质量档位」）。
     *
     * @param uid 本端 uid 或对端 uid
     * @param txQuality 上行质量
     * @param rxQuality 下行质量
     */
    open fun onNetworkQuality(uid: String, txQuality: String, rxQuality: String) {}

    /**
     * Token 将在 30 秒内过期（按 Token 里的 `expireAt` 计时）。向业务后端要新 Token 后调用 `renewToken`。
     */
    open fun onTokenPrivilegeWillExpire() {}

    /**
     * Token 已过期，需要立即 `renewToken`。
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
     * @param code 错误码，取值见 [RtcErrorCode]（三端一致）
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
     * 被服务端踢出房间（信令 type=kicked，或 poll 发现 kicked=1）。
     * 非 SFU 强制切断；SDK 会 leave 并回调。
     */
    open fun onKicked(channelId: String, reason: String) {}

    /**
     * 服务端静音/解静音本端或远端（信令 mute-audio/unmute-audio，或 poll）。
     */
    open fun onServerMuteAudio(uid: String, muted: Boolean) {}

    /**
     * 频道消息回调
     *
     * @param uid 发送方用户ID
     * @param message 消息内容（JSON字符串）
     */
    open fun onChannelMessage(uid: String, message: String) {}

    /**
     * 对端通过信令更新的流附加信息。
     *
     * 这是 `sy-extra:` 前缀的频道消息（与 iOS 同格式），不是 SFU 的流元数据。
     * 这类消息不会再回调 [onChannelMessage]。
     */
    open fun onStreamExtraInfoUpdated(uid: String, extra: String) {}

    /**
     * DataChannel 上的 SEI 风格消息。
     *
     * 载荷带端上前缀，走数据通道，不是 H.264 码流 SEI。
     * 原始字节（含前缀）仍会通过 [onStreamMessage] 给出。
     */
    open fun onSeiMessage(uid: String, streamId: Int, data: ByteArray) {}

    /**
     * 对端开关了自己的摄像头（`user-media` 信令，与 iOS `onUserMuteVideo` 相同）。
     * 同时仍回调 [onRemoteVideoStateChanged]（reason `remote-mute`）。
     */
    open fun onUserMuteVideo(uid: String, muted: Boolean) {}
}

/**
 * 音量信息
 */
data class VolumeInfo(
    val uid: String,
    val volume: Int
)
