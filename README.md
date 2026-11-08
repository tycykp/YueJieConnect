# 强兼互联 · QiangJian Connect

面向 HyperOS 4 与 ColorOS 17 原生设备互联的兼容层，采用 **libxposed API 102 + Rust**。

**2026-11-08 晚间更新**：独立 XiaomiProtocolAdapter / OplusProtocolAdapter 已接线；Noise v2、session_id、真实资料和电池 RPC、原生设备资料报文/属性转换已落地。v0.3.2/code10 两端实机已通过用户确认后的**双向原生 SDK 电池业务**及失联拒绝；最终源码 v0.3.3/code11 另通过构建/测试。完整原生 UI/通用连接仍未通过；文件、剪贴板、通知、投屏、应用流转未完成。详见 [实机结果与版本边界](adapters/hyperos4/runtime-validation.md)、[能力与缺口](adapters/hyperos4/capability-matrix.md)、[公共接口](adapters/hyperos4/shared-contract.md)。下文原有诊断阶段记录保留，其“尚无网络/连接代码”等描述已被本更新取代。

目标是**双向设备代理**：在各家原生接口中呈现它能识别的兼容设备身份，名称、型号、能力和状态对应另一端真实设备；连接与业务请求由中间层接管、转换、送到真实对端，结果再返回原生应用。详见 [设备代理设计与实现状态](docs/device-gateway.md)。被动发现采集只是诊断工具，不是最终交付目标。

当前已有实现：两端原生发现回调 → 有界异步队列 → 校验调用方的独立 Binder 服务 → Rust JNI → 统一设备目录 → 本机界面。第一项业务是**只读本机设备目录**。双端安装、API 102 装载、Hook 安装、调用方校验及 ARM64 JNI 查询已实机通过；本轮原生发现观察目录为空，**发现回调到 Rust 的实际通路尚未证实**。跨品牌原生配对、认证、连接和文件传输尚未实现，相关请求明确返回 unsupported。详见 [实机记录](docs/runtime-validation-20261108.md)。

不修改同账号标志、设备类型、厂商方法参数或回调结果，不以 MiShare/OShare 或自建传输替代原生设备互联。

## 实现位置

- `android/module/`：API 102 Java 入口、固定两包作用域、5 个经原始 DEX 核对的发现回调、简单本机界面。
- 同 APK 的 `:bridge` 独立进程：验证 Binder UID/PID、唯一包归属、版本与当前签名；仅模块 UID 可控制采集和查询目录。
- `core/`：Rust 统一事件/设备模型、未知能力位保留、IPC 租约、反重放、期限/容量限制、撤销与错误语义。
- `core/src/gateway.rs`：新增真实设备档案、兼容身份别名、能力交集与真实端点路由；4 项定向测试通过。尚未接入 Android 设备注入和跨设备传输，已安装 APK 仍为上一版诊断模块。
- `native/`：真实 JNI ABI，Rust 只在桥接服务加载，不在 Hook 回调中加载。
- `transport/`：SCP V4 明文控制帧离线编解码子集；未知扩展、CRC、加密和业务帧拒绝。尚无网络连接。

用户在本机界面确认后才开始接受观察事件，停止清除全部内存状态。原生 ID 在输出目录前按会话随机加盐散列，不采集账号摘要、密钥、文件内容或剪贴板。

## 构建

Windows PowerShell，配置完整 JDK 和 Android SDK 后：

```powershell
cargo test --locked --workspace
cargo clippy --locked --workspace --all-targets -- -D warnings
.\gradlew.bat :android:module:assembleDebug :android:module:testDebugUnitTest :android:module:lintDebug
powershell -NoProfile -File scripts/test-jni.ps1
```

ARM64 APK：`android/module/build/outputs/apk/debug/module-debug.apk`。Gradle 自动交叉编译并打包 Rust 库。无需 captures 即可构建；有原始样本可运行 `python scripts/verify-hooks.py` 复核 DEX。

详见 [构建](docs/build.md)、[验证记录](docs/validation.md)、[实机步骤](docs/device-validation.md)、[架构](docs/architecture.md)、[兼容性矩阵](docs/compatibility.md)、[API 102 核实](docs/api102.md)、[Hook 依据](docs/hooks.md)。

历史证据：[小米](adapters/hyperos4/protocol-analysis.md)、[欧加](adapters/coloros17/protocol-analysis.md)、[ColorOS SCP 专项](adapters/coloros17/coloros-to-coloros-protocol.md)。原始 captures 保持 Git 忽略，不修改其他分析成果。
