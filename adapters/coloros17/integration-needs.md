# 一加公共接入需求（2026-11-08）

## 续轮：正在实现纯文本剪贴板（最新请求）

**用户最新授权（本对话明确答复）**：“本对话接手公共剪贴板接口，小米暂不修改这些共享文件”。自此公共剪贴板所需 BridgeClient/BridgeService/NativeCore、core/native/transport 的修改由一加线程执行；小米继续自有UI/协议代码，请暂不编辑这些共享文件。会在本文件和shared-contract中公布确切签名及集成状态。该授权取代本轮原先的共享文件只读限制，仅限公共剪贴板接口及必要接线。

用户已要求继续适配，两个品牌对话均 active；仍保留原文件分工。一加本轮负责原生发送 hook、接收端真实写入/读回、取消与重复任务防护。公共层请优先提供厂商进程执行任务的注册/拉取/完成/撤销契约，以及 `clipboard_write` 标准负载，落地实际签名后写 shared-contract.md。

已有品牌执行器 `OplusClipboardEndpoint(ClassLoader).writeText(Context,String)`，必须在com.oplus.linker进程主线程调用。建议公共执行回调携带 projection_id/session_id/request_id/operation/payload/deadline；接收时再核对当前session、operation已授权且目标为本机，重复request不重复写入。回传仅ack真实写入和读回结果，cancelled/expired在执行前禁止写入。应用层text最大4096 UTF-8字节，默认不发送HTML/URI/文件。clip内容不能写日志。

建议标准operation为clipboard_write，payload `{text:String, mime:"text/plain"}`；ack `{operation:"clipboard_write", data:{applied:true, utf8_bytes:N}}`（仅示例待公共契约确认，不提前调用猜测接口）。可以沿现有BridgeClient.request发送；入站执行需要新的实际已实现公共注册接口。品牌代码将按实际落地契约接线。

负载边界补充：现有Binder JSON上限8192，Noise帧16384，4096字节纯文本若全为控制字符，JSON转义后可超过两者。建议公共线格式用 `{mime:"text/plain", utf8_b64:String}`（原始UTF-8最多4096、Base64最多5464），入站严格解码后再交品牌String执行器；不能截断文本或把超长帧失败写作成功。上面的text字段只是初稿，请按实际公共实现统一，避免两端不同格式。公共执行轮询必须独立于等待出站request的单线程队列，否则两端同时写剪贴板可能互相阻塞。

连接范围：样本上层先有Pantaconnect logical connect，再独立DataBus/OAF会话。不可把电池或档案成功换成全功能连接。需要公共logical open/close/cancel时请绑定真实远端执行能力，不能只返回裸connected=true。此处优先剪贴板业务闭环，其余能力保持未宣告。

续轮品牌代码已落地且编译/15项Oplus测试/lint通过，16个DEX类/46个方法复核通过：

- `OplusClipboardAdapter` 原生单设备 consumer hook：setStatusListener(dvd,...) 精确绑定alias/session；getInstance仅在真实远端clipboard执行能力就绪时initSuccess(dvd)，否则initSuccess("")；copyToClip/copyToClipOld转换并等待真实receipt；无dvd的广播consumer和原有其他设备保持原行为。Transport接口在品牌内部，尚未接入猜测的公共方法。
- `OplusClipboardReceiver(Context,ClassLoader,BooleanSupplier,Supplier<JSONObject>)` 的 `CompletableFuture<Result> execute(String session,String request,String text,long deadlineElapsedMs)`、`cancel(session,request)`、`sessionChanged()`、`close()`。Result字段为session/request/utf8Bytes/error，error=null才applied=true。Android主线程真实OEM写入并读回。
- `OplusClipboardTasks` 256条/当前会话有界且不驱逐已完成记录，防重复写入、同request不同payload、过期任务、重连旧请求；执行前cancel阻止写入，运行后不伪称回滚。需要公共层保证入站调用来自授权的认证session/clipboard操作。

公共契约一旦落地，本线程会接入 OplusProtocolAdapter 生命周期并扩展DUMP保护原生探针验证真实写入。当前新类不宣告剪贴板已可用，不能仅靠这些类称完成。

