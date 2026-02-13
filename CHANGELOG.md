# SY RTC Android SDK 更新日志

## 1.3.0

### 语音功能修复与稳定性

- **语音控制**：`enableLocalAudio` / `muteLocalAudio` 仅控制 WebRTC `localAudioTrack`，不再误操作 `AudioRecord`，与推流链路一致。
- **音频模块**：`enableAudio` / `disableAudio` 改为控制 `localAudioTrack` 的启用状态，与语聊行为一致。
- **参数校验**：`join(channelId, uid, token)` 增加空/空白校验，非法时回调 `onError(1000, "channelId/uid/token 不能为空")`；已加入时再次 join 回调 `onError(1000, "已经加入频道，请先 leave()")`。
- **API 返回值**：`setRecordingDevice` / `setPlaybackDevice` 返回 `Int`（0 成功，-1 失败）；`startAudioRecording` 返回 `Int`（0 成功，-1 失败或已在录制）。

### 升级说明

- 依赖：`com.github.carlcy:sy-rtc-android-sdk:v1.3.0`

---

## 1.2.0

- 版本与 Flutter / iOS 统一为 1.2.0；示例与文档更新。
