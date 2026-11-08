# 小米适配器实机验证 · 2026-11-08

时间为 Asia/Shanghai。小米线程只操作 b59f8656 / Xiaomi Pad 8 Pro (25091RP04C)，对端操作由一加线程完成。没有替换系统框架、重置仓库或提交 Git。

## 已通过的真实业务

安装实测 APK：`0.3.2-adapters` / versionCode 10，SHA256 `b91195ca65a274c4bee674621983d6727af034121cb5542c04f4395fc941b735`。

用户在一加对话明确反馈“两端码一致，已分别确认”，随后本线程读回 active、local_confirmed=true、remote_confirmed=true。没有由探针伪造账号、认证成功、对端资料或业务结果。

19:42:55 的原生调用链如下：

1. DUMP 权限保护的 debug-only probe 在 `com.milink.service:core` 调用原厂 `DeviceProfileManager.init/syncDeviceProfile`，仅选择当前真实已配对代理。
2. 原厂 `DeviceProfileManagerProxy` 经原本 Binder/异步代码调用 `SharedChannelImpl.shareChannel("deviceprofile",id)`。
3. XiaomiProtocolAdapter 在当前会话内核验真实对端资料和电池能力，19:42:55.577 记录 `deviceprofile_channel_ready`。没有构造假的 Lyra/Continuity Channel。
4. 原厂发送 `[0,0x80]`；中间层发送 session-bound battery_status。真实一加 BridgeService 经 JNI 附加线程读取 Android BatteryManager 和电池广播。
5. 小米将应答转换为 `[1,0x80,94,2,0,0,0,0,0,0]`，19:42:55.685 记录 `deviceprofile_real_response_delivered`，发送给原厂 manifest-private SharedChannelReceiver。
6. 原厂解析/缓存 RemoteDeviceInfo 并沿原 listener 回调，19:42:55.690 记录 **`native_onResponse level=94 plugged=2`**。plugged=2 是 USB 电源类型，未冒充通用 charging 布尔状态。
7. probe 释放自身原生 listener/service；19:42:55.696 对该 SharedChannel 实例撤回投射。桥接会话仍可用于下一次真实查询。

反向一加线程实测：19:41:37 原生 SynergyDeviceManager → DeviceKit → 公共 battery_status → 真实小米执行端 → 原生属性回调返回 level=75。本线程同次 `dumpsys battery` 实读小米 level=75、USB powered=true。一加侧详细证据由其维护，本报告不把其 SDK 结果扩大为通用连接成功。

动态复核：19:44:35.029 和 19:45:52.853，小米原生 onResponse 均返回一加 level=100 / plugged=2，与一加线程随后从 dumpsys/cmd battery/sysfs 实读的100/USB一致。首次94与后续100不是同一时刻基线，不把它们写成同时一致；数据确实每次查询远端，未使用档案缓存或设置/模拟电量。

原始小米材料（Git 忽略）：`captures/gateway-runtime-20261108/hyperos4-v2/native-probe.json`、`module-markers.txt`、`status.json`。可复跑入口 `scripts/validate-xiaomi-runtime.py --probe`；未确认或离线会明确拒绝，不自动确认/重连。

19:46:57 失联检查：一加实际 bridge_stop 后，小米实读 disconnected/peer_closed/projection=null，6 次发出 RPC 对应6次响应；随后再次发送受保护探针，仅 `peer_offline`，未产生旧 onResponse。原始证据为同目录 `disconnect.json`。未持久化确认，不自动重连。

收尾：小米再执行自身 bridge_stop 返回 ok，释放本模块 Wi-Fi request/进程绑定和前台服务；安装的已验证 code10 保留，未自动重建新会话。

最终源码产物为 `0.3.3-adapters` / code11，SHA256 `ca7c8112a61c9ffe5569e320874b00444f182546ec477b1f16fcaa0ca99ae281`，包含一加发现停止失败/混合查询 busy 两项末尾修复，assemble/JUnit/lint 通过。上述双端业务实测严格对应 code10/b911，不混写为 code11 实测。已从小米安装文件读回并验 SHA 的 code10 保存在 `build/artifacts/module-v10-native-battery-validated.apk`；最终 code11 在正常 APK 输出目录。

## 编译和证据检查

- Rust workspace：13 项测试通过，包括双端确认、实际动态远端 battery reader、能力不对称、同 alias 重连 session 失效、未知 alias 拒绝；clippy `-D warnings`、fmt check 通过。
- Android：13 项 JUnit 通过（小米 codec 2、公共 caller 2、一加 9）；assembleDebug、lintDebug 通过。lint 为 0 errors / 4 warnings（固定样本所需的非 SDK attach、ARM64 限定和受程序校验的 exported Binder 等）。
- 真实 JVM JNI smoke 通过；APK ARM64 和 ELF/ZIP 16 KiB 对齐检查通过，libxposed API=102。
- 小米固定原 APK SHA256 复核通过，9 个 DEX 类/27 个方法及 context 字段通过。原始指令见 `dex-protocol-evidence.json`；55 个显式 Mirror 消息 type 已登记，但全部未发布为已实现能力。
- 构建曾因 junit-bom-5.11.0-M2.module 缺 SHA256 停止。已将缓存文件与 Maven Central 原文件及发布的 `.sha256` 一致性核实后仅补这一制品；未关闭依赖校验。

## 运行限制与未完成项

早期两次会话能握手，但一加系统 Hans 冻结模块 UID、netd 销毁该 UID socket，导致一加 write_failed/103、小米 peer_closed。对端线程仅对本模块启用系统提供的“完全允许后台行为”，未改全局冻结/框架。Wi-Fi 显式绑定仍可能被 OEM/VPN 拒绝；v10 在仍有 Wi-Fi 与 activeNetwork 时遵从系统默认路由并记录原因。本次会话成功不等于长期后台稳定性已验收。

**已经证明的是双方特定原生 API 的电池业务互通。** 小米融合设备中心 UI 自然发现/设备卡片尚未证明；其 DeviceControlProvider 在 :ui 进程，与 SharedChannel 的 :core 缓存不同。一加线程已看到真实小米设备卡片，但点击连接仍 `native_business_unavailable`，未伪造 Pantaconnect onConnectionSuccess。

通用 logical open/close/cancel、文件、剪贴板、通知、投屏、应用流转、完整 Lyra/SCP wire 互操作都未完成。详细逐项缺口见 [能力账本](capability-matrix.md)，不能宣称“全部适配成功”。
