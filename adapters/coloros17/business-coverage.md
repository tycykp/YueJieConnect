# ColorOS 17 样本业务与协议覆盖

样本固定为 PLQ110_17.0.0.104(CN01)、com.oplus.linker 17.7.30，远端 Xiaomi Pad 8 Pro。以下是代码覆盖清单，不是互操作成功清单。原始样本位于 captures/device-interconnect-20261108/coloros17/analysis/jadx/linker/sources；SCP 线上证据另见 coloros-to-coloros-protocol.md。

## 原生 SDK 边界

| 环节 | 样本调用点 | 本次实现与剩余缺口 |
| --- | --- | --- |
| 发现注册/停止 | ConnectionServiceClientsImpl.start/stopGlobalDeviceDiscovery | 保留原调用和失败；成功后记录 owner/callback、类型过滤、发现期限；精确登记当前 alias；撤回时 lost。不是 BLE 广播伪造。 |
| 拉取设备列表 | getAvailableDevices / getDisplayDeviceList | 在原 Future 成功结果中加入已认证代理并按 ID 去重，原设备保留。 |
| 身份与会话 | DisplayDevice 15 参数构造器 | 真实名称/型号、PAD=10、NO_ACCOUNT、DISCOVERED、ability=0；会话 ID 必填；不制造同账号、OOBKEY、P2P IP。 |
| 连接请求 | ConnectionServiceClientsImpl.requestConnection | 有界请求、超时、精确 alias 路由、profile 实际往返诊断；没有可使用的原生业务连接时失败。未完成原生连接。 |
| 确认 | confirmConnection(DisplayDevice,PairAction,ConfirmType) | 只接管已登记代理；拒绝终止等待，ACCEPT/PIN/CUSTOMIZE 不冒充已认证成功。公共层尚无 OEM 连接执行器。 |
| 取消/关闭 | ConnectionServiceImpl.cancelConnection / closeConnection | 正确拦截返回 CompletableFuture 的 wrapper；ClientsImpl 对应方法返回 SealedResult，不混用类型。本地等待取消不冒充远端业务取消；无原生连接时 close 返回 false。 |
| 断线/重连 | projection=null / session_id 改变 | 清理等待、发 lost、废弃 generation，迟到应答不能恢复旧请求。alias 同一公钥稳定，但 session 独立。 |
| 错误 | GlobalDeviceConnectionCallback.onConnectionFailure / DeviceKit.onError | 显式 peer_offline、stale_session、timeout、cancelled、permission_denied、native_business_unavailable；未知操作不返回成功。 |
| 电池读取 | OpDeviceServiceClient.queryOriginalDeviceListWithIds / queryOriginalDeviceList / queryDeviceListByTypes | 已接真实 BridgeClient.request(battery_status)；严格对应 session/request/operation；结果写入 SynergyDeviceInfo.propertyList 的 batteryLevel_1280_1。不把 plugged 当 charging。真实原生端到端验收仍受会话中断阻塞。 |

电池属性使用方已追到 `PadHeadPreferenceFragment.onCreate$lambda$1` → `PadCardPreference.setBatteryPower`。这里只读属性映射不能表示该面板所展示的文件、投屏等按钮已经可用。DeviceKit 对象 deviceState 始终 DISCOVERED，feature/coordination 不增加未支持位。

## 业务面