**20:00:09 本机执行端实测通过**：一加com.oplus.linker:app的DUMP保护probe，真实OEM copyPureTextToClipBoard→ClipBoardUtil读回成功，并经copyCompleteDataToClip恢复原剪贴板，日志 `clipboard_self_test native_write_readback_ok restored`。测试文本临时生成，未输出用户剪贴板内容。安装sha256 f303008919b5dbaf7d630e07b945f320d12e439ecf8ddcd7defa4cdcc65d05ef。它是本机sink验证，跨设备仍等待实际公共clipboard写入/厂商执行任务契约。请优先补齐该接口，品牌端已经有真实可执行代码，不能继续只把公共缺口记录为unsupported后结束本轮。

20:05 一加只读状态发现新session `b597980680a01622dc7637bc90d22162786e5524bd83310ecb6cf436815e06a8` 已active且双端confirmed，requests_received=10，本线程未建立/确认该会话。已看到小米正常UI显示与电池工作进展，将保持本会话、不再安装/停止，供小米UI验证。剪贴板入站/发送新代码暂未接线，现有会话仍只有battery/profile能力，不会借这个只读会话发送用户剪贴板。下一轮公共写入能力/授权需独立更新。

本文件仅提出需求，不宣称公共接口已经实现。当前已读取的 BridgeClient 只有 `onProjection(Consumer<JSONObject>)`、`projection()`、`queryRealPeer(String, Consumer<JSONObject>)`；Rust bridge 仅交换 DeviceProfile。

## 入口

请在 ProjectionHooks 的 source=2 创建 `OplusProtocolAdapter(XposedModule, BridgeClient, ClassLoader, BooleanSupplier)` 并调用 `install()`；拆除旧 installOplus 全部 hook，关闭时调用 `close()`。品牌类独占其 onProjection listener，不能再由旧 update 覆盖。本线程将在 implementation-status.md 标明可接入时点。

## 当前必须补齐的公共契约

1. **会话身份**：projection 需要不可复用的 `session_id`（或 epoch）。同一公钥断线重连 alias 不变，品牌适配器必须废弃旧请求/确认/业务上下文；只有 alias 不足以防止旧异步应答复活。
2. **异步请求及实际执行端**：需要 BridgeClient 可调用的 request(projectionId, operation, requestId, payload, completion)，由远端 Android 原生执行器产生真实完成/错误；远端请求注册、ack、cancel 与断线事件。不要把 queryRealPeer 成功转换成 Pantaconnect onConnectionSuccess。
3. **能力**：已配对、已授权、双端实现交集才发布 operation。SDK ConnectionType 是 WLAN/P2P 等传输类型，不能冒充具体业务能力；当前仅 device_profile，原生 ability 保持 0。
4. **关闭/取消**：请求 id 独立，取消可关联到正在处理的远端调用。远端确认关闭或明确未创建连接才返回 Boolean=true；不能仅取消 Java Future 就断言远端已取消。断线必须向所有正在等待的调用发出终态。
5. **错误**：至少保留 peer_offline、session_expired、permission_denied、cancelled、timeout、unsupported_operation、remote_error、invalid_payload；执行结果必须带实际 request/session 绑定。

## 首项业务候选：纯文本剪贴板

样本确有 `com.oplus.linker.synergy.wisetransfer.clipboard.icdf` 下 consumer/provider/service 实现，正在核验数据结构和调用路径。建议公共执行契约先提供有尺寸限制的纯文本数据及真实 setPrimaryClip 完成结果；HTML/URI/文件剪贴板需要另行转换，不能宣称一起支持。具体原生 hook、需要的 public 签名与验证证据将在核验后追加。

一加代码只依赖已落地签名；本文件不是要求另一端盲目照搬尚未核验的方法名。

## v2 对接进展

已读 shared-contract.md v2；session_id 与 request 已实现，已不再作为公共阻塞。OplusProtocolAdapter **已可接入且编译通过**，请 source=2 接线。

纯文本一加执行端已落地 `OplusClipboardEndpoint(ClassLoader)`，`String readText()`、`void writeText(Context,String)`；构造/调用可抛 ReflectiveOperationException，SecurityException 表示拒绝，clipboard_write_unverified 表示读回未确认。运行环境必须为 com.oplus.linker 的 Context/ClassLoader；不可由模块 :bridge 直接加载厂商类。对应公共注册/轮询/请求回传接口尚缺。无新授权时不读取剪贴板，不发布 clipboard 能力。

本品牌下一项优先对接 v2 实际有执行器的 battery_status，正在核对 Pantaconnect/DeviceOperateManager 属性入口，避免仅在模块界面查询。

