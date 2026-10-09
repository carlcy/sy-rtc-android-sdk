# SY RTC Android SDK

实时音视频 Android SDK（Kotlin / Java）。控制面是 SY 的 `rtc-backend-go`，媒体层是端侧 WebRTC。对齐即构 Express 的是**进房、角色、信令**这条主路径，不是 ZegoExpress 全 API。

当前版本：**3.3.0**（JitPack tag `v3.3.0`）。

## 快速开始

和即构 Express 一样，只加仓库和一行带版本号的依赖，不下载、不解压 AAR / zip。

| 步骤 | 即构 ZEGO Express | SY RTC |
|------|-------------------|--------|
| 仓库 | `maven { url 'https://maven.zego.im' }` | `maven { url 'https://jitpack.io' }` |
| 依赖 | `implementation 'im.zego:express-video:x.y.z'` | `implementation 'com.github.carlcy:sy-rtc-android-sdk:v3.3.0'` |
| 初始化 | `ZegoExpressEngine.createEngine` | `RtcEngine.create()` + `init(appId, context)` |
| 鉴权 | AppSign 或 Token | 业务后端用 AppSecret 换 Token，客户端只拿字符串 |
| 进房 | `loginRoom` | `join(channelId, uid, token)` |

### 1. 添加仓库

Android Gradle Plugin 7.1+ 写在根目录 `settings.gradle`：

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

Kotlin DSL（`settings.gradle.kts`）：

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

AGP 低于 7.1 时，改写到根 `build.gradle` 的 `allprojects.repositories`，地址同样是 `https://jitpack.io`。

### 2. 添加依赖

`app/build.gradle`：

```gradle
dependencies {
    implementation 'com.github.carlcy:sy-rtc-android-sdk:v3.3.0'
}
```

Kotlin DSL：

```kotlin
dependencies {
    implementation("com.github.carlcy:sy-rtc-android-sdk:v3.3.0")
}
```

