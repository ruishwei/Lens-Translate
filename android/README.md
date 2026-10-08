# 译镜 Android

原生 Java 应用。最低 Android 8.0 / API 26，编译与目标 API 35。

## 构建

要求 JDK 17、Android SDK Platform 35、Build Tools 35.0.0。Gradle Wrapper 固定 8.11.1，Android Gradle Plugin 固定 8.9.3。

在本目录配置自己的 SDK 路径（也可设置 `ANDROID_HOME`）：

```powershell
'sdk.dir=C:/Android/Sdk' | Set-Content local.properties
.\gradlew.bat :app:assembleFullDebug :app:assembleScreenDebug
```

Linux / macOS 使用 `./gradlew`。首次构建需要下载 Gradle 和 Maven 依赖。`local.properties`、SDK、签名私钥及缓存不应提交。

## 安装包

| 源码版本 | 内容 | 包名 |
| --- | --- | --- |
| `full` | 屏幕翻译、译镜键盘和输入辅助 | `com.rongji.screentranslate` |
| `screen` | 屏幕翻译，不含输入功能与词库 | `com.rongji.screentranslate.screen` |

每版分别生成 `arm64-v8a`、`armeabi-v7a`、`x86_64` APK，位于 `app/build/outputs/apk/<版本>/debug/`。选择与设备架构一致的包，例如：

```powershell
adb install -r app/build/outputs/apk/screen/debug/app-screen-arm64-v8a-debug.apk
```

调试构建使用本机调试证书。源码构建的签名可能与下载附件不同；覆盖升级需要相同包名和签名。离线 OCR 随包提供，翻译语言模型首次使用前另行下载；Key 不进入构建参数或 APK。

## 单元测试与静态检查

```powershell
.\gradlew.bat :app:testFullDebugUnitTest :app:testScreenDebugUnitTest :app:lintFullDebug :app:lintScreenDebug
```

单元测试无需 API Key。公开仓库不附带个人截图或内部设备测试材料。

完整使用说明、权限与数据说明见 [项目首页](../README.md)。
