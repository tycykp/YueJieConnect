# Rust 兼容核心

qj-core 实现 JSON 命令、统一观察事件/设备目录、本机 IPC 租约和错误语义。Engine::handle 的 Caller 由 Android Binder 构造，身份不能来自 JSON；时间使用单调启动时钟。

set_enabled/catalog/request 仅限模块 UID，open/observe 限已验证厂商进程。local_device_catalog 是当前唯一业务，其余返回 unsupported。能力位不代表授权，状态只在内存中。详见 [架构](../docs/architecture.md)。

`gateway` 是后续双向设备代理的核心：真实档案 → 目标厂商角色和稳定别名 → 指定真实对端/会话的 Dispatch。它独立于现有观察目录，不把广播记录当成已认证设备；保留真实名称/型号，以真实支持、翻译器实现和用户授权的交集决定可路由能力。尚未连接 JNI 命令、Android 注入和网络传输。详见 [当前实现状态](../docs/device-gateway.md)。
