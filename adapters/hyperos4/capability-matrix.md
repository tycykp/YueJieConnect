# Xiaomi Pad 8 Pro 原生互联能力账本

样本固定为 25091RP04C / OS4.0.5.0.XPYCNXM。目标真实对端为 OnePlus PLQ110。以下是本次样本已发现的协议面和业务面，不能据文件名称推导设备实际支持；逐消息清单见 `mirror-message-inventory.json`。native 库内部尚未完整还原，所以这不是“所有线上协议已穷举”的声明。

## 状态与转换边界

| 协议/能力 | 样本直接证据 | 本轮实际代码 | 原生验收/剩余工作 |
|---|---|---|---|
| 身份、发现、能力 | SharedChannelImpl.onDeviceFound；DeviceInfo.Builder | 精确注册 alias，保留真实 name/model/platform，session_id 绑定 Noise transcript；手机类型=1；不伪造同账号/capability 位 | SharedChannel 内缓存发布已接；融合中心 UI 卡片发现未证实 |
| 桥接认证 | 现有 Noise XX + 双端 transcript 比对/确认 | v2，只在两端确认后发布，15 分钟会话；旧会话结果拒绝 | 不实现或冒充 Lyra 账号/证书认证；OEM trust 标签不作凭据 |
| 设备资料连接 | SharedChannelImpl.shareChannel(caller,id[,type]) 原方法同步返回 0/-10/-11/-12 | 仅 deviceprofile 调用者允许成功，实际远端资料往返+电池能力交集后绑定本机 receiver/session | 这是资料业务会话，不是通用 Continuity Channel；短重载/其他 caller 未实现 |
| 设备电池请求/应答 | DeviceProfileManagerProxy.k：`[0,mask]`；Service.c：`[1,mask,level,plugged,0…]`；Proxy.i 更新 RemoteDeviceInfo | mask 0 或 0x80；远端 Android 实时读；共享 typed battery_status → 原生 2/10 字节；通过 manifest-private SharedChannelReceiver 回注 | 未拿远端数据不回成功；真机最终状态见 runtime-validation.md |
| 取消/释放/断线 | removeCaller/onRelease/onDeviceLost；原生 profile 有 10 秒超时 | 释放/重连 generation 失效，清连接登记，撤回 found；同步 7 秒上限与 transport 5 秒期限 | 短只读请求无远端任务副作用；未实现通用远端 cancel/close ACK，不能宣称取消文件任务成功 |
| 错误 | 样本 shareChannel/send 返回值与 profile onSyncFail | 无路径/未知业务 -12；失效/远端读取失败 -11；不伪造成功包 | 通用跨业务错误码仍需逐服务还原 |
| 耳机控制/回应 | SharedChannelImpl prefix 1/4 | 未实现 | 需耳机路由与真实状态转换，不把真实手机映射成耳机 |
| 响铃查找/回应 | prefix 3/6；ringfind/sc/c,d,g | 未实现 | 开始/停止/状态、用户提示、响铃生命周期与对端执行待接 |
| 打开笔记/相机共享 | prefix 7/8 | 未实现 | 两类业务独立确认与可执行 app/相机资源缺口 |
| TV 设置/查询/授权/验证码 | prefix 9–16；20 remote_control_response；NetBus get_tv_state | 未实现 | 与手机/平板核心链不同，不能借返回值绕过 TV 认证 |
| 文件 | Mirror CrossFileShare；MiExpress 入口与 proto；既有报告第 5 节 | 未实现 | URI/文件权限、收件确认、流控、哈希、取消/续传、错误及真实对端执行都缺。MiShare 联盟路径不代替验收 |
| 剪贴板 | Mirror ClipboardMessage(type=29)，ClipDataMessage，MessageCodec 与协同服务 | 未实现/不发布能力 | 原生 Session/ClipData 的 text、HTML、URI 分流与双方授权/执行注册仍缺；不得借通用文本直写宣称全部支持 |
| 通知 | MirrorNotificationListenerService.onNotificationPosted → NotificationPostMessage；NotificationInteractionMessage | 未实现 | 通知访问授权、动作/撤回、图标/附件与双方 id 映射未接 |
| 投屏/远程输入 | cast/synergy XML + MirrorControl JNI；Screen*/Hid* 消息模型 | 未实现 | 原生视频/音频 codec、屏幕许可、输入权限、同步/反压/断线资源释放未接 |
| 应用流转 | RelayApp/RelayAppIntent/RelayBrowser/RelayOffice/RecentApps 消息模型 | 未实现 | 这些只是明确存在的消息面；具体 app 支持、任务状态、Intent/URI 授权与执行结果待核实 |
| 其他 Mirror 控制 | MessageCodec/MessageHandlerImpl 与机器清单全部显式 class_type | 未实现 | 音频焦点/媒体会话、屏幕/电源、分享键鼠/拖拽、Wi-Fi 配置、设备历史等分开建账，不默认手机/平板开放 |

## 证据与复现

- `scripts/verify-xiaomi-protocol.py` 对固定原 APK SHA256、9 个 DEX 类、27 个方法及 context 字段复核，业务 DEX 指令保存在 `dex-protocol-evidence.json`。包含实际 native API probe 所使用的 DeviceProfileManager/Profile/DeviceInfo 描述符。
- `scripts/inventory-xiaomi-protocol.py` 从本地原样 JADX 输出提取显式 getClassType，保留来源 SHA256；不从名字猜协议号，不把模型存在标为可用。
- 调试 native probe 在 com.milink.service:core 调用原厂 DeviceProfileManager.init/syncDeviceProfile，实际走原厂 Binder、shareChannel/send、SharedChannelReceiver、RemoteDeviceInfo 和 onResponse。探针成功也只证明这一 API 链，不等于 UI 卡片/文件/投屏通过。
- release APK 不包含探针实现；debug 广播由 android.permission.DUMP 限制，目标固定当前真实已配对投射，仅发电池查询。

## 下一条实质性工作

先完成真实双端电池 native API 验证及失联/迟到结果检查，再追融合设备中心消费方的完整目录模型。剪贴板必须增加厂商进程执行器注册、消息投递/完成、授权和撤销；已有一加独立 codec/endpoint 尚不能直接由模块 :bridge 调用。不能为赶进度把这些未接链路写成已适配。