| 业务 | 样本证据/实际方法 | 目前状态 | 所需公共与远端工作 |
| --- | --- | --- | --- |
| 纯文本剪贴板 | ClipBoardService/copyToClip,getClipData；ClipBoardConsumerImpl；ClipBoardServiceManager.processPureTextClipData | UTF-8 feature=0 转换、4096 字节限制、非法编码拒绝；一加写入 sink `ClipBoardServiceHelper.copyPureTextToClipBoard` + ClipboardManager 读回核验已经实现。未安装剪贴板发送 hook/未注册远端执行器，能力不发布。 | 授权后向厂商进程投递任务、实际用户目标路由、远端 ack/cancel；小米纯文本对应转换；与 OEM loop suppression 联调。 |
| 完整剪贴板/URI | feature=1 为 ClipData Parcel + UriRawInfo；ClipboardFileService/getUriContent | 明确拒绝转成纯文本；未适配。 | Android Parcel 不跨系统版本透传；标准 MIME/条目/文件 URI、授权、流式数据、取消。 |
| 文件读写/管理 | FileService/createFile,deleteFile,openFile,readFile,writeFile；FileServiceConsumer/Provider | 9 个 RPC 中的这 5 个已列入完整声明清单；没有执行转换。 | OAF/ICDF 的 104/110 逻辑通道、FileInfo/FileData、路径/URI/授权、流控与哈希验收。OShare 不代替此项。 |
| 远端输入 | InputService/inputToTextbox；inputservice/icdf/InputServiceImpl | 声明和 provider 存在，未适配。 | 输入目标、IME/焦点、拒绝、真实远端执行结果；不能只传字符串就称键鼠协同。 |
| 投屏/窗口/音频 | SynergyCmd.start_mirror/start_mirror_result/stream_init/prepared/start/stop、mirageWindow、codec_info、vdc_audio_*；pc/cast | 已列命令，未完成帧/媒体/控制转换。 | 编解码能力、流启动/关闭、输入/窗口与权限、真实媒体接收端。 |
| 应用流转 | AppRelayActiivty、appfunction/AppRelayStateRepository、SynergyCmd relay/window 状态 | 样本有入口及状态实现；未验证当前手机到平板具体分支、未适配。 | 目标应用/任务、状态数据、接力授权、启动与取消结果。不能按设置标题声明支持。 |
| 通知 | PadPanelDataBusHelper.PAD_NOTICE_STATUS；iOS notification/IPhoneNotificationManager | Android 平板通知开关和独立 iOS 通知分支存在；不能把 ANCS 当成 Android 通知协议。未适配。 | 追踪 Android 通知 payload/provider、用户许可、通知去重/撤销、真实送达确认。 |
| 网络共享/状态 | PadPanelDataBusHelper SHARE_NETWORK/SHARE_NETWORK_ENABLE/恢复网络状态 | 样本状态及控制入口存在；未适配。 | WLAN/热点/蜂窝权限与状态机，不能把电池业务通道当网络共享。 |
| 通话/SMS | SynergyCmd synergy_accept_phone/end/ignore/respond_via_message；nearcomm 样本另存 | 命令存在，尚未证明全部用于当前 phone→pad 分支；未适配。 | 具体协同权限、SIM/通话状态、音频与拒绝语义；不发送通信内容作验证。 |

`capability-inventory.json` 穷举本次四个 Synergy *ServiceGrpc 的 **4 服务、9 RPC**，以及 SynergyCmd 的 **76 个命令/键/标志常量**，带反编译源 SHA-256 和行号。声明不等于可达实现；它不是对全部 accessory、iOS、云端 SDK 的“完整支持”断言。每项缺口均保持未完成，不能将此表或离线 SCP codec 当成全部适配验收。

## 执行端与传输边界

一加作为真实远端：公共 v2 BatteryReader 在模块服务读取 Android BatteryManager；剪贴板则必须在 com.oplus.linker 的 Context/ClassLoader 中调用 OplusClipboardEndpoint。模块 :bridge 无厂商类加载器，不能直接反射 OEM helper。当前没有厂商进程任务注册/回传的公共接口，因此剪贴板执行器保持未接线，未声称可用。

Noise v2 是 SDK 边界代理的认证请求承载；没有复刻 SCP/UKEY2/OOBKEY。只有真实 SDK 请求→跨设备真实执行→SDK 应答的闭环才能验收业务。一次桥接连接、profile 查询或 debug probe 注册均不足够。
