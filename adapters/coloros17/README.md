# ColorOS 17 适配器

品牌实现位于 android/module 的 OplusProtocolAdapter、OplusDeviceKitAdapter、OplusPeerRegistry、OplusClipboardCodec/Endpoint。共享 ProjectionHooks 的 source=2 已实际接线；原有 DiscoveryModule 观察回调保留。

2026-11-08 实机已验证：一加原生“添加新设备”显示真实 Xiaomi Pad 8 Pro；原生 DeviceKit 电池查询返回小米实时75%；停止真实会话后条目撤回。原生连接点击仍报连接失败，完整业务会话、文件、剪贴板、投屏、应用流转尚未通过，不能称全部适配完成。

见 [实现状态](implementation-status.md)、[本轮实机证据](runtime-validation-20261108.md)、[业务覆盖与缺口](business-coverage.md)、[公共接口需求](integration-needs.md)。声明清单包含4个Synergy服务、9个RPC、76个命令/键常量，并非全部已支持。

14个原始DEX类、38个方法描述符由 `scripts/verify-oplus-adapter.py` 校验；9项一加定向JUnit、构建与lint通过。SCP离线工具、OCS/NDSF/OOBKEY与SDK代理是不同边界。历史证据见 [总报告](protocol-analysis.md)与 [SCP专项](coloros-to-coloros-protocol.md)。
