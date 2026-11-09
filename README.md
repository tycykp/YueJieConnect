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
| NFC 一碰投屏（ColorOS → 小米平板 8 Pro） | ✅ |
| NFC 一碰传文件（ColorOS → 小米平板 8 Pro） | ✅ |
| 投屏触控与单指拖动 | ✅ |
| 原厂投屏窗口双向文件拖拽 | ✅ |
| 原厂界面配对与完整连接 | ✅ |
| 原厂设备发现与卡片展示 | ✅ |
| ColorOS 原厂断开连接 | ✅ |
| 双向富文本 / HTML 剪贴板 | ✅ |
| 双向文件 / URI 剪贴板 | ✅ |
| 原厂文件传输 | ❌ |
| 通知操作与快捷回复 | ✅ |
| 应用接力 / 应用流转 | ❌ |
| 网络共享（Wi-Fi / 移动数据，平板控制中心） | ✅ |
| 通话协同 | ❌ |
| 短信协同 | ❌ |
| 响铃查找设备 | ❌ |
| 相机共享 | ❌ |
| 媒体与音量控制 | ✅ |

✅ 已支持；❌ 尚未完整支持。具体可用性取决于设备与系统版本。

文件剪贴板每次同步一个 `content://` URI，接收应用粘贴并打开文件时开始传输。

使用原厂配对时，在两台设备的「选择同步通知的应用」中启用原厂配对通知授权，再重新连接。来源设备需要通知访问权限，接收设备需要允许显示通知。快捷回复适用于来源应用提供的文本回复按钮。

ColorOS 手机投屏到小米平板时，使用平板音量键和系统音量条调节投屏声音。手机上只有一个可控的播放会话时，平板的系统媒体键可控制播放和暂停。

网络共享需要先在系统蓝牙设置中配对手机和平板。在模块界面，手机选择「网络共享：等待蓝牙连接」，平板选择已配对的手机；核对两端校验码并确认「网络共享」授权后，可在平板控制中心点击显示手机名称的「移动数据」按钮开启或关闭共享，也可使用模块界面的共享按钮。平板需要在 LSPosed 中启用本模块的「系统界面」作用域，并重启系统界面或设备。手机连接 Wi-Fi 时共享 Wi-Fi，关闭手机 Wi-Fi 后共享移动数据，可能消耗套餐流量。点击「停止网络共享并恢复连接」可关闭临时热点并恢复平板原有网络。共享控制使用蓝牙连接。

在 ColorOS 手机的 LSPosed 中启用「NFC 服务」和「跨屏互联」作用域，打开 NFC。手机亮屏解锁后，用背面的 NFC 感应区触碰小米平板 8 Pro 右上角即可请求投屏。发送文件时，打开系统分享页面后直接触碰平板，在原厂卡片上滑即可发送，已配对且解锁的平板直接接收，无需额外选择模块分享入口或再次确认文件接收；传输结束后退出动画。未连接时会尝试配对和连接，首次配对需平板可被发现并确认。一次可选 1 至 4 个文件，单个文件最大 64 MiB，接收文件保存在 `Download/YueJie`。使用 HMA-OSS 时，跨屏互联需要能访问分享文件的应用。

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
