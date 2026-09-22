# RTC Android Example（对接 rtc-backend-go）

示例路径：`rtc-android-sdk/example`  
依赖上级源码模块 `:sy-rtc-android-sdk`（见 `settings.gradle`）。

后端：`rtc-backend-go` 默认监听 **`:8080`**。

| 配置项 | 模拟器 | 真机（同局域网） |
|--------|--------|------------------|
| apiBaseUrl | `http://10.0.2.2:8080` | `http://<电脑LAN_IP>:8080` |
| signalingUrl | `ws://10.0.2.2:8080/ws/signaling` | `ws://<电脑LAN_IP>:8080/ws/signaling` |
| Token | `POST {apiBaseUrl}/api/rtc/token?channelId=&uid=&expireHours=24`，Header：`X-App-Id` / `X-App-Secret` | 同左 |

Cleartext：`AndroidManifest` `usesCleartextTraffic=true` + `res/xml/network_security_config.xml`（`cleartextTrafficPermitted=true`）。

## 功能

- 可编辑 appId / apiBaseUrl / signalingUrl / AppSecret / channelId / uid / token
- **拉取 Token** 或手动粘贴
- 初始化 → 加入/离开 → 静音音频 → 开关视频 → 本地预览
- 本地/远端 FrameLayout + 日志面板

## 权限

| 权限 | 模拟器 | 真机 |
|------|--------|------|
| INTERNET | 需要 | 需要 |
| RECORD_AUDIO | 需要（虚拟麦也可） | 运行时授权 |
| CAMERA | 多数 AVD 无摄像头，视频可跳过 | 运行时授权 |
| MODIFY_AUDIO_SETTINGS | 建议 | 建议 |
| BLUETOOTH_CONNECT (API 31+) | 可选 | 建议 |
| FOREGROUND_SERVICE* | 一般不需要 | 后台通话/录屏时需要 |
| POST_NOTIFICATIONS (API 33+) | 可选 | FGS 时需要 |

## 模拟器步骤

1. 启动 `rtc-backend-go`（`make run`，监听 `:8080`），确认 MySQL 中有对应 **appId + appSecret** 且 `status=active`。
2. 启动 AVD（API 24+）。
3. Android Studio 打开 `example/`，Sync，Run `app`。
4. 确认界面默认：
   - apiBaseUrl = `http://10.0.2.2:8080`
   - signalingUrl = `ws://10.0.2.2:8080/ws/signaling`
5. 填好 appId / AppSecret → **初始化** → **拉取 Token**（或粘贴）→ **加入频道**。
6. **预期**：能进房、音频可用、日志有 `onJoinChannelSuccess`；无摄像头 AVD 上视频失败属正常。

## 真机步骤

1. 手机与电脑同一 Wi-Fi；查电脑 LAN IP（如 `192.168.1.8`）。
2. 将 apiBaseUrl / signalingUrl 改为：
   - `http://192.168.1.8:8080`
   - `ws://192.168.1.8:8080/ws/signaling`
3. 确保电脑防火墙放行 8080；后端已启动。
4. USB 安装或 `./gradlew :app:installDebug`；授权麦克风/相机。
5. 初始化 → Token → 加入；可选 **启用视频** → **本地预览**。
6. **预期**：音视频双向（对端也在频道）；远端画面在右侧容器。

## 构建

```bash
cd rtc-android-sdk/example
./gradlew :app:assembleDebug
```

minSdk **24**，targetSdk / compileSdk **35**。

## 注意

- `setupLocalVideo` / `setupRemoteVideo` 传 **容器资源 id**（如 `R.id.localVideoContainer`），不是 `View.hashCode()`。
- Live / RTMP 已移除，勿再查找相关 API。
- 不要 `git push`。
