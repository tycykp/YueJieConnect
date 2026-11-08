# 一加实现状态

## 续轮进行中：纯文本剪贴板

本轮新增OplusClipboardAdapter/Receiver/Tasks/NativeResult/SelfTest；20:00:09一加原生OEM写入、读回、恢复原剪贴板实测通过。发送侧绑定明确dvd的原生ClipBoardConsumerImpl，结果使用原始`ClipBoardConsumerImpl$3`及`Clipboard.Result`回注真实远端结果；没有设备ID的广播consumer不接管。

当前累计16项Oplus测试、编译/lint通过；20类/58个原始DEX描述符已核验。剪贴板跨设备仍待公共厂商进程任务契约接入，因此新发送/接收适配器暂未安装到主适配器，不发布clipboard能力。本机sink成功不能代替跨机业务验收；本轮仍在推进，详见integration-needs.md顶部。

以下为上一轮已完成的电池/发现验收基线。

2026-11-08：**可接入，已接线，已实机装载；未完成全部适配。** 共享 ProjectionHooks 的 source=2 已委托 OplusProtocolAdapter，旧品牌重复hook已移除。

OplusProtocolAdapter使用精确alias/session匹配，tempId绑定当前session，替代旧代码的qj前缀接管；Noise档案往返不等于原生连接。

已实现精确 alias 登记、会话 generation、发现注册 owner/设备类型/期限、列表去重、请求超时/取消/拒绝/断线终态、迟到回调抑制。原生连接仍没有业务通道，明确失败，绝不将 profile 成功转换成连接成功。

新增 OplusClipboardCodec 严格限定 feature=0 UTF-8，拒绝 Parcel/URI 与非法编码；OplusClipboardEndpoint 使用样本真实 ClipBoardServiceHelper 写入并读回核验。目前公共契约没有 clipboard 调用，此执行器尚未注册，不能标成业务已通。

已依赖实际共享契约v2；session_id必填。OplusRuntimeProbe仅在debug APK、DUMP保护下调用已配对真实设备的原厂SynergyDeviceManager，不自动确认或模拟结果。

更新：session_id 已必填。OplusDeviceKitAdapter 已接到 OpDeviceServiceClient 的按 ID/类型查询，走 BridgeClient.request battery_status，校验 request_id/session_id/operation/level 后转换成真实 SynergyDeviceInfo + batteryLevel_1280_1 回调。plugged 不等于 charging，不生成 batteryCharging。该只读属性支持不表示 Pantaconnect 原生业务连接已完成。

14个原始DEX类、38个方法描述符通过；一加9项JUnit、assembleDebug通过；lint为0错误、4项现有警告。最后“原厂停止失败保留发现”“混合查询过载保留原条目”两项修复后已重跑JUnit/lint/构建。

所有已有未提交修改保持原样；不编辑公共入口、BridgeClient、Rust core/native/transport 或小米文件。

## 实际验证与产物

- 一加正常原生“与平板互联→添加新设备”实际显示Xiaomi Pad 8 Pro。
- 19:41:37原生DeviceKit跨机读取小米电量75%，与小米本机基线一致；正常原生页面还触发了同一查询链。
- 一加作为执行端，小米线程收到Android电量94%，后续实时更新100%/USB=2；较早一加截图状态栏94，随后本机基线100，保留时间差异。
- 19:43:05真实点击条目后仍“连接失败”/native_business_unavailable，未伪造原生通道。
- 停止会话后原生列表小米条目消失，19:46:31离线探针返回peer_unavailable。

实机会话APK为v0.3.2/code10，SHA256 `b91195ca65a274c4bee674621983d6727af034121cb5542c04f4395fc941b735`。最后两项边界修复后最终APK为v0.3.3/code11，SHA256 `ca7c8112a61c9ffe5569e320874b00444f182546ec477b1f16fcaa0ca99ae281`，19:46:51安装一加并验证主进程/:app装载、caller_verified。最终小修APK未重建完整跨机会话；会话已停止，不沿用旧授权。

为解决已实证Hans冻结销毁socket，通过一加系统界面仅将本应用设为“完全允许后台行为”；未改全局冻结、VPN或框架。随后正常原生UI前台时会话保持active，未宣称无限后台可靠性。

公共层现仅实现device_profile/battery_status，没有native logical open/close/cancel、原生业务通道或厂商进程任务执行契约，因此不能生成连接成功信息。文件、完整剪贴板、通知、投屏、应用流转等未完成转换与真实执行验收；拒绝与能力清单不是完成标志。详见runtime-validation-20261108.md、business-coverage.md、integration-needs.md。
