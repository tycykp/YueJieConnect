# 双向设备代理

## 2026-11-08 后续实现更新

以下初期设计/测试记录保留。代码现已接入 Noise v2、双端确认、session_id、真实档案与 Android 电池执行；ProjectionHooks 分别委托 XiaomiProtocolAdapter 和 OplusProtocolAdapter。小米 deviceprofile 的 shareChannel/send 只对当前登记代理与已实现资料业务接管，真实电池结果再回注 SharedChannelReceiver；不是通用 Continuity Channel。公共契约和精确范围见 [shared-contract](../adapters/hyperos4/shared-contract.md) 与 [能力账本](../adapters/hyperos4/capability-matrix.md)。真实原生业务验收仍以本轮 runtime-validation 为准，不能由这些代码推断已完成全部适配。

2026-11-08 用户澄清：中间层应呈现各家原生接口能够识别的设备形态，并使用真实设备信息进行通信。最终验收是原生应用可以操作真实的异品牌对端，不是只在模块里看到目录。

## 两个方向

| 本机原生接口 | 兼容形态 | 真实设备信息/执行端 |
| --- | --- | --- |
| HyperOS 4 | 可由小米互联接口操作的手机代理 | 一加 Ace 6，OnePlus / PLQ110，真实能力与状态 |
| ColorOS 17 | 可由欧加互联接口操作的平板代理 | Xiaomi Pad 8 Pro，Xiaomi / 25091RP04C，真实能力与状态 |

协议角色、设备类别、必要的本地代理标识由适配器转换；名称、型号、系统信息来自真实对端的桥接服务。虚拟 ID 是路由索引，绑定真实对端公钥与有效会话，不是借用其他设备的系统 ID 或账号凭证。厂商要求的型号字段与真实展示信息若不一致，应分别保存，不能丢失真实来源。

双端 LSP 可以在原生 SDK/业务调用边界实现设备代理，不必把所有通信都强制塞回尚未还原的 Lyra/SCP 握手。但必须同时接管连接、请求、应答、取消及断线，否则发现列表里的代理只是不能工作的条目。仅自建传输成功也不算原生应用兼容；要看到原生入口发出的实际操作在真实对端完成并正确回传。

## 复核到的接管边界

- 小米 `SharedChannelImpl.onDeviceFound` 只更新 activeSharedServices。`shareChannelInternal` 随后仍查该表并调用 `createChannel`；`send(caller,targetDeviceId,bytes)` 查 activeSharedChannels，无通道返回 -12。因而仅加入发现对象不足以通信，还要针对本模块代理 ID 接管连接/发送及结果，正常设备继续原路径。该缓存入口不等于所有小米原生设备 UI 的发现入口，UI 投射仍需沿使用方补齐。
- 欧加 `ConnectionServiceClientsImpl.startGlobalDeviceDiscovery` 注册 Agent 回调并调用 AppIpc；`handleEvents` 将 DisplayDeviceItem 转为 DisplayDevice，再分发 found/lost。`DisplayDevice.requestConnection`、confirm/close/cancel 继续委托 ConnectionService。需要保存原生调用者回调、注入真实对端的代理对象，并把代理的连接/业务请求导向桥接服务。
- 角色值来自本次样本：小米 `continuity.netbus.DeviceTypeV2` 为 PHONE=1、PAD=2；欧加 `pantaconnect.sdk.DeviceType` 为 PHONE=8、PAD=10，`DisplayDeviceExtension.toMajor` 对应相同类别值。它们是 SDK 模型值，不是跨厂商线上广播位。

以上基于本地反编译文件：小米 `SharedChannelImpl.java:1037/1167/1253`、`DeviceTypeV2.java`；欧加 `ConnectionServiceClientsImpl.java:830/948`、`DisplayDevice.java`、`DeviceType.java`、`DisplayDeviceExtension.java:125`。新连接/业务 Hook 尚未安装；仍需原始 DEX 复核与适配后的定向实测。

## 本次实际新增代码

`core/src/gateway.rs` 提供：

- RealDevice 保存真实平台、名称、厂商、型号、系统、类别、桥接身份公钥及支持的操作。
- Projection 按目标 SDK 映射手机/平板角色，用公钥和目标 SDK 生成稳定别名；设备改名不改变路由身份。别名目前是内部标识，写入原生对象前还需适配器核对其格式约束。
- Gateway::publish 绑定真实档案和会话身份；可用操作取 **真实端支持 ∩ 本地已实现转换 ∩ 已授权操作**，不透传未知 capability 位。
- Gateway::route 把本机原生请求中的代理 ID 解析为确切真实 peer、session、operation 和 request ID。Dispatch 仅表示待发送指令，不能当作原生完成回调。
- 会话过期、错误适配器、未知别名或未实现能力拒绝路由；disconnect/expire 返回需要撤回的代理 ID。

ConfirmedLink 仅声明与未来认证传输层之间的可信调用契约，没有实现密钥握手，也没有暴露给 IPC JSON 构造。当前已安装的 0.1.0 APK 不使用此新模块。尚未实现 Android 对象注入、跨设备认证传输、厂商业务负载转换和应答回注；不能称为已经模拟成功或已经互通。

本次只运行新增 gateway 的 4 项定向测试以及 qj-core clippy，均通过：真实信息保留/精确路由，改名与目标 SDK 隔离，会话过期/断线/错误来源拒绝，身份绑定与授权交集。没有重新扫描实机、重装 APK或重跑原有测试套件。

## 后续实现顺序

1. 两端桥接服务取得本机真实档案，建立有用户确认、可撤销的认证会话，交换实际支持能力。身份不能来自未经认证的发现广播。
2. 先接好代理 ID 的连接、取消、请求和应答路由，再向原生客户端发布代理设备；断开时同步撤回。
3. 选择有完整证据的单项业务，实现双方语义转换、真实执行和结果回传。只有真实对端确认的结果才能转成原生成功回调。
4. 用一次明确的双向原生操作验证整条链路；失败按具体阶段诊断，不反复扫描碰运气。
