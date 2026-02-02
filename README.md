# SY RTC Android SDK

SY RTC Android SDK 是一个用于实时语音通信的 Android 原生 SDK。

## ✨ 特性

- ✅ 完整的 RTC 功能
- ✅ 简洁的 API 设计
- ✅ 支持 Kotlin 和 Java
- ✅ 支持 Maven 发布

## 📦 安装

### 方式一：从 JitPack 安装（推荐）

在项目的根目录 `build.gradle` 中添加 JitPack 仓库：

```gradle
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }  // 添加 JitPack 仓库
    }
}
```

在 `app/build.gradle` 中添加依赖：

```gradle
dependencies {
    implementation 'com.github.carlcy:sy-rtc-android-sdk:v1.2.0'
}
```

**注意**：将 `carlcy` 替换为你的 GitHub 用户名，`v1.2.0` 替换为实际的版本号。

### 方式二：从 Maven Central 安装

如果已发布到 Maven Central：

```gradle
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.sy.rtc:sy-rtc-android-sdk:1.2.0'
}
```

### 方式三：使用本地 AAR

1. **下载 AAR 文件**

   从发布页面下载 `sy-rtc-android-sdk-release.aar` 文件

2. **复制到项目**

   将 AAR 文件复制到 `app/libs/` 目录

3. **配置 build.gradle**

   在 `app/build.gradle` 中添加：

   ```gradle
   repositories {
       flatDir {
           dirs 'libs'
       }
   }

   dependencies {
       implementation(name: 'sy-rtc-android-sdk-release', ext: 'aar')
   }
   ```

## 🚀 快速开始

### 1. 添加权限

在 `AndroidManifest.xml` 中添加：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### 2. 初始化 SDK

```kotlin
import com.sy.rtc.sdk.RtcEngine
import com.sy.rtc.sdk.RtcEventHandler

// 创建引擎实例
val engine = RtcEngine.create()

// 初始化
engine.init("your_app_id") // AppId 从用户后台获取
```

### 3. 设置事件监听

```kotlin
engine.setEventHandler(object : RtcEventHandler {
    override fun onUserJoined(uid: String, elapsed: Int) {
        Log.d("RTC", "用户加入: $uid, 耗时: ${elapsed}ms")
    }
    
    override fun onUserOffline(uid: String, reason: String) {
        Log.d("RTC", "用户离开: $uid, 原因: $reason")
    }
    
    override fun onVolumeIndication(speakers: List<VolumeInfo>) {
        speakers.forEach { info ->
            Log.d("RTC", "用户 ${info.uid} 音量: ${info.volume}")
        }
    }
})
```

### 4. 加入房间

```kotlin
// 先从服务器获取 Token（不能在前端直接生成）
val token = getTokenFromServer(appId, channelId, uid)

// 加入房间
engine.join(channelId, uid, token)
```

### 5. 控制音频

```kotlin
// 启用本地音频
engine.enableLocalAudio(true)

// 静音
engine.muteLocalAudio(true)

// 取消静音
engine.muteLocalAudio(false)
```

### 6. 设置角色

```kotlin
import com.sy.rtc.sdk.RtcClientRole

// 设置为主播
engine.setClientRole(RtcClientRole.HOST)

// 设置为观众
engine.setClientRole(RtcClientRole.AUDIENCE)
```

### 7. 离开房间

```kotlin
engine.leave()
```

### 8. 释放资源

```kotlin
engine.release()
```

## 📖 完整示例

```kotlin
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sy.rtc.sdk.*

class MainActivity : AppCompatActivity() {
    private lateinit var engine: RtcEngine
    private var isJoined = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 请求权限
        requestPermissions()
        
        // 初始化引擎
        initEngine()
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
        )
        
        val needRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (needRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needRequest.toTypedArray(), 100)
        }
    }

    private fun initEngine() {
        engine = RtcEngine.create()
        engine.init("your_app_id")
        
        engine.setEventHandler(object : RtcEventHandler {
            override fun onUserJoined(uid: String, elapsed: Int) {
                Log.d("RTC", "用户加入: $uid")
            }
            
            override fun onUserOffline(uid: String, reason: String) {
                Log.d("RTC", "用户离开: $uid")
            }
            
            override fun onVolumeIndication(speakers: List<VolumeInfo>) {
                // 处理音量指示
            }
        })
    }

    private fun joinChannel() {
        if (isJoined) return
        
        // 从服务器获取 Token
        val token = getTokenFromServer()
        
        engine.join("channel_001", "user_001", token)
        engine.enableLocalAudio(true)
        
        isJoined = true
    }

    private fun leaveChannel() {
        if (!isJoined) return
        
        engine.leave()
        isJoined = false
    }

    private fun getTokenFromServer(): String {
        // 调用服务器 API 获取 Token
        // 这里需要实现 HTTP 请求
        return "token_from_server"
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }
}
```

## 📚 API 文档

### RtcEngine

#### 创建实例

```kotlin
fun create(): RtcEngine
```

创建 RTC 引擎实例。

#### 初始化

```kotlin
fun init(appId: String)
```

初始化 RTC 引擎。

**参数：**
- `appId`: 应用ID，从用户后台获取

#### 加入房间

```kotlin
fun join(channelId: String, uid: String, token: String)
```

加入语音房间。

**参数：**
- `channelId`: 房间ID
- `uid`: 用户ID（字符串类型）
- `token`: 鉴权Token（从服务器获取）

#### 离开房间

```kotlin
fun leave()
```

离开当前房间。

#### 启用/禁用本地音频