把 `v3.3.0` 换成 [Releases](https://github.com/carlcy/sy-rtc-android-sdk/releases) 里的 tag。坐标里的 `carlcy` 是本仓库的 GitHub 用户，客户不要改成自己的用户名。

### 3. 权限

`minSdk` 21。`AndroidManifest.xml`：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CAMERA" />
```

Android 6.0+ 在运行时申请 `RECORD_AUDIO` 和 `CAMERA`。

### 4. 初始化

```kotlin
val engine = RtcEngine.create()
engine.init(appId, applicationContext)
engine.setApiBaseUrl("https://your-api.example.com")
engine.setSignalingServerUrl("wss://your-api.example.com/ws/signaling")
engine.setEventHandler(object : RtcEventHandler() {
    override fun onJoinChannelSuccess(channelId: String, uid: String, elapsed: Int) {}
    override fun onUserJoined(uid: String, elapsed: Int) {}
    override fun onUserOffline(uid: String, reason: String) {}
    override fun onTokenPrivilegeWillExpire() {
        // 向业务后端再要一张 Token，然后 engine.renewToken(newToken)
    }
})
```

`appId` 来自 SY 控制台。`AppSecret` 只放在业务后端。

### 5. 获取 Token

客户端不能自己签 Token。业务后端：

```bash
curl -sS -X POST 'https://your-api.example.com/api/server/rtc/token' \
  -H 'Content-Type: application/json' \
  -H 'X-App-Id: YOUR_APP_ID' \
  -H 'X-App-Secret: YOUR_APP_SECRET' \
  -d '{"appId":"YOUR_APP_ID","channelId":"demo_room","uid":"user_1","expireHours":24,"role":"publisher","qualityTier":"sd"}'
```

- `role`：`host` | `audience` | `publisher` | `subscriber`
- `qualityTier`：`audio` | `sd` | `hd` | `fhd`（分钟计费档位）

调试可以用 `RoomService`（生产环境改为用户 JWT，不要把 AppSecret 打进包）：

```kotlin
val rooms = RoomService(apiBaseUrl, appId)
rooms.setAuthToken(userJwt)
rooms.getToken(channelId, uid, role = "publisher", qualityTier = "sd") { token, error ->
    if (token != null) engine.join(channelId, uid, token)
}
```

### 6. 加入频道

```kotlin
engine.join(channelId, uid, token)
engine.enableLocalAudio(true)
```

`join` 会把 RTC Token 接到信令地址上：`wss://.../ws/signaling?token=`。

离开与释放：

```kotlin
engine.leave()
engine.release()
```

Token 快过期时：

```kotlin
rooms.renewToken(channelId, uid) { newToken, error ->
    if (error is RtcCredentialException) {
        // 4031 停用，4032 吊销，4033 过期
        return@renewToken
    }
    if (newToken != null) engine.renewToken(newToken)
}
```

`RoomService.renewToken` 对应 `POST /api/rtc/token/renew`。`RtcEngine.renewToken` 用新 Token 重连信令，不发送 leave，也不拆掉当前媒体连接。拉 Token 和续期若返回 4031 / 4032 / 4033，callback 的异常是 `RtcCredentialException`。

### 7. 媒体服务器（LiveKit）

服务端配置了 LiveKit 节点时，拉 Token 带 `meta=true`，把返回的 JSON 原样交给 `join`：

```kotlin
rooms.getToken(channelId, uid, role = "publisher", meta = true) { metaJson, error ->
    if (metaJson != null) engine.join(channelId, uid, metaJson)
}
```

- JSON 里 `mediaWired=true` 且有 `sfuUrl` / `sfuToken` 时，麦克风、摄像头、远端音视频都走 LiveKit；没有时自动用 P2P，调用方式不变。
- 续期同样带 `meta = true`，把 JSON 交给 `engine.renewToken`；新的 `sfuToken` 用于之后的媒体重连。
- 服务端踢人（LiveKit removed by server）和信令 `kicked` 只回调一次 `onKicked`。
- 服务端静音本端时回调 `onServerMuteAudio(本端uid, true)`，SDK 不会自动打开麦克风。
- `audience` 的 `sfuToken` 没有发布权限。切到可发布角色要重新取 Token 再 `renewToken`。
- 网络质量取自 LiveKit 的连接质量（`excellent` / `good` / `poor` / `down`），音量取自 LiveKit 音频电平。
- 目前只在 P2P 下可用：屏幕共享、自定义视频源与美颜处理、`createDataStream`、SEI、伴奏混入上行。

换画质：本地编码用 `engine.setVideoQuality("hd")`（`audio` | `sd` | `hd` | `fhd`）。控制面档位用用户 JWT 调用 `rooms.switchQualityTier(channelId, "hd")`（`POST /api/rtc/quality/switch`）。若响应里带了新 Token，再 `engine.renewToken`。

## 示例工程

`example/` 使用和客户相同的依赖行。Tag 还没推到 JitPack 时，先在仓库根目录执行 `./gradlew publishToMavenLocal`，示例会从本机 Maven 仓库解析同一坐标。

改 SDK 源码联调：把 `example/gradle.properties` 里的 `useLocalSdk` 改成 `true`。

```bash
./gradlew assemble
cd example && ./gradlew assemble
```

## 发布

客户侧推荐 **JitPack**（仓库已公开，不需要 Maven 账号）。Owner 发一版：

```bash
# VERSION 与下面的 tag 数字一致，例如文件内容 3.3.0
git tag v3.3.0
git push origin v3.3.0
```

打开 https://jitpack.io/#carlcy/sy-rtc-android-sdk 确认 `v3.3.0` 构建成功。客户依赖就是：

```gradle
implementation 'com.github.carlcy:sy-rtc-android-sdk:v3.3.0'
```

Maven Central 是可选的正式仓库，需要 Owner 自己准备签名和 Central Portal 令牌。步骤见 [PUBLISH_GUIDE.md](./PUBLISH_GUIDE.md)。

## 常用 API

包名 `com.sy.rtc.sdk`。

| API | 说明 |
|-----|------|
| `RtcEngine.create()` / `init(appId, context)` | 创建并初始化 |
| `setApiBaseUrl` / `setSignalingServerUrl` / `setApiAuthToken` | 控制面与信令地址；JWT 与 RTC Token 不是同一个 |
| `join` / `leave` / `renewToken` | 进出频道；续期重连信令 |
| `setClientRole` / `setChannelProfile` | `HOST` / `AUDIENCE` / `PUBLISHER` / `SUBSCRIBER`；场景需在 `join` 前设置 |
| `RtcEngine.VERSION` | 常量 `3.3.0`，与 `VERSION`、Demo `versionName` 一致 |
| `enableLocalAudio` / `muteLocalAudio` / `isLocalAudioMuted` | 本地音频。静音会通过信令 `user-media` 通知对端 `onUserMuteAudio`（与 iOS 互通） |
| `muteLocalVideo` | 对端收到 `onUserMuteVideo(uid, muted)`（与 iOS 互通） |
| `isRemoteAudioMuted` / `isRemoteVideoMuted` | 本机屏蔽了该路，或对端自己静音了，都返回 true |
| `muteRemoteAudioStream` / `muteAllRemoteAudioStreams` | 停止播放该路远端音频（本机 `AudioTrack`），不是服务端强制断流 |
| `enableVideo` / `setVideoQuality` / `switchCamera` / `setupLocalVideo` / `setupRemoteVideo` | 视频。`switchCamera` 在摄像头采集时切换前后摄；`useFrontCamera(front)` 指定前/后摄（未采集时记住，开摄像头时生效） |
| `setEnableSpeakerphone` / `getAudioRoute` | 扬声器或听筒。路由回调 `onAudioRoutingChanged`：0 扬声器，1 耳机，2 蓝牙，3 听筒 |
| `setBeautyEffectOptions` / `setVideoFrameProcessor` | 内置提亮作用于编码前的帧；自定义处理器会替换内置提亮 |
| `startScreenCapture(intent, config)` | 需要 MediaProjection 授权。Android 10+ 自动启动 SDK 内置的 `mediaProjection` 前台服务 `ScreenCaptureService`（见下文），返回 0 表示已提交，开始采集时回调 `onLocalVideoStateChanged("screen_capturing")`，失败 `onError(1006)` |
| `enableCustomVideoCapture` / `pushExternalVideoFrame` | 外部视频帧送入本地视频源 |
| `enableAudioVolumeIndication` | 音量来自 PCM 或 WebRTC `audioLevel`，没有样本时为 0 |
| `onNetworkQuality` | 本机 ICE RTT 与丢包估计，档位见下文「网络质量档位」。没有样本时是 `unknown`，不是 SFU 探测 |
| `setStreamExtraInfo` / `getStreamExtraInfo` | 频道消息 `sy-extra:` 前缀广播（与 iOS 同一格式），UTF-8 最多 1024 字节，超出返回 -2；新成员进房会补发。对端 `onStreamExtraInfoUpdated`，本地 `getStreamExtraInfo(uid)` 取最近值 |
| `getNetworkType` | `ConnectivityManager` 实时判断：`wifi` / `cellular` / `ethernet` / `none` / `unknown`（名称与 iOS 相同） |
| `createDataStream` / `sendStreamMessage` / `sendSei` | DataChannel。`sendSei` 只是带前缀的数据通道消息，不是码流 SEI |
| `RoomService.getToken` / `fetchToken` / `renewToken` | `POST /api/rtc/token` 与 `POST /api/rtc/token/renew`。4031/4032/4033 为 `RtcCredentialException` |
| `RoomService.switchQualityTier` | `POST /api/rtc/quality/switch`，只认用户 JWT |
| `setRoomAttribute` / `getRoomAttributes` / `deleteRoomAttribute` | `POST /api/rtc/channel/meta/set`、`get`、`delete`，只认用户 JWT |

`audience` / `subscriber` 只关本地推流，不是 SFU 强制切断。

**跨端约定**：静音用信令类型 `user-media`（`{uid, audioMuted?, videoMuted?}`），附加信息用频道消息 `sy-extra:<文本>`，SEI 用 DataChannel `SYSEI` 前缀。这些 SDK 保留消息不会回调 `onChannelMessage`。旧版 Android 的 `client-mute` / `stream-extra` JSON 仍能解析，但不再发送。

### 断线重连

与 iOS 相同的策略（`ReconnectPolicy`）：信令或 ICE 断开后最多重试 5 次，第 n 次等待 2^(n-1) 秒（1、2、4、8、16 秒）。ICE 断开时由 uid 字典序较小的一方 `restartIce` 并重发 offer，另一方等对端 offer。成功后次数清零。

| 时机 | `onConnectionStateChanged(state, reason)` | 专用回调 |
|---|---|---|
| join | `connecting` / `joining` → `connected` / `join_success` | `onJoinChannelSuccess` |
| 断开，开始重试 | `reconnecting` / `signaling` 或 `ice` | `onReconnecting(reason, attempt, maxAttempts, delayMs)` |
| 恢复 | `connected` / `rejoin_success` | `onRejoinChannelSuccess`、`onReconnected(reason)` |
| 5 次都失败 | `failed` / `signaling` 或 `ice` | `onReconnectFailed(reason)`、`onError(1003)` |
| leave | `disconnecting` / `leaving` → `disconnected` / `leave` | `onLeaveChannel` |

3.2.0 之前 Android 只重试 3 次、间隔 1/2/3 秒，reason 为 `join` / `user-list` / `rejoined`；`restartIce` 没有重发 offer，实际不生效。

### 首帧与分辨率

远端视频轨到达后挂一个常驻 sink：第一帧回调 `onFirstRemoteVideoDecoded` 和 `onFirstRemoteVideoFrame`（elapsed 为距 join 的毫秒），第一帧及之后宽、高或旋转变化时回调 `onVideoSizeChanged(uid, width, height, rotation)`。宽高是解码后缓冲区尺寸，rotation 0/90/180/270。本地视频轨每次换轨（摄像头、屏幕共享、自定义采集）的第一帧回调 `onFirstLocalVideoFrame`。iOS 相同。

### 本地录音

`startAudioRecording(AudioRecordingConfiguration(filePath, codecType = "aac" | "wav"))`，与 iOS 相同：

- 格式：`aac` / `aacLc` / `m4a` 输出 AAC-LC（MPEG-4，建议 `.m4a`）；`wav` / `pcm` 输出 16 bit WAV。**不支持 mp3**，传入回调 `onError(1000)` 并返回 -1（旧版 `mp3` 实际输出 AMR-NB，已移除）。
- 频道内：录 WebRTC 管线里的 PCM，本端采集 + 所有远端混成单声道（`includeLocal` / `includeRemote` 控制），不另开麦克风，因此不会因与通话抢麦而录成静音。本端静音时录到静音；本端静音了某远端时不录他。
- 频道外：系统录音器录麦克风，仅 AAC。频道外开始的录音 join 后可能被 WebRTC 抢占，请 join 后重新开始。
- leave 时自动停止并写完文件；远端离开时从混音中移除。

### 错误码

`onError(code, message)` 的取值三端（Android `RtcErrorCode`、iOS `SyRtcErrorCode`、Flutter `SyRtcErrorCode`）相同：

| code | 常量 | 含义 |
|---|---|---|
| 1000 | `INVALID_ARGUMENT` | 参数无效或调用时机不对（空 Token、重复 join、未知画质档位、附加信息超过 1024 字节） |
| 1002 | `SIGNALING` | 信令服务端返回的错误，message 为服务端原文 |
| 1003 | `RECONNECT_FAILED` | 重连 5 次都失败，需要 leave 后重新 join |
| 1004 | `KICKED` | 被房间管理踢出（同时回调 `onKicked`） |
| 1005 | `CAMERA` | 摄像头打开 / 切换失败，或没有可用视频源 |
| 1006 | `SCREEN_SHARE` | 屏幕共享失败 |
| 1007 | `CUSTOM_CAPTURE` | 自定义采集用法错误 |
| 1009 | `AUDIO_ROUTE` | 音频路由切换失败或不支持（目前只有 iOS 会报） |
| 403 | `FORBIDDEN` | 服务端拒绝入房：在踢出名单、房间锁定、不在白名单 |
| 4031 / 4032 / 4033 | `CREDENTIAL_SUSPENDED` / `REVOKED` / `EXPIRED` | AppId 的访问凭证被暂停 / 吊销 / 过期；服务端会断开信令，SDK 收到 `onKicked` 后以此码回调 `onError`（不再报 1004） |

403 和 4031–4033 与控制面 REST 的业务码相同，取自信令 `kicked` / `error` 帧的 `data.code`（需要 2026-09-30 之后的 rtc-backend-go）。

### Token 过期提醒

Token 是服务端签发的 `base64url(payload).签名`，payload 里的 `expireAt` 是过期时间（Unix 秒）。`join` 和 `renewToken` 后，SDK 在过期前 30 秒回调 `onTokenPrivilegeWillExpire`，到期回调 `onRequestToken`；收到后向业务后端再要一张 Token，调用 `renewToken`。iOS 行为相同。服务端也会在过期前 30 秒推送 `token-privilege-will-expire`、到期推送 `token-expired`（带 `data.expireAt`）。本地定时器和服务端推送按 Token 去重（`TokenExpiryDedupe`）：每个 Token 只回调一次提醒、一次过期；过期后不补提醒；旧 Token 迟到的推送忽略；`renewToken` 后重新计。

### 网络质量档位

RTT 和丢包各自落档，取较差的一档；没有样本时为 `unknown`。阈值参考即构 Express 的分级，Android 与 iOS 完全相同（两端各有同一张表的单测）。 3.2.0 起名字与 iOS 统一：原 `medium` 改为 `poor`，`die` 改为 `down`（`NetworkQualityEstimator.MEDIUM` / `DIE` 仍保留为已弃用别名）。`onRtcStats` 同时给 `lossPercent`（0–100）和 `packetLossRate`（0–1）。

| 档位 | RTT (ms) | 丢包 |
|---|---|---|
| `excellent` | < 100 | < 1% |
| `good` | < 200 | < 3% |
| `poor` | < 400 | < 8% |
| `bad` | < 800 | < 20% |
| `down` | ≥ 800 | ≥ 20% |

**上下行分开（与 iOS 相同，`LinkQuality`）**：
- 上行 `txQuality`：RTT + 上行丢包（对端回报的 `remote-inbound-rtp.fractionLost`），用上表。
- 下行 `rxQuality`：本统计周期的下行丢包（`inbound-rtp` 丢包 / 收包增量，用上表丢包列）+ 抖动（`inbound-rtp.jitter`：excellent <30ms、good <50ms、poor <100ms、bad <200ms、其余 down），取较差。

**回调方式（与 iOS 相同）**：每 2 秒一轮，先 `onNetworkQuality(本端 uid, tx, rx)`，tx / rx 分别为所有对端链路中上行 / 下行最差的一档（unknown 不计入）；再每个对端 `onNetworkQuality(对端 uid, tx, rx)`。没有对端时只回调本端 `unknown`。`onRtcStats` 另给 `txQuality` / `rxQuality` / `txPacketLossRate` / `rxPacketLossRate` / `jitterMs`，`quality` 为两者较差。3.2.0 及之前 tx 与 rx 取同一值。

### 屏幕共享前台服务

Android 10（API 29）起，MediaProjection 必须运行在类型为 `mediaProjection` 的前台服务里（Android 14 起不满足会直接抛 `SecurityException`）。SDK 自带 `com.sy.rtc.sdk.ScreenCaptureService`，并在 SDK 的 manifest 中声明了服务与 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限，manifest 合并后宿主 **不需要** 再声明。

- `startScreenCapture` 在用户授权后先启动该服务并显示常驻通知（渠道 `sy_rtc_screen_capture`，低优先级），服务进入前台后才创建 MediaProjection。
- `stopScreenCapture`、系统结束投屏、`leave` 时自动停止服务。
- 自定义通知：`ScreenCaptureService.notificationTitle` / `notificationText` / `smallIcon`（在 `startScreenCapture` 前设置）。
- 宿主已有自己的 mediaProjection 前台服务时，设 `ScreenCaptureService.enabled = false`，SDK 就不再启动它。
- Android 13+ 若未授予 `POST_NOTIFICATIONS`，通知不会出现在通知栏，但服务照常运行。

## 常见问题

**依赖解析失败。** 确认仓库里有 `https://jitpack.io`，版本号是 tag（带 `v`），例如 `v3.3.0`。该 tag 必须已经 push，并且 JitPack 页面是绿色。

**进不了频道。** Token 过期、信令地址没有 `wss`、或麦克风权限没给。重新向业务后端要 Token，再 `join` 或 `renewToken`。

**没有声音。** `enableLocalAudio(true)`、`muteLocalAudio(false)`，角色用 `RtcClientRole.HOST` 或 `PUBLISHER`。

## 许可证

MIT License
