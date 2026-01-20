package com.sy.rtc.sdk

import android.content.Context

/**
 * RTC引擎主类
 * 
 * SY RTC 引擎主类，提供实时音视频通信功能
 * 
 * @property appId 应用ID
 * @property eventHandler 事件处理器
 */
class RtcEngine private constructor() {
    private var appId: String? = null
    private var eventHandler: RtcEventHandler? = null
    private var impl: RtcEngineImpl? = null
    private var context: Context? = null

    companion object {
        /**
         * 创建RTC引擎实例
         */
        @JvmStatic
        fun create(): RtcEngine {
            return RtcEngine()
        }
    }

    /**
     * 初始化引擎
     * 
     * @param appId 应用ID
     * @param context Android Context（可选，如果未提供则使用 Application Context）
     */
    fun init(appId: String, context: Context? = null) {
        this.appId = appId
        this.context = context
        
        // 获取 Context
        val ctx = context ?: try {
            // 尝试从 Application 获取 Context
            val applicationClass = Class.forName("android.app.ActivityThread")
            val currentApplication = applicationClass.getMethod("currentApplication").invoke(null) as? android.app.Application
            currentApplication ?: throw IllegalStateException("无法获取 Application Context")
        } catch (e: Exception) {
            throw IllegalStateException("无法获取 Context，请传入 context 参数", e)
        }
        
        // 创建实现类
        impl = RtcEngineImpl(ctx, appId)
        impl?.initialize()
    }

    /**
     * 加入频道
     * 
     * @param channelId 频道ID
     * @param uid 用户ID
     * @param token 鉴权Token
     */
    fun join(channelId: String, uid: String, token: String) {
        impl?.join(channelId, uid, token)
        // 模拟用户加入事件（实际应由原生SDK触发）
        eventHandler?.onUserJoined(uid, 0)
    }

    /**
     * 离开频道
     */
    fun leave() {
        impl?.leave()
    }

    /**
     * 启用/禁用本地音频
     * 
     * @param enabled true为启用，false为禁用
     */
    fun enableLocalAudio(enabled: Boolean) {
        impl?.enableLocalAudio(enabled)
    }

    /**
     * 静音本地音频
     * 
     * @param muted true为静音，false为取消静音
     */
    fun muteLocalAudio(muted: Boolean) {
        impl?.muteLocalAudio(muted)
    }

    /**
     * 设置客户端角色
     * 
     * @param role 角色：RtcClientRole.HOST 或 RtcClientRole.AUDIENCE
     */
    fun setClientRole(role: RtcClientRole) {
        impl?.setClientRole(role)
    }

    /**
     * 设置事件处理器
     * 
     * @param handler 事件处理器
     */
    fun setEventHandler(handler: RtcEventHandler) {
        this.eventHandler = handler
        // 将事件处理器传递给实现类
        impl?.eventHandler = handler
    }

    /**
     * 释放资源
     */
    fun release() {
        eventHandler = null
        appId = null
    }

    // ==================== 音频路由控制 ====================

    /**
     * 开启/关闭扬声器
     */
    fun setEnableSpeakerphone(enabled: Boolean) {
        impl?.setEnableSpeakerphone(enabled)
    }

    /**
     * 设置默认音频路由
     */
    fun setDefaultAudioRouteToSpeakerphone(enabled: Boolean) {
        impl?.setDefaultAudioRouteToSpeakerphone(enabled)
    }

    /**
     * 检查扬声器状态
     */
    fun isSpeakerphoneEnabled(): Boolean {
        return impl?.isSpeakerphoneEnabled() ?: false
    }

    // ==================== 远端音频控制 ====================

    /**
     * 静音指定远端用户
     */
    fun muteRemoteAudioStream(uid: String, muted: Boolean) {
        impl?.muteRemoteAudioStream(uid, muted)
    }

    /**
     * 静音所有远端用户
     */
    fun muteAllRemoteAudioStreams(muted: Boolean) {
        impl?.muteAllRemoteAudioStreams(muted)
    }

