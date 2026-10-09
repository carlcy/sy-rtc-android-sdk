# 发布指南

客户安装方式只有坐标，没有 AAR 下载。默认走 JitPack。Maven Central 可选。

客户最终写的是：

```gradle
implementation 'com.github.carlcy:sy-rtc-android-sdk:v3.3.0'
```

版本号来自 git tag，tag 必须是 `v` + `VERSION` 文件里的数字（`VERSION` 为 `3.3.0` 时 tag 为 `v3.3.0`）。

## 1. JitPack（推荐，Owner 只需要打 tag）

仓库 `https://github.com/carlcy/sy-rtc-android-sdk` 已是公开仓库。`jitpack.yml` 指定 JDK 17 和 Android 35，根工程就是 Android Library，并配置了 `maven-publish`。

发版：

```bash
# 1. 改 VERSION（例如 3.3.0），提交并推到 main
git add VERSION
git commit -m "release: v3.3.0"
git push origin main

# 2. 打 tag。JitPack 用 tag 当版本号，不要省略 v
git tag v3.3.0
git push origin v3.3.0
```

然后打开 https://jitpack.io/#carlcy/sy-rtc-android-sdk ，等 `v3.3.0` 构建变绿。

不需要 Sonatype 账号，不需要 GPG，不需要 GitHub Actions secret。未配置签名时 `publishToMavenLocal` 和 JitPack 构建都不会因为 signing 失败。

本地先验证同一行坐标：

```bash
./gradlew publishToMavenLocal
cd example && ./gradlew :app:assembleDebug
```

`publishToMavenLocal` 写出的坐标默认就是 `com.github.carlcy:sy-rtc-android-sdk:v<VERSION>`。`example` 把 `mavenLocal()` 放在 JitPack 前面，所以 tag 还没推上去时示例也能编过。

源码联调不必发布：`example/gradle.properties` 设 `useLocalSdk=true`。

## 2. Maven Central（可选）

`com.github.carlcy` 是 JitPack 的 group，Central 不接受。GitHub 用户 `carlcy` 可以在 [Central Portal](https://central.sonatype.com/) 用 GitHub 登录后认领命名空间 **`io.github.carlcy`**。

发到 Central 之前，在本机或 CI 设置（不要提交进 git）：

| 变量 | 含义 |
|------|------|
| `POM_GROUP_ID` | `io.github.carlcy` |
| `POM_VERSION` | `3.3.0`（无 `v` 前缀） |
| `POM_ARTIFACT_ID` | `sy-rtc-android-sdk`（可省略） |
| `OSSRH_USERNAME` 或 `CENTRAL_USERNAME` | Central Portal User Token 的用户名 |
| `OSSRH_PASSWORD` 或 `CENTRAL_PASSWORD` | 对应口令 |
| `SIGNING_KEY` | ASCII armored GPG 私钥（`gpg --armor --export-secret-keys KEYID`） |
| `SIGNING_PASSWORD` | 私钥口令 |
| `SIGNING_KEY_ID` | 可选，密钥 id |

生成令牌：Central Portal → 头像 → View Account → Generate User Token。

上传地址默认是 Central 的 staging API：

`https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/`

仍在用旧 OSSRH 时，把 `mavenCentralUrl`（或环境变量 `MAVEN_CENTRAL_URL`）设为  
`https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/`。

发布：

```bash
export POM_GROUP_ID=io.github.carlcy
export POM_VERSION=3.3.0
export OSSRH_USERNAME=...
export OSSRH_PASSWORD=...
export SIGNING_KEY="$(cat secring.asc)"
export SIGNING_PASSWORD=...
./gradlew publishReleasePublicationToMavenCentralRepository
```

然后在 Central Portal 里把这次 deployment 发布出去。客户若改走 Central，依赖变为：

```gradle
implementation 'io.github.carlcy:sy-rtc-android-sdk:3.3.0'
```

仓库只需要 `mavenCentral()`，不再需要 JitPack。在 Central 真正发布之前，README 和示例继续使用 JitPack 坐标。

## 3. 只编 AAR（不作为客户接入方式）

```bash
./gradlew assembleRelease
```

产物：`build/outputs/aar/sy-rtc-android-sdk-release.aar`。客户文档不要再写 flatDir / 解压 zip。
