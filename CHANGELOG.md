# SY RTC Android SDK 更新日志

## 未发布

- 新增 `RtcErrorCode`，三端取值统一（见 README「错误码」）。信令 `kicked` 帧带凭证码时 `onError` 报 4031 / 4032 / 4033，而不是 1004；信令 `error` 帧的 403 和凭证码原样透传。
- 修复：信令错误的文本取自 `data.message`（服务端实际字段），此前一直显示「信令错误」。
- 新增 Token 过期提醒：解析 Token 的 `expireAt`（兼容 JWT `exp`），过期前 30 秒回调 `onTokenPrivilegeWillExpire`，到期回调 `onRequestToken`。此前 Android 从不回调这两个方法。也处理 `token-will-expire` / `token-expired` 信令。

## 3.2.0

### 接入

- 客户依赖改为 JitPack 坐标：`implementation("com.github.carlcy:sy-rtc-android-sdk:v3.2.0")`。Demo 默认使用同一行，不再下载或解压 AAR。
- `publish.gradle` 与 `VERSION` 对齐；无签名密钥时 `publishToMavenLocal` / JitPack 仍可构建。Maven Central 需 owner 配置 `io.github.carlcy` 与签名，见 `PUBLISH_GUIDE.md`。

### 新增（不改变已有方法签名）

- `RtcEngine.setVideoQuality(VideoQualityTier)` / `setVideoQuality("audio"|"sd"|"hd"|"fhd")` — 本地画质档位
- `RoomService.switchQualityTier` — `POST /api/rtc/quality/switch`（用户 JWT）
- `RoomService.renewToken` — `POST /api/rtc/token/renew`，参数与 `fetchToken` 相同
- `ScreenCaptureService` — 内置 `mediaProjection` 前台服务（manifest 合并，含 `FOREGROUND_SERVICE_MEDIA_PROJECTION`）。Android 10+ 由 `startScreenCapture` 自动启动、`stopScreenCapture` / 离开频道时停止；宿主无需声明。可用 `ScreenCaptureService.enabled = false` 关闭。
- `RoomService.setRoomAttribute` / `getRoomAttributes` / `deleteRoomAttribute` — `POST /api/rtc/channel/meta/set|get|delete`（用户 JWT）
- 拉 Token / 续期遇到业务码 4031（停用）、4032（吊销）、4033（过期）时，callback 收到 `RtcCredentialException`
- `RoomInfo.currentSeats`、`RoomInfo.attrs`（缺省为空，旧构造仍可用）
- `RtcEngine.VERSION = "3.2.0"`
- 端上能力（不是 SFU）：`onNetworkQuality` 使用本机 RTT/丢包；`switchCamera`；`getAudioRoute` / `onAudioRoutingChanged`；`setVideoFrameProcessor` 与编码前提亮；`startScreenCapture(intent, config)`；本地/远端静音状态；PCM 音量提示；`setStreamExtraInfo`；`enableCustomVideoCapture` / `pushExternalVideoFrame`；ICE/信令重连与 `onRejoinChannelSuccess`；`sendSei`（DataChannel 前缀，不是码流 SEI）

### 行为

- 重连策略与 iOS 统一：最多 5 次，间隔 1/2/4/8/16 秒；ICE 断开时 offer 发起方 `restartIce` 并重发 offer（此前 restartIce 不重协商，实际无效）。新增 `onReconnecting` / `onReconnected` / `onReconnectFailed`；连接状态 reason 改为 `joining` / `join_success` / `rejoin_success` / `leaving`，与 iOS 相同。renewToken 重连信令后不再重复回调 `onJoinChannelSuccess`

- 网络质量阈值与 iOS 统一（参考即构）：excellent <100ms/<1%，good <200ms/<3%，poor <400ms/<8%，bad <800ms/<20%，其余 down。档位名 `medium`→`poor`、`die`→`down`，与 iOS / Flutter 相同

- 跨端互通：静音通知改用 iOS 同款信令 `user-media`，附加信息改用 `sy-extra:` 前缀频道消息；旧 JSON 仍可接收。SDK 保留消息不再触发 `onChannelMessage`
- 新增 `onUserMuteVideo`、`useFrontCamera`、`getStreamExtraInfo`；`getNetworkType` 改为真实值（此前固定 `unknown`）；`isRemoteAudioMuted` / `isRemoteVideoMuted` 计入对端自己静音；`setStreamExtraInfo` 超过 1024 字节返回 -2

- `renewToken` 在已进房时用新 Token 重连信令（不发 leave，不拆 PeerConnection）

## 3.1.0

### 重大变更 / Breaking

