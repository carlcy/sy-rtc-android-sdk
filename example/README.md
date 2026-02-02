# Android SDK Demo

这是一个完整的 Android SDK 使用示例项目。

## 📁 项目结构

```
example/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/sy/rtc/example/
│   │       │   └── MainActivity.kt
│   │       └── res/
│   └── build.gradle
├── build.gradle
└── settings.gradle
```

## 🚀 快速开始

### 1. 配置依赖

在 `app/build.gradle` 中添加：

```gradle
dependencies {
    implementation 'com.sy.rtc:sy-rtc-android-sdk:1.2.0'
}
```

### 2. 添加权限

在 `AndroidManifest.xml` 中添加：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

### 3. 使用示例

参考 `app/src/main/java/com/sy/rtc/example/MainActivity.kt`

## 📝 完整示例代码

详见 README.md 中的示例代码部分。