    /**
     * 调节指定用户音量（0-100）
     */
    fun adjustUserPlaybackSignalVolume(uid: String, volume: Int) {
        impl?.adjustUserPlaybackSignalVolume(uid, volume)
    }

    /**
     * 调节所有远端用户音量（0-100）
     */
    fun adjustPlaybackSignalVolume(volume: Int) {
        impl?.adjustPlaybackSignalVolume(volume)
    }

    // ==================== Token 刷新 ====================

    /**
     * 更新 Token
     */
    fun renewToken(token: String) {
        impl?.renewToken(token)
    }

    // ==================== 音频参数配置 ====================

    /**
     * 设置音频配置
     */
    fun setAudioProfile(profile: String, scenario: String) {
        impl?.setAudioProfile(profile, scenario)
    }

    /**
     * 启用音频模块
     */
    fun enableAudio() {
        impl?.enableAudio()
    }

    /**
     * 禁用音频模块
     */
    fun disableAudio() {
        impl?.disableAudio()
    }

    // ==================== 音频设备管理 ====================

    /**
     * 获取音频采集设备列表
     */
    fun enumerateRecordingDevices(): List<AudioDeviceInfo> {
        return impl?.enumerateRecordingDevices() ?: emptyList()
    }

    /**
     * 获取音频播放设备列表
     */
    fun enumeratePlaybackDevices(): List<AudioDeviceInfo> {
        return impl?.enumeratePlaybackDevices() ?: emptyList()
    }

    /**
     * 设置音频采集设备
     */
    fun setRecordingDevice(deviceId: String) {
        impl?.setRecordingDevice(deviceId)
    }

    /**
     * 设置音频播放设备
     */
    fun setPlaybackDevice(deviceId: String) {
        impl?.setPlaybackDevice(deviceId)
    }

    /**
     * 获取采集音量（0-255）
     */
    fun getRecordingDeviceVolume(): Int {
        return impl?.getRecordingDeviceVolume() ?: 0
    }

    /**
     * 设置采集音量（0-255）
     */
    fun setRecordingDeviceVolume(volume: Int) {
        impl?.setRecordingDeviceVolume(volume)
    }

    /**
     * 获取播放音量（0-255）
     */
    fun getPlaybackDeviceVolume(): Int {
        return impl?.getPlaybackDeviceVolume() ?: 0
    }

    /**
     * 设置播放音量（0-255）
     */
    fun setPlaybackDeviceVolume(volume: Int) {
        impl?.setPlaybackDeviceVolume(volume)
    }

    // ==================== 网络质量监控 ====================

    /**
     * 获取连接状态
     */
    fun getConnectionState(): String {
        return impl?.getConnectionState() ?: "disconnected"
    }

    /**
     * 获取网络类型
     */
    fun getNetworkType(): String {
        return impl?.getNetworkType() ?: "unknown"
    }

    // ==================== 音频采集控制 ====================

    /**
     * 调节采集音量（0-400，100为原始音量）
     */
    fun adjustRecordingSignalVolume(volume: Int) {
        impl?.adjustRecordingSignalVolume(volume)
    }

    /**
     * 静音采集信号
     */
    fun muteRecordingSignal(muted: Boolean) {
        impl?.muteRecordingSignal(muted)
    }

    // ==================== 视频基础功能 ====================

    /**
     * 启用视频模块
     */
    fun enableVideo() {
        impl?.enableVideo()
    }

    /**
     * 禁用视频模块
     */
    fun disableVideo() {
        impl?.disableVideo()
    }

    /**
     * 启用/禁用本地视频采集
     */
    fun enableLocalVideo(enabled: Boolean) {
        impl?.enableLocalVideo(enabled)
    }

    /**
     * 设置视频编码配置
     */
    fun setVideoEncoderConfiguration(config: VideoEncoderConfiguration) {
        impl?.setVideoEncoderConfiguration(config)
    }
    
    /**
     * 设置视频编码配置（简化版）
     */
    fun setVideoEncoderConfiguration(width: Int, height: Int, frameRate: Int, bitrate: Int) {
        val config = VideoEncoderConfiguration(
            width = width,
            height = height,
            frameRate = frameRate,
            bitrate = bitrate
        )
        setVideoEncoderConfiguration(config)
    }
    
