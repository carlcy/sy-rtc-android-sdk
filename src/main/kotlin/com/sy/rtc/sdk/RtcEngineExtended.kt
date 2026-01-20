package com.sy.rtc.sdk

/**
 * RTC引擎扩展类
 * 
 * 包含所有新增功能的API定义
 * 这些方法需要在 RtcEngine.kt 中实现
 */

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
