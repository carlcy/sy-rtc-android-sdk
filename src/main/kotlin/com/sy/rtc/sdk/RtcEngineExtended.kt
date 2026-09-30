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
 * 本地录音配置。与 iOS 相同：
 *
 * - [codecType]：`aac` / `aacLc` / `m4a` → AAC，MPEG-4 容器（文件建议用 `.m4a`）；`wav` / `pcm` → 16 bit WAV。
 *   **不支持 mp3**（此前 Android 的 `mp3` 实际输出 AMR-NB），传入会回调 `onError(1000)` 并返回 -1。
 * - 在频道内：录 WebRTC 已有的音频（本端采集 + 远端解码），不另开麦克风，混成单声道。
 *   [includeLocal] / [includeRemote] 控制是否包含本端、远端。本端静音时录到的是静音。
 * - 不在频道内：只录麦克风（系统录音器，仅 AAC）。录音途中 join 会与 WebRTC 抢麦克风，请 join 后再开始。
 * - [channels] 目前只支持 1（混音输出为单声道）；[quality]：`low` 32 kbps、`medium` 64 kbps、`high` 128 kbps（仅 AAC）。
 * - leave 时自动停止并写完文件。
 */
data class AudioRecordingConfiguration(
    val filePath: String,
    val sampleRate: Int = 32000,
    val channels: Int = 1,
    val codecType: String = "aacLc",
    val quality: String = "medium",
    val includeLocal: Boolean = true,
    val includeRemote: Boolean = true,
) {
    /** AAC 码率（bit/s）。 */
    val aacBitrate: Int
        get() = when (quality.lowercase()) {
            "low" -> 32_000
            "high" -> 128_000
            else -> 64_000
        }
}