- **移除 CDN 旁路推流（直播）能力**：删除 `startRtmpStreamWithTranscoding` / `stopRtmpStream` / `updateRtmpTranscoding` 及 `LiveTranscoding` / `TranscodingUser`；不再调用 `/api/rtc/live/*`。
- **产品功能位**：`hasLiveFeature` / feature `live` 改为 `hasRtcFeature` / feature `rtc`（音视频一体）。`hasVoiceFeature` 保留为兼容别名。

### 新增

- `onKicked(channelId, reason)` / `onServerMuteAudio(uid, muted)` — 控制面踢人/静音信令回调（非 SFU 强制断流）
- `setupLocalVideo(ViewGroup)` / `setupRemoteVideo(uid, ViewGroup)` — 供 Flutter PlatformView 绑定

## 3.0.1

- Version align with iOS/Flutter RTC SDKs.
- No functional SFU change (control-plane client SDK).

## 3.0.0

### 重大变更

- **适配 Flutter 3.38+ / Kotlin 2.0**：升级 Kotlin 至 2.0.21、AGP 至 8.7.3、Gradle 至 8.9
- compileSdk 从 34 升至 35
- 添加 `jitpack.yml` 确保 JitPack 使用 JDK 17 构建

### 兼容性说明

- 搭配 Flutter SDK `3.0.0` 使用；旧项目可继续使用 `2.1.1`

## 2.1.1

### 新增功能

- `RtcEngine.setChannelProfile(profile)` — 设置频道场景（通信/直播）
- `RtcEngine.enableAudioVolumeIndication(interval, smooth, reportVad)` — 启用音量提示回调
- `RoomService.setUserId(uid)` — 设置用户 ID 用于房间创建等需要身份认证的操作

### Bug 修复

- 统一所有版本号为 2.1.1

## 2.1.0

### 新增 RoomService — 房间管理服务

- `RoomService` 类：房间管理 + Token 获取
  - `getRoomList()` / `createRoom()` / `closeRoom()` / `getRoomDetail()`
  - `fetchToken()` / `getOnlineCount()`
- `RoomInfo` 数据类

---

## 2.0.0 (Breaking Change)

### 架构调整

SDK 重新定位为纯 RTC 传输层，移除所有业务逻辑，对齐声网/即构等主流 RTC SDK 设计。

### 移除

- 房间管理、麦位管理、用户管理（踢人/禁言/封禁）、聊天、礼物等业务 API 及回调

### 新增

- 频道生命周期回调：`onJoinChannelSuccess`、`onLeaveChannel`、`onRejoinChannelSuccess`
- 连接与网络：`onConnectionStateChanged`、`onNetworkQuality`、`onRtcStats`
- Token 管理：`onTokenPrivilegeWillExpire`、`onRequestToken`、`renewToken()`
- 音频状态：`onLocalAudioStateChanged`、`onRemoteAudioStateChanged`、`onUserMuteAudio`、`onAudioRoutingChanged`
- 视频状态：`onLocalVideoStateChanged`、`onRemoteVideoStateChanged`、`onFirstRemoteVideoDecoded`、`onFirstRemoteVideoFrame`、`onVideoSizeChanged`
- 数据流：`createDataStream`、`sendStreamMessage`、`onStreamMessage`、`onStreamMessageError`

### 迁移指南

业务逻辑请通过 `sendChannelMessage` 自定义 JSON 协议实现。

---

## 1.5.0

### 新功能

- **房间管理**：`updateRoomInfo`、`setRoomNotice`、`setRoomManager`
- **麦位管理**：`takeSeat`、`leaveSeat`、`requestSeat`、`handleSeatRequest`、`inviteToSeat`、`handleSeatInvitation`、`kickFromSeat`、`lockSeat`/`unlockSeat`、`muteSeat`/`unmuteSeat`
- **用户管理**：`kickUser`、`muteUser`、`banUser`
- **房间聊天**：`sendRoomMessage`
- **礼物系统**：`sendGift`
- **14 个新回调**：房间信息/公告/管理员变更、座位操作、用户管理、聊天、礼物等

### 升级说明

- 依赖：`com.sy.rtc:sy-rtc-android-sdk:1.5.0`

---

## 1.4.1

### 修复

- **Demo 地址**：SDK 默认信令地址和示例 App 改回 IP 直连（域名备案进行中）

### 升级说明

- 依赖：`com.sy.rtc:sy-rtc-android-sdk:1.4.1`

---

## 1.4.0

### 新功能

- **频道消息**：新增 `sendChannelMessage(message)` 方法和 `onChannelMessage(uid, message)` 回调，支持向频道内所有用户广播自定义消息
- **在线人数修复**：修复后加入的用户收到 `user-list` 时不触发 `onUserJoined` 的问题，现在在线人数对所有用户一致

### 改进

- **Demo 地址**：示例 App 中 API/信令地址改为域名

### 升级说明

- 依赖：`com.sy.rtc:sy-rtc-android-sdk:1.4.0`

---

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
