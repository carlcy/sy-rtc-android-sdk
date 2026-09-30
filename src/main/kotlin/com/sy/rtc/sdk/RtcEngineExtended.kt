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
 * 美颜配置。
 *
 * [enabled] 时，SDK 在送入编码器之前抬高 Y 平面（[lighteningLevel]）。
 * 这是端上预处理，不是云端美颜。自定义 [VideoFrameProcessor] 会替换这条内置处理。
 */
data class BeautyOptions(
    val enabled: Boolean = false,
    val lighteningLevel: Double = 0.5,
    val rednessLevel: Double = 0.1,
    val smoothnessLevel: Double = 0.5
)

/**
 * 本地采集帧钩子。返回原帧或新帧。
 *
 * SDK 会在把帧交给编码器后释放「新返回的帧」。若要自行持有，请先 retain。
 * 内置美颜与此钩子二选一：设置本处理器后不再叠加内置提亮。
 */
interface VideoFrameProcessor {
    fun onFrameCaptured(frame: org.webrtc.VideoFrame): org.webrtc.VideoFrame
}

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
 * 画质档位，与控制面 Token 的 `qualityTier` 一致：`audio` | `sd` | `hd` | `fhd`。
 *
 * 分辨率与码率是本地编码预设，不是 SFU 下发的强制模板。
 */
enum class VideoQualityTier(
    val apiValue: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrateKbps: Int
) {
    AUDIO("audio", 0, 0, 0, 0),
    SD("sd", 640, 480, 15, 500),
    HD("hd", 1280, 720, 24, 1500),
    FHD("fhd", 1920, 1080, 30, 3000);

    companion object {
        @JvmStatic
        fun fromApi(value: String?): VideoQualityTier? {
            val raw = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.apiValue == raw || it.name.lowercase() == raw }
        }
    }
}

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

