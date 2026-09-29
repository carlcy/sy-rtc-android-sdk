# SY RTC Android SDK

实时音视频 Android SDK（Kotlin / Java）。控制面是 SY 的 `rtc-backend-go`，媒体层是端侧 WebRTC。对齐即构 Express 的是**进房、角色、信令**这条主路径，不是 ZegoExpress 全 API。

当前版本：**3.2.0**（JitPack tag `v3.2.0`）。

## 快速开始

和即构 Express 一样，只加仓库和一行带版本号的依赖，不下载、不解压 AAR / zip。

| 步骤 | 即构 ZEGO Express | SY RTC |
|------|-------------------|--------|
| 仓库 | `maven { url 'https://maven.zego.im' }` | `maven { url 'https://jitpack.io' }` |
| 依赖 | `implementation 'im.zego:express-video:x.y.z'` | `implementation 'com.github.carlcy:sy-rtc-android-sdk:v3.2.0'` |
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
    implementation 'com.github.carlcy:sy-rtc-android-sdk:v3.2.0'
}
```

Kotlin DSL：

```kotlin
dependencies {
    implementation("com.github.carlcy:sy-rtc-android-sdk:v3.2.0")
}
```

把 `v3.2.0` 换成 [Releases](https://github.com/carlcy/sy-rtc-android-sdk/releases) 里的 tag。坐标里的 `carlcy` 是本仓库的 GitHub 用户，客户不要改成自己的用户名。

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
# VERSION 与下面的 tag 数字一致，例如文件内容 3.2.0
git tag v3.2.0
git push origin v3.2.0
```

打开 https://jitpack.io/#carlcy/sy-rtc-android-sdk 确认 `v3.2.0` 构建成功。客户依赖就是：

```gradle
implementation 'com.github.carlcy:sy-rtc-android-sdk:v3.2.0'
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
| `enableLocalAudio` / `muteLocalAudio` | 本地音频 |
| `enableVideo` / `setVideoQuality` / `setupLocalVideo` / `setupRemoteVideo` | 视频。`setup*Video` 可传容器 id 或 `ViewGroup` |
| `RoomService.getToken` / `fetchToken` / `renewToken` | `POST /api/rtc/token` 与 `POST /api/rtc/token/renew`。4031/4032/4033 为 `RtcCredentialException` |
| `RoomService.switchQualityTier` | `POST /api/rtc/quality/switch`，只认用户 JWT |
| `setRoomAttribute` / `getRoomAttributes` / `deleteRoomAttribute` | `POST /api/rtc/channel/meta/set`、`get`、`delete`，只认用户 JWT |

`audience` / `subscriber` 只关本地推流，不是 SFU 强制切断。

## 常见问题

**依赖解析失败。** 确认仓库里有 `https://jitpack.io`，版本号是 tag（带 `v`），例如 `v3.2.0`。该 tag 必须已经 push，并且 JitPack 页面是绿色。

**进不了频道。** Token 过期、信令地址没有 `wss`、或麦克风权限没给。重新向业务后端要 Token，再 `join` 或 `renewToken`。

**没有声音。** `enableLocalAudio(true)`、`muteLocalAudio(false)`，角色用 `RtcClientRole.HOST` 或 `PUBLISHER`。

## 许可证

MIT License
