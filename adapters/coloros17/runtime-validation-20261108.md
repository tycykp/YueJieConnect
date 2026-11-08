# 一加原生适配实机记录 · 2026-11-08

时间为Asia/Shanghai。设备OnePlus PLQ110，PLQ110_17.0.0.104(CN01)，com.oplus.linker 17.7.30；LSPosed 2.2.1-it/7919，API102。只操作一加序列号3B161Q00YN200000；小米数据来自负责小米的对话所记录的真实验证。

## 通过的链路

1. 双方安装相同v10 APK，用户核对比对码0e6365-f524e9后分别确认；双方状态读回active及local_confirmed/remote_confirmed=true。没有自动调用确认或伪造认证。
2. 19:41:37.294，一加debug/DUMP限定探针调用原厂SynergyDeviceManager.queryOriginalDeviceList。19:41:37.581，原生回调读取batteryLevel_1280_1=75，与小米本机75一致。数据路径是DeviceKit→Oplus适配→Binder权限/租约→Rust会话路由→Noise→小米BatteryManager执行→原路响应→原生DeviceKit对象和回调。
3. 打开一加原生“与平板互联→添加新设备”，列表实际显示Xiaomi Pad 8 Pro。原生页面也触发多次真实DeviceKit查询；日志出现oplus_native_battery_result_delivered。
4. 19:43:05真实点击小米条目后，原生连接回调失败，UI显示“连接失败”，日志remote_profile_verified_native_business_unavailable。这里明确未通过连接业务验收。
5. 双方完成电池验证后，一加停止会话，仍打开的原生NewDeviceDiscoveryActivity撤回小米条目。19:46:31离线探针输出probe_peer_unavailable，未返回缓存成功。

本地证据（build为忽略目录）：

- build/oplus-validation/runtime.json：实际电池probe、本轮日志和样本SHA。
- build/oplus-validation/after-native-ui.json：active，12 requests_sent/12 responses_received，2 requests_received，无错误。
- build/oplus-validation/native-discovery.png：正常原生发现UI。
- build/oplus-validation/native-connect-failed.png：真实点击后的连接失败。
- build/oplus-validation/native-withdrawn.png：停止会话后的原生条目撤回。
- build/oplus-adapter-dex-evidence.json：14类、38方法的APK/DEX SHA和code_offset。
- android/module/build/test-results/testDebugUnitTest/TEST-io.github.qiangjian.connect.Oplus*.xml：9项JUnit通过。

小米侧对应证据：captures/gateway-runtime-20261108/hyperos4-v2/native-probe.json及后续带时间戳文件。首次19:42:55一加电量94%，19:44:35及19:45:52为100%/USB=2；较早一加截图状态栏94，随后dumpsys、cmd battery get -f level与sysfs只读均100。没有设置、模拟或reset电池。不得把不同时刻差异写成同一时刻原始硬件值一致。

## 故障与修复范围

v8两次会话在awaiting_confirmation数秒后，一加write_failed/errno103，小米peer_closed。系统日志给出直接原因：OplusHansManager冻结模块UID10476，随后netd destroyAllSocketForUid。此时服务已是foreground/connectedDevice，LAN权限已授权，单靠前台服务不足。

通过一加系统应用详情的耗电管理，将本应用从“智能优化后台运行”改为“完全允许后台行为”，并读回已选中。没有改全局Hans、其他应用、VPN或框架。随后切换到原生界面仍保持会话，覆盖本轮测试时段，不等于无限后台可靠性已经证明。

公共线程v9在Wi-Fi绑定失败时返回wifi_route_unavailable；v10保留系统允许的默认路由并记录原因，才恢复本轮会话。该变化归公共实现，不宣称本品牌代码复刻SCP LAN握手。

## 产物与最后状态

完成真实业务/UI测试的APK SHA256：`b91195ca65a274c4bee674621983d6727af034121cb5542c04f4395fc941b735`。

最后两个Java边界修复后，一加9项测试/lint/DEX校验/构建通过，最终v0.3.3/code11 APK为`ca7c8112a61c9ffe5569e320874b00444f182546ec477b1f16fcaa0ca99ae281`，19:46:51安装并验证两进程oplus_adapter_installed及caller_verified。最终小修APK未重新配对做完整跨机回归；原会话停止并已撤回设备。

完整Pantaconnect业务连接仍未实现。文件、剪贴板、投屏、通知、应用流转等没有真实互操作结果，不能以只读电池成功表示“全部适配成功”。