更新：OplusDeviceKitAdapter 已完成 OpDeviceServiceClient 的 queryOriginalDeviceListWithIds/queryOriginalDeviceList/queryDeviceListByTypes → battery_status → SynergyDeviceInfo.propertyList 转换并接入主适配器；仅返回 batteryLevel_1280_1，plugged 不能冒充 batteryCharging。13 个原始 DEX 类的 34 个描述符已通过。共享 ProjectionHooks 已确认接线。

构建协调：首次 assembleDebug/JUnit/lint 通过；小修后的重建偶发依赖 metadata 缺失（guava-parent-33.4.0-jre.pom，junit-bom 5.10.2/5.11.0-M2 module）；未更改 verification-metadata、未禁用验证。正在通过本机既有代理重试。公共构建文件归小米线程。

更新 19:30：使用既有 127.0.0.1:7897 Java 代理后 assemble/JUnit/lint 全通过，未改公共构建配置。已在一加安装 APK sha256 e1ce638c23372ad65c81d1ba518698c441b879a7437fcd963c8550fa35445064；一加主进程和 :app 均记录 oplus_adapter_installed + caller_verified。一加 WLAN 192.168.1.185，本线程已准备监听 38271。请小米线程仅操作小米端连接此地址，在自家文件记录会话/比对码；本线程不操作小米。仍需要两端真实配对确认，禁止以未确认 session 投射。

最新：已读小米 19:35 监听通知，一加改为主动连接 192.168.1.97:38271，请保持小米监听端，勿反向发起第二条连接。本线程将记录一加实际会话/比对码。

19:33 实机阻塞：两次一加主动连接都真实进入 awaiting_confirmation，随即 write_failed/errno=103 断开；第二次 session=a8deac3c1d76d3d0c230d898859ece25bd62287197dc716a758210335f621ec1。没有机会完成确认、未调用 bridge_confirm。一加日志同时有 route_binding_failed / route=default_wifi_binding_unavailable。小米看到 peer_closed 与此吻合。请公共层排查 bindWifi/网络切换；本线程不改共享文件。目前 probe_registered 已实测，广播 OPLUS_PROFILE_PROBE 仅调用真实 SynergyDeviceManager.queryOriginalDeviceList（已配对目标），不伪造任何结果。

**19:36 已定位更直接根因**：一加系统日志 OplusHansManager `freeze uid:10476 io.github.qiangjian.connect pids:[32371,32439] scene:StrictMode-1|LcdOn` 随即 netd `destroyAllSocketForUid:10476`，后续 Binder 又解冻。这发生在 :bridge 已 isForeground=true/connectedDevice 且 LAN 权限已允许的情况下；因此不是 Noise 协议失败。将保留模块 MainActivity 在前台作为一次诊断验证；不更改系统框架或全局冻结策略。请小米重开监听供该单次验证。正常原生界面后台运行仍需解决每应用后台活动许可，前台诊断不能当作产品稳定性验收。

最新 v9：已安装 sha256=61c8cf251c816e7caaf81e314dc6c2fb9152da40b0ddc014b38ddc5e0af0a9b4，原生 :app 装载后切回模块 MainActivity 保持前台，再执行一次 bridge_connect。现在直接返回 **wifi_route_unavailable**（尚未握手）。CHANGE_NETWORK_STATE/INTERNET/ACCESS_NETWORK_STATE/ACCESS_LOCAL_NETWORK 都已 granted；netpolicy restrict-background disabled，ACCESS_LOCAL_NETWORK appops default allow。继续公共路由诊断，不能当成业务代码成功。一加最终 probe/设备 DEX 校验现为 14 类 / 38 方法。

一加后台设置已处理：通过系统“应用详情→耗电管理”实际查看原为“智能优化后台运行”；已仅对 io.github.qiangjian.connect 选择“完全允许后台行为”并确认系统对话框，读回单选状态为 true。没有改全局 Hans、VPN、框架或小米设备。此后 v9 仍 wifi_route_unavailable，所以 WifiRoute 的绑定问题与后台冻结需要分别处理。当前强制绑定失败使握手根本不能开始；可考虑在验证 activeNetwork 正是非 VPN Wi-Fi 时使用该已确认默认网络，不能无条件回退未知默认路由。需要公共线程实现/裁定。

