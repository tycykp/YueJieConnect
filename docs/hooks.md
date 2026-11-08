# 发现 Hook 依据

2026-11-08 晚间更新：本页下方是原被动发现基线。当前 ProjectionHooks 仅做品牌委托；XiaomiProtocolAdapter 新增 onInitialize/onRelease/removeCaller、3 个 shareChannel 重载和 send 接管，只针对当前真实会话登记的代理及已实现 deviceprofile 业务。不是仅凭 qj 前缀接管。新增 descriptor/字段及原始业务指令见 [小米 DEX 证据](../adapters/hyperos4/dex-protocol-evidence.json)，可运行 scripts/verify-xiaomi-protocol.py 复核；[实机记录](../adapters/hyperos4/runtime-validation.md) 已证明电池请求到原生回调的闭环。

完整 APK/DEX 哈希与方法描述符见 [基线](hook-dex-evidence.json)。scripts/verify-hooks.py 读取忽略的原始 captures，对哈希和真实 DEX 描述符做比较；变化即失败，未使用 JADX 自动重命名推测运行时签名。

| 来源 | 实际方法 |
| --- | --- |
| 小米 | com.miui.circulate.channel.SharedChannelImpl.onDeviceFound(String, com.xiaomi.continuity.netbus.DeviceInfo): void |
| 小米 | 同类 onDeviceInfoChanged(String, int, DeviceInfo): void |
| 小米 | 同类 onDeviceLost(String, DeviceInfo): void |
| 欧加 | com.oplus.linker.connect.arch.discovery.DiscoveryImpl$startSearch$2.onDeviceFound(com.oplus.pantaconnect.sdk.connectionservice.connection.DisplayDevice): void |
| 欧加 | 同类 onDeviceLost(DisplayDevice): void |

小米反编译位置为 SharedChannelImpl.java 1037/1045/1053 行，回调维护 activeSharedServices；仅接收 serviceId 00070A2A。getter：getDeviceId/getDeviceName/getDeviceType/getCapability/getCapabilityV2/getCapabilityV3。

欧加 DiscoveryImpl.java 110–131 行 callback 由 startGlobalDeviceDiscovery 使用；DEX 类名确为 $startSearch$2。getter：getProtocolDeviceId/getDisplayName/getDeviceType/getAbility；DEX 同时存在 getter 与 componentN，代码使用真实 getter。不读取账户状态、摘要或 IP。

此外只在目标包进程 Hook Android Application.attach(Context)，原方法完成后取得 Context 并验证身份/初始化异步客户端。缺失签名时撤销本组 Hook，记录 unsupported_hook_signature。没有 system_server 作用域。

原方法仅 proceed 一次，原异常继续传播；只复制标量，观察错误隔离。版本/当前证书变化则禁用采集。装载与实际命中仍需实机验证。
