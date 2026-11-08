# HyperOS 4 适配器

2026-11-08 本轮已实现 `XiaomiProtocolAdapter`、原生 deviceprofile 请求/电池应答转换，以及共享 v2 会话/typed RPC/真实 Android 电池执行端。完整当前范围见 [能力账本](capability-matrix.md)、[公共契约](shared-contract.md)、[进度](implementation-status.md)。未知 caller/能力拒绝，只有确切登记且绑定当前会话的代理进入转换。

尚未完成全能力原生互操作：融合设备中心 UI 卡片、文件、剪贴板、通知、投屏和应用流转不能标记成功。历史分析与以下早期状态保留，当前交付以本轮验证记录为准。

实现位于 android/module 的 DiscoveryModule.java 与 core。入口为 com.milink.service 的 SharedChannelImpl.onDeviceFound/onDeviceInfoChanged/onDeviceLost，仅观察 serviceId 00070A2A，不主动发现或修改回调。

真实签名和固定版本见 [Hook 依据](../../docs/hooks.md)。Lyra 认证、设备投射和业务未实现。MiShare 不替代整个设备互联。[历史静态报告](protocol-analysis.md)保持原样，当前状态以项目 README 和兼容性记录为准。
