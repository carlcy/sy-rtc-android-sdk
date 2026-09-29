# RTC Android Example

示例用 Maven 坐标依赖 SDK（`com.github.carlcy:sy-rtc-android-sdk`，版本见 `gradle.properties` 的 `sdkVersion`）。`useLocalSdk=true` 时改为编译上级源码。

后端：`rtc-backend-go`。

| 配置项 | 模拟器 | 真机（同局域网） |
|--------|--------|------------------|
| apiBaseUrl | `http://10.0.2.2:8080` | `http://<电脑LAN_IP>:8080` |
| signalingUrl | `ws://10.0.2.2:8080/ws/signaling` | `ws://<电脑LAN_IP>:8080/ws/signaling` |
| Token | `POST {apiBaseUrl}/api/rtc/token?channelId=&uid=&expireHours=24`，Header：`X-App-Id` / `X-App-Secret` | 同左 |

生产基址以控制台为准（示例里可切换 IP HTTPS / 域名）。Cleartext：`usesCleartextTraffic=true` 与 `res/xml/network_security_config.xml`。

## 构建

仓库根目录先保证坐标能解析（tag 已在 JitPack，或先 `./gradlew publishToMavenLocal`），然后：

```bash
cd example
./gradlew :app:assembleDebug
```

minSdk 24，compileSdk / targetSdk 35。

## 使用

1. 填 appId / AppSecret / apiBaseUrl / signalingUrl。
2. **初始化** → **拉取 Token**（或粘贴）→ **加入频道**。
3. 预期日志有 `onJoinChannelSuccess`。无摄像头的模拟器上，视频失败是正常的。

`setupLocalVideo` / `setupRemoteVideo` 传容器资源 id（如 `R.id.localVideoContainer`）。

AppSecret 只用于这个调试界面拉 Token。客户 App 不要内置 AppSecret，改由业务后端签发 Token。