    /**
     * 设置音频质量
     * 
     * @param quality 音频质量等级：low/medium/high/ultra
     */
    fun setAudioQuality(quality: String) {
        impl.setAudioQuality(quality)
    }

    /**
     * 开启视频预览
     */
    fun startPreview() {
        impl?.startPreview()
    }

    /**
     * 停止视频预览
     */
    fun stopPreview() {
        impl?.stopPreview()
    }

    /**
     * 静音本地视频
     */
    fun muteLocalVideoStream(muted: Boolean) {
        impl?.muteLocalVideoStream(muted)
    }

    /**
     * 静音远端视频
     */
    fun muteRemoteVideoStream(uid: String, muted: Boolean) {
        impl?.muteRemoteVideoStream(uid, muted)
    }

    /**
     * 静音所有远端视频
     */
    fun muteAllRemoteVideoStreams(muted: Boolean) {
        impl?.muteAllRemoteVideoStreams(muted)
    }

    // ==================== 视频渲染 ====================

    /**
     * 设置本地视频视图
     */
    fun setupLocalVideo(viewId: Int) {
        impl?.setupLocalVideo(viewId)
    }

    /**
     * 设置远端视频视图
     */
    fun setupRemoteVideo(uid: String, viewId: Int) {
        impl?.setupRemoteVideo(uid, viewId)
    }

    // ==================== 屏幕共享 ====================

    /**
     * 开始屏幕共享
     */
    fun startScreenCapture(config: ScreenCaptureConfiguration) {
        impl?.startScreenCapture(config)
    }

    /**
     * 停止屏幕共享
     */
    fun stopScreenCapture() {
        impl?.stopScreenCapture()
    }

    /**
     * 更新屏幕共享配置
     */
    fun updateScreenCaptureConfiguration(config: ScreenCaptureConfiguration) {
        impl?.updateScreenCaptureConfiguration(config)
    }

    // ==================== 视频增强 ====================

    /**
     * 设置美颜选项
     */
    fun setBeautyEffectOptions(options: BeautyOptions) {
        impl?.setBeautyEffectOptions(options)
    }

    /**
     * 视频截图
     */
    fun takeSnapshot(uid: String, filePath: String) {
        impl?.takeSnapshot(uid, filePath)
    }

    // ==================== 音乐文件播放 ====================

    /**
     * 开始播放音乐文件
     */
    fun startAudioMixing(config: AudioMixingConfiguration) {
        impl?.startAudioMixing(config)
    }

    /**
     * 停止播放音乐文件
     */
    fun stopAudioMixing() {
        impl?.stopAudioMixing()
    }

    /**
     * 暂停播放音乐文件
     */
    fun pauseAudioMixing() {
        impl?.pauseAudioMixing()
    }

    /**
     * 恢复播放音乐文件
     */
    fun resumeAudioMixing() {
        impl?.resumeAudioMixing()
    }

    /**
     * 调节音乐文件音量（0-100）
     */
    fun adjustAudioMixingVolume(volume: Int) {
        impl?.adjustAudioMixingVolume(volume)
    }

    /**
     * 获取音乐文件播放进度（毫秒）
     */
    fun getAudioMixingCurrentPosition(): Int {
        return impl?.getAudioMixingCurrentPosition() ?: 0
    }

    /**
     * 设置音乐文件播放位置（毫秒）
     */
    fun setAudioMixingPosition(position: Int) {
        impl?.setAudioMixingPosition(position)
    }

    // ==================== 音效文件播放 ====================

    /**
     * 播放音效
     */
    fun playEffect(soundId: Int, config: AudioEffectConfiguration) {
        impl?.playEffect(soundId, config)
    }

    /**
     * 停止音效
     */
    fun stopEffect(soundId: Int) {
        impl?.stopEffect(soundId)
    }

    /**
     * 停止所有音效
     */
    fun stopAllEffects() {
        impl?.stopAllEffects()
    }

    /**
     * 设置音效音量（0-100）
     */
    fun setEffectsVolume(volume: Int) {
        impl?.setEffectsVolume(volume)
    }