**v10 单次会话已建立，等待真实确认**：一加已安装与小米相同 b91195ca65a274c4bee674621983d6727af034121cb5542c04f4395fc941b735。session=`16c41a65e0ec1234ce8be77747c33ce607f804d2ce91a89d14eea514e0795b98`，一加显示比对码 **0e6365-f524e9**，local_confirmed=false / remote_confirmed=false，无错误。本线程将请用户在两端确认，不自动 bridge_confirm。不要重启/重装/建立第二会话覆盖。已安装产物稳定；最新两个品牌安全小修（stop失败保留发现、混合查询busy保留原条目）尚只在源文件，后续最终构建另记录。

用户已在本对话答复：**“两端码一致，已分别确认”**（0e6365-f524e9 会话）。正在读状态并执行一加原生 DeviceKit probe。可继续小米侧原生探针，仍不要重启/重装本次会话。

**19:41:37 原生 SDK 只读业务闭环成功**：一加状态 active，双方 confirmed=true；DUMP 保护 probe 调用原始 SynergyDeviceManager.queryOriginalDeviceList，19:41:37.294 native_query_started → 19:41:37.581 native_battery_received level=75（小米真实远端电池）；没有使用 profile 冒充业务结果。仍须区分诊断 API 调用与正常 UI 自然入口，正在检查一加原生界面与后台存活。证据 build/oplus-validation/runtime.json；该文件是本线程专属输出。

**正常 UI 发现已证实**：一加“与平板互联→添加新设备”真实出现 Xiaomi Pad 8 Pro，截图 build/oplus-validation/native-discovery.png。正常页面触发多次 DeviceKit 查询，主进程出现 oplus_native_battery_result_delivered。19:43:05 点击该真实条目，SDK requestConnection → queryRealPeer 成功 → native_business_unavailable，UI显示“连接失败”。这不是验收成功；公共层还缺实际 logical/open/close/cancel + 对应业务远端执行器，不能生成 GlobalDeviceConnectionInfo 宣称通道已建立。

当前保持会话供小米探针；小米验证后请在自己状态文件标记“可断开”，本线程将测试一加设备撤回。不会为单侧测试提前关闭另一侧正在验证的会话。

已读小米 19:42:55 探针成功；一加 UI 检查也完成，接下来本线程将 bridge_stop 验证真实会话撤回，无需重新确认。请小米侧可在失联后触发只读探针验证 peer_unavailable；勿重新发起连接掩盖撤回。用户已确认的会话不持久化为新的自动授权。

**暂缓断开：一加基线发现差异**：随后一加 dumpsys battery 为 level=100 / USB powered=true / status=2，cmd battery get -f level=100，root只读 sysfs /sys/class/power_supply/battery/capacity=100；与小米探针之前的94不同，不能未经核实写成匹配。公共 reader 用 BatteryManager.getIntProperty(CAPACITY)，可能与系统展示值不同或有其它拦截；请小米侧再次一次真实查询看是否仍94。不会设置/模拟电池或 reset 电池状态。正向一加收到小米75与小米本机75匹配不受此问题影响。

基线补证：已实际查看 19:43 原生发现截图 build/oplus-validation/native-discovery.png，**一加状态栏明确显示94**，与小米 native_onResponse level94一致。dumpsys/sysfs100与应用可见电量94存在差异，原样保留记录，不推测/修改 ROM 或其他模块。公共 reader/API 与用户界面值一致，可据此描述“读取 Android BatteryManager 电量94”，不要写“原始内核电量94”。准备断开，若小米已再次取样则直接记录即可。

**撤回已执行**：一加 bridge_stop 返回 ok，回到仍打开的原生 NewDeviceDiscoveryActivity，Xiaomi Pad 8 Pro 条目实际消失；UI XML 已保存，同场景截图 native-withdrawn.png。可进行小米失联检查。最后源级 stop失败/混合查询busy 修复已单独通过一加9测试、lint与14类38方法DEX校验，现构建最终APK，不再新建会话。

**最终交付状态**：源级两项边界修复最终APK sha256=`ca7c8112a61c9ffe5569e320874b00444f182546ec477b1f16fcaa0ca99ae281`，一加已19:46:51安装并验证主进程/:app API102 hooks及caller_verified。共享Native/transport契约未改，两项仅一加Java边界变化；无需为此打断其它设备。完整跨机/UI测试对应b91195ca...，最终小修对应JUnit/lint/DEX/构建与实机装载，记录明确区分。当前会话已停止，一加原生撤回/离线拒绝通过。品牌README、implementation-status、business-coverage、runtime-validation已更新；完整连接/其余业务仍由上述公共logical session/执行器缺口阻塞，未标记全部适配完成。
