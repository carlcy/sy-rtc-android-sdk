# Android SDK Demo

## 生产基址（2026-09-15）

- API: `http://47.105.48.196`
- 信令: `ws://47.105.48.196/ws/signaling`（RTC；须 `?token=`）
- 文档: `docs/SDK_RTC.md` / `docs/SDK_IM.md`
- 下载: `http://47.105.48.196/downloads/`

本机调试仍可用 `10.0.2.2:8080`（Android 模拟器）或 `127.0.0.1`。


对接 **rtc-backend-go :8080**。详细步骤见 **[README_EXAMPLE.md](./README_EXAMPLE.md)**。

- 模拟器默认：`http://10.0.2.2:8080` / `ws://10.0.2.2:8080/ws/signaling`
- 真机：换成电脑局域网 IP

```bash
cd example && ./gradlew :app:assembleDebug
```
