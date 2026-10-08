# 译镜 · Lens Translate

Android / Windows 屏幕与文本翻译工具。截屏后在设备上识别文字，再使用本机离线引擎或 DeepSeek 翻译。

## 下载

[全部发布文件与校验值](https://github.com/ruishwei/Lens-Translate/releases/tag/v0.9.0)

| 版本 | 下载 | APK / EXE 大小 |
| --- | --- | ---: |
| Android 屏幕版 · ARM64 | [下载 APK](https://github.com/ruishwei/Lens-Translate/releases/download/v0.9.0/Lens-Translate-0.9.0-screen-arm64-v8a-debug.apk) | 18.21 MiB |
| Android 完整版 · ARM64 | [下载 APK](https://github.com/ruishwei/Lens-Translate/releases/download/v0.9.0/Lens-Translate-0.9.0-full-arm64-v8a-debug.apk) | 20.06 MiB |
| Android 屏幕版 · 32 位 ARM | [下载 APK](https://github.com/ruishwei/Lens-Translate/releases/download/v0.9.0/Lens-Translate-0.9.0-screen-armeabi-v7a-debug.apk) | 16.24 MiB |
| Android 完整版 · 32 位 ARM | [下载 APK](https://github.com/ruishwei/Lens-Translate/releases/download/v0.9.0/Lens-Translate-0.9.0-full-armeabi-v7a-debug.apk) | 18.09 MiB |
| Windows 0.1.0 · x64 | [下载便携 EXE](https://github.com/ruishwei/Lens-Translate/releases/download/v0.9.0/Lens-Translate-0.1.0-x64.exe) | 130.62 MiB |

Android 最低支持 8.0，Windows 支持 10 / 11 x64。本次为预发布版本：Android 使用开发测试签名，Windows EXE 未做生产代码签名。安装后空间还包含解压引擎、运行数据和另行下载的离线语言包。

## Android：选择版本

| 功能 | 屏幕版 | 完整版 |
| --- | --- | --- |
| 全屏 / 区域翻译、悬浮球、黑底文字覆盖 | 支持 | 支持 |
| 本机 OCR、图片与文本翻译 | 支持 | 支持 |
| 离线 / 自动 / DeepSeek 引擎 | 支持 | 支持 |
| 译镜键盘：全拼、九宫格、笔画、本地词库联想 | 不包含 | 支持 |
| 输入翻译、可选输入辅助服务 | 不包含 | 支持 |

完整版包名为 `com.rongji.screentranslate`，屏幕版为 `com.rongji.screentranslate.screen`。两版可共存，设置、历史、Key 和语言包分别管理，不自动迁移。同包名且同签名的发布包可以覆盖升级；自行编译使用自己的签名。

### 屏幕翻译

1. 在设置中选择翻译引擎。离线模式先下载语言包；DeepSeek 和自动云端回退需要自己的 API Key。
2. 开启悬浮翻译，授予显示在其他应用上层的权限和系统截屏授权。跨应用翻译时选择共享整个屏幕。
3. 单击悬浮球进行全屏翻译；长按打开圆形菜单，选择区域翻译后拖动框选。
4. 译文以黑底白字覆盖对应文字块，页面其余区域透明。点击图层退出，悬浮球恢复；拖动悬浮球可以改变位置。

当前 OCR 适合中文和拉丁字母文字。屏幕滚动后需要重新触发翻译，受系统保护的窗口可能不能截屏。语言包首次下载需要能访问 Google 模型服务的网络，准备完成后可断网翻译。

### 输入翻译（完整版）

在主页设置输入翻译，启用并切换至译镜键盘，先准备所需离线语言包。在其他应用中输入文字后点键盘上的“翻译”，译文写回原输入框，由使用者自行发送。支持选区翻译和限时撤销。

译镜键盘的输入、候选词、词库联想和翻译在本机处理，不调用 DeepSeek，不保存输入翻译历史。无需无障碍权限；保留其他输入法时，可选择启用独立输入辅助，但兼容性受宿主应用影响。当前不含手写、语音、滑行或个人词库学习。

## Windows 使用

1. 将自己的 DeepSeek Key 配置到用户环境变量 `DEEPSEEK_API_KEY`，从可读取该变量的新进程启动便携 EXE。
2. 在应用中输入文字、导入图片，或按 `Ctrl + Shift + T` 框选屏幕。
3. 检查识别文字后点翻译；支持复制译文和管理本机历史。

Windows 使用系统 OCR，需要在系统语言设置中安装对应 OCR 组件；翻译使用 DeepSeek，暂不提供离线翻译。应用遵循系统网络配置，必要时可通过进程变量 `LENS_PROXY_URL` 指定代理。

## 隐私与数据

- 截图在设备上用于 OCR，不加入 DeepSeek 请求。云端翻译仅发送需要翻译的文字、语言、模型及翻译指令；API Key 通过 HTTPS 请求头认证。
- 全屏识别文字可能含页面标题、用户名和评论；区域翻译先裁剪再识别。自动模式可能将需要云端处理的文字发送至 DeepSeek；希望文字保持本地时请选择离线模式。
- Android Key 由 Android Keystore 加密保存；Windows 从环境变量读取。应用不附带公共 Key，电脑 Key 不会自动同步至手机。
- 历史保存在本机，可关闭记录或清空。两端不互相同步，没有账号登录或自建云后端。
- ML Kit 在本机处理原文和译文，但 SDK 可能联网下载模型、接收更新并发送性能 / 使用指标。

## 从源码构建

Android 需要 JDK 17、Android SDK 35，详见 [Android 构建说明](android/README.md)。

```powershell
git clone https://github.com/ruishwei/Lens-Translate.git
cd Lens-Translate/android
# 配置 ANDROID_HOME 或本机 local.properties 后：
.\gradlew.bat :app:assembleFullDebug :app:assembleScreenDebug
```

Windows 需要 Node.js 22.12+ 和 npm：

```powershell
cd desktop
npm ci
npm run build
npm start
# 打包便携 EXE：
npm run dist
```

源代码、构建配置与必要第三方资源保存在仓库；本机配置、凭据、缓存、内部文档、截图和测试输出不进入版本管理。

## 许可证

项目自身代码和文档采用 [Apache License 2.0](LICENSE)，归属与第三方说明见 [NOTICE](NOTICE)。

第三方内容保留各自许可证：完整版拼音词库采用 Apache-2.0，笔画词库采用 LGPL-3.0；原始词库、作者、许可证和固定来源版本在 `android/app/src/full/assets/` 中。其他依赖和 SDK 依其各自许可证使用。
