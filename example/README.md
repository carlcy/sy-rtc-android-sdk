# SY RTC Android 示例

依赖与客户工程相同，写坐标和版本，不下载、不解压 AAR。

```gradle
implementation 'com.github.carlcy:sy-rtc-android-sdk:v3.2.2'
```

版本在 `gradle.properties` 的 `sdkVersion`。仓库是 JitPack；本机若已执行过仓库根目录的 `./gradlew publishToMavenLocal`，会优先用这份本地包（坐标字符串不变）。

改 SDK 源码时，把 `useLocalSdk` 设为 `true`，示例会改为编译上级模块 `:sy-rtc-android-sdk`。

```bash
cd example
./gradlew assemble
```

界面流程：初始化 → 拉取 Token → 加入频道。Token 即将过期时会再拉一次并 `renewToken`。启用视频时调用 `setVideoQuality("sd")`。

对接说明见 [README_EXAMPLE.md](./README_EXAMPLE.md)。