```kotlin
fun enableLocalAudio(enabled: Boolean)
```

启用或禁用本地音频采集和播放。

**参数：**
- `enabled`: `true` 为启用，`false` 为禁用

#### 静音/取消静音

```kotlin
fun muteLocalAudio(muted: Boolean)
```

静音或取消静音本地音频。

**参数：**
- `muted`: `true` 为静音，`false` 为取消静音

#### 设置客户端角色

```kotlin
fun setClientRole(role: RtcClientRole)
```

设置客户端角色。

**参数：**
- `role`: `RtcClientRole.HOST` 或 `RtcClientRole.AUDIENCE`

#### 设置事件监听

```kotlin
fun setEventHandler(handler: RtcEventHandler?)
```

设置事件监听器。

**参数：**
- `handler`: 事件监听器，`null` 表示移除监听

#### 释放资源

```kotlin
fun release()
```

释放引擎资源。在不再使用引擎时调用。

### RtcEventHandler

事件回调接口：

```kotlin
interface RtcEventHandler {
    fun onUserJoined(uid: String, elapsed: Int)
    fun onUserOffline(uid: String, reason: String)
    fun onVolumeIndication(speakers: List<VolumeInfo>)
}
```

**回调说明：**
- `onUserJoined`: 当有用户加入房间时触发
  - `uid`: 用户ID
  - `elapsed`: 加入耗时（毫秒）
- `onUserOffline`: 当有用户离开房间时触发
  - `uid`: 用户ID
  - `reason`: 离开原因
- `onVolumeIndication`: 当检测到用户音量变化时触发
  - `speakers`: 说话者列表

### RtcClientRole

客户端角色枚举：

```kotlin
enum class RtcClientRole {
    HOST,      // 主播，可以说话
    AUDIENCE   // 观众，只能听
}
```

### VolumeInfo

音量信息：

```kotlin
data class VolumeInfo(
    val uid: String,    // 用户ID
    val volume: Int    // 音量（0-255）
)
```

## 🔑 如何获取 Token？

**重要**：Token 必须从服务器获取，不能在前端直接生成！

### 推荐流程

1. **客户端请求加入房间**
   ```kotlin
   // 使用 Retrofit 或 OkHttp
   val response = apiService.getToken(
       appId = appId,
       channelId = channelId,
       uid = uid
   )
   val token = response.data.token
   ```

2. **服务器生成 Token**
   ```java
   // 服务器代码（Java Spring Boot）
   @PostMapping("/rtc/token")
   public Result<String> generateToken(@RequestBody TokenRequest request) {
       String token = rtcService.generateToken(
           request.getAppId(),
           request.getChannelId(),
           request.getUid()
       );
       return Result.success(token);
   }
   ```

3. **客户端使用 Token 加入房间**
   ```kotlin
   engine.join(channelId, uid, token)
   ```

## ⚙️ 项目配置

### 最低要求

在 `app/build.gradle` 中：

```gradle
android {
    defaultConfig {
        minSdk 21  // Android 5.0
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = '1.8'
    }
}
```

### 权限配置

在 `AndroidManifest.xml` 中添加：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    
    <!-- 必需权限 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    
    <application>
        <!-- 你的应用配置 -->
    </application>
</manifest>
```

### 运行时权限请求

Android 6.0+ 需要动态请求麦克风权限：

```kotlin
if (ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) != PackageManager.PERMISSION_GRANTED
) {
    ActivityCompat.requestPermissions(
        this,
        arrayOf(Manifest.permission.RECORD_AUDIO),
        100
    )
}
```

## 📦 发布到 JitPack（推荐）

### 快速发布

1. **推送到 GitHub**
   ```bash
   git add .
   git commit -m "Release v1.2.0"
   git push origin main
   ```

2. **创建 Release Tag**
   ```bash
   git tag v1.2.0
   git push origin v1.2.0
   ```

3. **访问 JitPack**
   - 打开 https://jitpack.io/
   - 搜索：`carlcy/sy-rtc-android-sdk`
   - JitPack 会自动构建并发布

### 使用方式

用户可以在 `build.gradle` 中使用：

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.carlcy:sy-rtc-android-sdk:v1.2.0'
}
```

## 📦 发布到 Maven Central（可选）

SDK 已配置 Maven 发布，也可以发布到 Maven Central。

### 发布到本地 Maven（测试）

```bash
./gradlew publishToMavenLocal
```

### 发布到 Maven Central

1. 注册 Sonatype 账号并配置 GPG 签名
2. 运行发布命令：

```bash
./gradlew publish
```

## ❓ 常见问题

### 1. 无法加入房间？

**可能原因：**
- Token 无效或已过期
- 网络连接问题
- 权限未授予

**解决方法：**
- 重新从服务器获取 Token
- 检查网络连接
- 确保已授予麦克风权限

### 2. 没有声音？

**可能原因：**
- 本地音频未启用
- 已静音
- 角色设置为观众

**解决方法：**
```kotlin
// 启用本地音频
engine.enableLocalAudio(true)

// 取消静音
engine.muteLocalAudio(false)

// 设置为主播
engine.setClientRole(RtcClientRole.HOST)
```

### 3. 编译错误？

**可能原因：**
- Kotlin 版本不兼容
- 依赖冲突

**解决方法：**
- 确保 Kotlin 版本 >= 1.8
- 检查依赖版本冲突

## 📱 平台要求

- **minSdk**: 21 (Android 5.0)
- **compileSdk**: 34
- **Kotlin**: 1.8+
- **Java**: 1.8+

## 📄 许可证

MIT License

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

---

**最后更新**: 2026-01-14
