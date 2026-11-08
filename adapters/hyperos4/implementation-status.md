# 小米实现进度 · 2026-11-08

公共 v2 已实现：session_id、异步/同步 request、device_profile/battery_status 实际远端执行、typed response。ProjectionHooks 已切换到独立 XiaomiProtocolAdapter / OplusProtocolAdapter，旧品牌 hook 已移除。

首项小米业务：SharedChannelImpl 的 caller=deviceprofile 专属建链/发送，远端 Android 电池读取，转换为 10 字节原生应答，交给 manifest-private SharedChannelReceiver，继续原生缓存/IDeviceProfileListener 链。未知 caller/mask 保持拒绝。未声明泛用 Continuity Channel、UI 完整发现或投屏/文件成功。

本轮首次 assembleDebug + testDebugUnitTest + lintDebug 通过；原始 APK SHA256 固定，5 类/18 方法/context 字段 DEX 校验通过。新增原生 API probe 后需要重构建。

联调约定：小米线程只操作 b59f8656；不操作一加 3B161Q00YN200000。一加线程可在最终 APK 稳定后安装其负责设备，v2 两端均需更新。正在添加 debug-only、DUMP 权限限制的小米原生 DeviceProfileManager probe；广播 action `io.github.qiangjian.connect.XIAOMI_PROFILE_PROBE`，目标包 com.milink.service，日志 QiangJianXiaomiTest。probe 仅对当前已配对真实投射发起电池查询，不能构造虚假远端或绕过双端确认。

当前未取得真机电池闭环；后续结果补记。

19:30 前小米已安装 v0.3.0-adapters / code 8，重启 com.milink.service:core，debug probe_registered、BridgeService initialization ok，真实 operations=[device_profile,battery_status]。小米地址 192.168.1.97:38271 已启动两分钟监听，供一加线程连接联调；未代替用户进行双端确认。可以通过本文件记录双方已观察的会话/确认阶段；不会操作另一端设备。

19:32 当前：上次监听收到连接后 peer_closed，已重新 listen 192.168.1.97:38271，保持小米作为监听端，不反向连接。一加请在此窗口发起。已读一加安装 e1ce638c...，双方安装产物一致。Maven junit-bom-5.11.0-M2.module 缺 hash 已由本线程对 Maven Central 文件与其公开 sha256 双核对后定点补入，后续构建未关闭校验。

联调发现：小米日志 19:31:13 awaiting_confirmation → 19:31:19 peer_closed；19:33:14 awaiting_confirmation → 19:33:18 peer_closed。尚未确认/超时前就由远端关闭，非本线程主动 stop；请一加检查其 last_error/服务生命周期。不要反复连接掩盖原因。下一步先读一加诊断，再恢复单次会话。

已读一加 write_failed/103 与 route_binding_failed。公共修复已落地 `WifiRoute`：requestNetwork 持有 Wi-Fi 网络并等待 onAvailable，绑定失败直接 wifi_route_unavailable，不再沿未知默认路由建立后很快断开的会话。此修复针对已观察到的路由缺口，尚不能断言是 errno103 的唯一根因。正在构建 versionCode 9 / 0.3.1-adapters，完成后本线程安装小米；一加可安装同一产物后只做一次新网络会话验证。

已读 19:36 一加冻结/netd 销毁 socket 的直接证据；这比网络绑定错误更直接。先保持双方当前 code8 / e1ce638c... 做一次一加 MainActivity 前台诊断，小米已再次启动监听（约19:37）。code9 构建中暂不重装以免打断本次会话；WifiRoute 的效果独立验证，不能宣称解决系统冻结。

已读一加 v9 的 wifi_route_unavailable。由已观测到 v8 默认路由可以完成握手、真正断开来自 Hans freeze，严格拒绝默认路由不成立。现 WifiRoute 在显式 Wi-Fi bind 被 OEM/VPN 拒绝时，保留系统默认路由（须仍可见 Wi-Fi + activeNetwork），记录具体原因；不改系统/VPN/全局冻结设置。请等本轮 v10 产物稳定再一次前台验证，暂不重复 v9 连接。

**19:39:32 可联调**：v10/0.3.2-adapters 最终 APK SHA256 `b91195ca65a274c4bee674621983d6727af034121cb5542c04f4395fc941b735`，assemble + unit + lint 成功。已安装小米、重启原生 :core，192.168.1.97:38271 已监听两分钟。代码现稳定，不继续修改影响产物。一加安装同 SHA 后前台保活发起单次连接；本线程捕获并记录小米比对码，等待真实确认。

**双方会话一致**：小米实读 session=16c41a65e0ec1234ce8be77747c33ce607f804d2ce91a89d14eea514e0795b98，比对码 0e6365-f524e9，与一加记录一致；双方 confirmed=false，phase=awaiting_confirmation。本线程不发送 bridge_confirm、不覆盖会话；小米模块确认页已准备打开。一加线程负责向用户提出一次双端确认请求，避免重复提问。用户完成后本线程只触发小米原生探针。

**19:42:55 小米原生只读业务闭环成功**：用户在一加对话确认两端分别确认，本线程随后读回 active / local_confirmed=true / remote_confirmed=true。`scripts/validate-xiaomi-runtime.py --probe` 只调用原厂 DeviceProfileManager，真实 onResponse 返回一加电量 level=94 / plugged=2。原始小米证据位于 captures/gateway-runtime-20261108/hyperos4-v2/native-probe.json。小米本机 dumpsys battery 同时为 level=75 / USB powered=true，与一加原生 SDK 19:41:37 收到75一致。请一加线程补核对一加本机94/USB基线；本线程不操作该设备。

这证明双方特定原生 SDK/API 的电池查询、真实远端执行、字段转换和原生回调，不证明 UI 自动发现或通用原生连接/其他业务成功。当前先保留会话供对方查 UI；本线程不主动 stop，后续失联/撤销测试协调后再做。

已读一加电池基线100的核对需求：小米第二次原生查询已于 **19:44:35.029 返回 level=100 / plugged=2**，首次19:42:55是94，说明结果动态变化而非静态档案。没有设置/模拟电池。现又按对端请求做一次原生查询并保存独立时间戳证据；不能把不同时刻94/100的差异直接判为转换错误。新的100/USB与一加随后实读基线一致。

**可断开**：第三次实际原生回调 19:45:52.853 = level100 / plugged2，见 captures/gateway-runtime-20261108/hyperos4-v2/native-probe-20261108-194553.json。小米本轮业务核对完成，允许一加线程执行其计划的 bridge_stop；本线程等实际 disconnected 后才发离线 probe，验证不会伪造结果。

**19:46:57 离线检查通过**：一加 stop 后，小米 phase=disconnected / peer_closed / projection=null；6 次发出请求收到6次对应响应。此状态再发一次 native probe，结果仅 `peer_offline`，没有 onResponse；证据 disconnect.json。

最终源码已构建 versionCode11 / 0.3.3-adapters，APK SHA256 `ca7c8112a61c9ffe5569e320874b00444f182546ec477b1f16fcaa0ca99ae281`；包含一加两项最后小修，assemble/unit/lint通过。已通过原生往返的精确 v10 b911产物另从小米已安装base.apk读回保存在 build/artifacts/module-v10-native-battery-validated.apk，sha一致。不能把最终v11包混写成已做此次配对/业务测试的v10包。

最终收尾：小米 bridge_stop 返回 ok，释放网络持有/前台服务；保留已验证 code10 安装。最终完整边界集中在 runtime-validation.md / capability-matrix.md，未 reset/提交/推送仓库，未修改一加专属实现。
