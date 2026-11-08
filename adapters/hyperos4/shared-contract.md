# 已实现公共契约 v2（2026-11-08，构建验证进行中）

代码已落地，v0.3.2/code10 已完成双方原生 SDK 电池查询和真实执行的实机闭环、失联拒绝；最终源码 v0.3.3/code11 构建测试通过。精确版本与记录见 runtime-validation.md；不得据此声明原生全能力成功。

- `Projection.session_id: String`：64 位十六进制 Noise transcript，会话重连必须改变；稳定 `id` 仅是公钥/SDK 别名。原有 projection 字段保持。
- `BridgeClient.onProjection(Consumer<JSONObject>)`：单品牌独占监听，主线程回调；注册后回放当前状态，null 表示撤回。
- `BridgeClient.projection()`：当前认证投射；适配器须核对 id 与 session_id，不能用 qj 前缀判断归属。
- `BridgeClient.queryRealPeer(String, Consumer<JSONObject>)`：保留旧接口，只查询档案，不代表原生连接成功。
- `BridgeClient.request(String id, String operation, String requestId, JSONObject payload, Consumer<JSONObject> completion)`：有界队列、主线程终态回调；requestId 1–128 字符 `[A-Za-z0-9_:-]`；自动绑定调用时 session_id。
- `BridgeClient.requestBlocking(String id, String operation, String requestId, JSONObject payload): JSONObject`：仅供 OEM 本来同步等待的后台方法使用；主线程拒绝，7 秒本地上限。超时/中断取消排队等待，不声称远端回滚。
- 当前实际操作仅 `device_profile`、`battery_status`，payload 必须 `{}`。响应 `{status:"ok", request_id, session_id, result:{operation, data}}`。battery data 为 `{level:0..100, plugged:0|1|2|4|8}`；plugged 是 Android 电源类型，不是布尔值。
- 电池由远端 BridgeService 注册的 LocalDeviceOperations 实时读取 Android BatteryManager/电池广播，JNI 附加线程调用；无执行器不发布 battery_status。不能把该操作名当作 OEM ability 位。
- 请求错误：peer_offline、stale_session、session_expired、unsupported_operation、forbidden、request_timeout、busy、permission_denied、battery_unavailable、remote_error、invalid_payload、service_unavailable、would_block_main、cancelled。业务错误保留 request_id/session_id；授权/格式错误可能仅有 code。
- Native IPC `bridge_operation` 仍核验厂商 UID/PID、token、递增 sequence，并再次校验 session/真实别名/能力。
- Noise prologue 升级为 `qiangjian-connect/bridge/v2`，两端必须更新；v1 不能误接受新业务。

未实现：通用异步远端任务、远端 cancel/close ACK、剪贴板执行、文件、通知、投屏、应用流转。不得调用猜测的方法或把本地 Future 取消写作远端取消成功。一加入口已通过 ProjectionHooks 委托 OplusProtocolAdapter 接线，原有 installOplus 已移除；本契约会随实际验证更新。

v2 已通过 Rust/JNI/Android 编译与测试。v0.3.2 / code10 支持持有 Wi-Fi request；OEM 拒绝显式绑定时，仍可见 Wi-Fi 与 activeNetwork 才使用系统默认路由并明确记录。既不更改 VPN/系统路由，也不绕过 Noise 双端确认。不能认为该网络选择逻辑消除了 OEM 后台冻结。
