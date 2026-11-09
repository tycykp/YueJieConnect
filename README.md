# 越界互联 · YueJie Connect

越界互联是一个基于 LSPosed / libxposed 的跨品牌设备互联模块，通过兼容层连接小米澎湃 OS 4 与 ColorOS 17，沿用系统原厂的设备发现、配对和投屏界面。

项目围绕设备信息与电量展示、剪贴板与通知同步、投屏音视频、远程触控和文件拖拽等功能展开。Android 端使用 Java，通信与加密核心使用 Rust。目前面向 Android 17 的 ARM64 设备，安装使用需要支持 libxposed API 102 的模块环境。

## 功能支持

| 功能 | 状态 |
| --- | --- |
| 加密设备连接 | ✅ |
| 设备信息查询 | ✅ |
| 电量与充电状态查询 | ✅ |
| 双向纯文本剪贴板 | ✅ |
| 基础通知同步 | ✅ |
| 手机向平板投屏（画面） | ✅ |
| 手机向平板投屏（音频） | ✅ |
| 投屏触控与单指拖动 | ✅ |
| 原厂投屏窗口双向文件拖拽 | ✅ |
| 原厂界面配对与完整连接 | ✅ |
| 原厂设备发现与卡片展示 | ✅ |
| ColorOS 原厂断开连接 | ✅ |
| 富文本 / HTML 剪贴板 | ❌ |
| 文件 / URI 剪贴板 | ❌ |
| 原厂文件传输 | ❌ |
| 通知操作与快捷回复 | ❌ |
| 应用接力 / 应用流转 | ❌ |
| 网络共享 | ❌ |
| 通话协同 | ❌ |
| 短信协同 | ❌ |
| 响铃查找设备 | ❌ |
| 相机共享 | ❌ |
| 媒体与音量控制 | ❌ |

✅ 已支持；❌ 尚未完整支持。具体可用性取决于设备与系统版本。

## 构建教程

当前构建脚本使用 Windows PowerShell 和 Windows 版 Android NDK。

### 1. 准备环境

安装 Git、Android Studio 和 Rustup。在 Android Studio 的 SDK Manager 中安装：

- Android SDK Platform 37
- Android SDK Build-Tools 37.0.0
- Android NDK 29.0.14206865（Side by side）

使用 JDK 21 或 Android Studio 自带的 JBR，并确保 `git`、`rustup`、`cargo` 可在 PowerShell 中运行。Gradle 由项目自带的 Wrapper 下载，无需单独安装。

### 2. 获取源码并配置环境

```powershell
git clone https://github.com/tycykp/YueJieConnect.git
cd YueJieConnect

# 根据本机安装位置调整路径。
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

rustup toolchain install nightly-2026-08-01 --profile minimal --component rustfmt --component clippy --target aarch64-linux-android
```

Rust 工具链版本由 `rust-toolchain.toml` 固定。

### 3. 构建 APK

```powershell
.\gradlew.bat :android:module:assembleDebug
```

Gradle 会自动调用 `scripts/build-native.ps1` 编译 Rust 原生库并打包到 APK。首次构建需要联网下载依赖。

生成的安装包：

```text
android/module/build/outputs/apk/debug/module-debug.apk
```

应用名称为「越界互联」，包名为 `io.github.yuejie.connect`。两台设备安装同一版本后，在 LSPosed 中启用模块及推荐作用域，重启设备，再从原厂互联页面配对。