    /**
     * 预加载音效
     */
    fun preloadEffect(soundId: Int, filePath: String) {
        impl?.preloadEffect(soundId, filePath)
    }

    /**
     * 卸载音效
     */
    fun unloadEffect(soundId: Int) {
        impl?.unloadEffect(soundId)
    }

    // ==================== 音频录制 ====================

    /**
     * 开始客户端录音
     */
    fun startAudioRecording(config: AudioRecordingConfiguration) {
        impl?.startAudioRecording(config)
    }

    /**
     * 停止客户端录音
     */
    fun stopAudioRecording() {
        impl?.stopAudioRecording()
    }

    // ==================== 数据流 ====================

    /**
     * 创建数据流
     */
    fun createDataStream(reliable: Boolean, ordered: Boolean): Int {
        return impl?.createDataStream(reliable, ordered) ?: 0
    }

    /**
     * 发送数据流消息
     */
    fun sendStreamMessage(streamId: Int, data: ByteArray) {
        impl?.sendStreamMessage(streamId, data)
    }

    // ==================== 旁路推流 ====================

    /**
     * 开始旁路推流
     */
    fun startRtmpStreamWithTranscoding(url: String, transcoding: LiveTranscoding) {
        impl?.startRtmpStreamWithTranscoding(url, transcoding)
    }

    /**
     * 停止旁路推流
     */
    fun stopRtmpStream(url: String) {
        impl?.stopRtmpStream(url)
    }

    /**
     * 更新旁路推流转码配置
     */
    fun updateRtmpTranscoding(transcoding: LiveTranscoding) {
        impl?.updateRtmpTranscoding(transcoding)
    }
}

// ==================== 配置数据类 ====================

/**
 * 音频设备信息
 */
data class AudioDeviceInfo(
    val deviceId: String,
    val deviceName: String
)

/**
 * 视频编码配置
 */
data class VideoEncoderConfiguration(
    val width: Int = 640,
    val height: Int = 480,
    val frameRate: Int = 15,
    val minFrameRate: Int = -1,
    val bitrate: Int = 0,
    val minBitrate: Int = -1,
    val orientationMode: String = "adaptative",
    val degradationPreference: String = "maintainQuality",
    val mirrorMode: String = "auto"
)

/**
 * 屏幕共享配置
 */
data class ScreenCaptureConfiguration(
    val captureMouseCursor: Boolean = true,
    val captureWindow: Boolean = false,
    val frameRate: Int = 15,
    val bitrate: Int = 0,
    val width: Int = 0,
    val height: Int = 0
)

/**
 * 美颜配置
 */
data class BeautyOptions(
    val enabled: Boolean = false,
    val lighteningLevel: Double = 0.5,
    val rednessLevel: Double = 0.1,
    val smoothnessLevel: Double = 0.5
)

/**
 * 音频混音配置
 */
data class AudioMixingConfiguration(
    val filePath: String,
    val loopback: Boolean = false,
    val replace: Boolean = false,
    val cycle: Int = 1,
    val startPos: Int = 0
)

/**
 * 音效配置
 */
data class AudioEffectConfiguration(
    val filePath: String,
    val loopCount: Int = 1,
    val publish: Boolean = false,
    val startPos: Int = 0
)

/**
 * 音频录制配置
 */
data class AudioRecordingConfiguration(
    val filePath: String,
    val sampleRate: Int = 32000,
    val channels: Int = 1,
    val codecType: String = "aacLc",
    val quality: String = "medium"
)

/**
 * 旁路推流配置
 */
data class LiveTranscoding(
    val width: Int = 360,
    val height: Int = 640,
    val videoBitrate: Int = 400,
    val videoFramerate: Int = 15,
    val lowLatency: Boolean = false,
    val videoGop: Int = 30,
    val backgroundColor: Int = 0x000000,
    val watermarkUrl: String? = null,
    val transcodingUsers: List<TranscodingUser>? = null
)

/**
 * 转码用户配置
 */
data class TranscodingUser(
    val uid: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 0.0,
    val height: Double = 0.0,
    val zOrder: Int = 0,
    val alpha: Double = 1.0
)
