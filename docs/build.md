# 构建与复现

当前脚本面向 Windows x64 / PowerShell。闭源 captures 不参与构建和打包。

固定工具链：Rust nightly-2026-08-01 + aarch64-linux-android/rustfmt/clippy；Gradle Wrapper 9.7.1；AGP 9.4.1；SDK 37（本机 platforms/android-37.0）、build-tools 37.0.0、NDK 29.0.14206865。需完整 JDK，本机验证为 JBR OpenJDK 25.0.2，Java 源目标 17。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
cargo test --locked --workspace
cargo fmt --all -- --check
cargo clippy --locked --workspace --all-targets -- -D warnings
.\gradlew.bat :android:module:assembleDebug :android:module:testDebugUnitTest :android:module:lintDebug
powershell -NoProfile -File scripts/test-jni.ps1
```

buildRust 自动运行 scripts/build-native.ps1，以 NDK API 29 linker 和 -z max-page-size=16384 生成 ARM64 库。APK 的 min/compile/target 都为 API 37，限定此次 Android 17 样本；native 链接基线较低不表示 APK 支持旧 ROM。

产物：android/module/build/outputs/apk/debug/module-debug.apk（debug 签名）和 target/aarch64-linux-android/release/libqj_jni.so。

Gradle ZIP SHA-256：acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a。Cargo.lock 与 Gradle verification metadata 锁定下载内容；常规构建不要使用 --write-verification-metadata。debug 签名来自本机默认开发密钥，不保证不同机器 APK 字节相同。

中文仓库路径已显式开启 android.overridePathCheck=true。直接 Binder 实现避开 Windows AIDL depfile 编码限制。未修改系统环境或全局 Gradle 配置；如 Java Maven TLS 受网络影响，在当前命令设置本机代理的 http/https proxyHost/proxyPort，不禁用证书验证。首次 wrapper 生成的 URL HEAD 检查受代理影响关闭，ZIP 摘要校验保留。

拥有原始 captures 且安装 androguard==4.1.4 时执行 python scripts/verify-hooks.py，只读样本并比较 APK/DEX 哈希及签名，输出到 build/。没有样本也可构建。

scripts/test-jni.ps1 用真实 Windows Rust cdylib 和 JVM 调用同一 NativeCore，验证中文/补充平面字符、观察、查询、反重放、拒绝和撤销。ARM64 另做交叉编译/ELF 检查；主机 JVM 通过不代表 Android 实机通过。
