# Android SDK 发布指南

## 构建 AAR

```bash
./gradlew assembleRelease
```

生成的 AAR 文件位于：`build/outputs/aar/sy-rtc-android-sdk-release.aar`

## 发布到 Maven Central

### 1. 准备工作

1. 注册 Sonatype 账号：https://issues.sonatype.org/
2. 创建 GPG 密钥用于签名
3. 配置 `gradle.properties`：

```properties
ossrhUsername=your-username
ossrhPassword=your-password
signing.keyId=your-key-id
signing.password=your-key-password
signing.secretKeyRingFile=path/to/secret.gpg
```

### 2. 发布

```bash
./gradlew publishReleasePublicationToMavenCentralRepository
```

### 3. 发布到本地 Maven 仓库（测试）

```bash
./gradlew publishToMavenLocal
```

使用：
```gradle
repositories {
    mavenLocal()
}
dependencies {
    implementation 'com.sy.rtc:sy-rtc-android-sdk:1.0.0'
}
```

## 发布到 JitPack

1. 将代码推送到 GitHub
2. 创建 Release Tag
3. 在 JitPack 搜索：https://jitpack.io/
4. 使用：
```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    implementation 'com.github.yourusername:sy-rtc-android-sdk:1.0.0'
}
```

