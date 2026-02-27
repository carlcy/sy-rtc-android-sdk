# SY RTC Android SDK 更新日志

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
