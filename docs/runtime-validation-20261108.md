# 双端实机验证 · 2026-11-08

后续更新：本页保留18点的观察版记录。19点的适配器 v0.3.2 已通过用户双端确认、双向原生 SDK 电池请求/真实执行/原生回调和失联拒绝；详细时间、APK SHA256、原始证据与未完成的 UI/通用连接范围见 [小米适配器实机记录](../adapters/hyperos4/runtime-validation.md)。不能用本页早期“尚无网络”的状态描述最新实现，也不能把后来单项业务成功扩大为全部互联完成。

本轮按用户授权安装现有 APK，验证 API 102 装载并进行一轮原生发现观察。没有改代码、重编译、重跑测试套件或循环扫描。

## 结果

| 项目 | 小米 Pad 8 Pro | 一加 Ace 6 |
| --- | --- | --- |
| 安装 | 成功，18:12:16 | 成功，18:11:33 |
| 当前框架 | LSPosed 2.2.1 / 7940 | LSPosed 2.2.1-it / 7919 |
| 框架实际 API | 102，lspctl status 与模块日志一致 | 102，lspctl status 与模块日志一致 |
| 模块生命周期 | module_loaded、discovery_hooks_installed、caller_verified 均出现 | 同左 |
| 关键进程 | com.milink.service:core（另有主进程和 :ui 等） | com.oplus.linker、:app、:devicediscovery |
| 厂商进程绑定桥接服务 | dumpsys 可见 :core 等的 Client AppBindRecord | dumpsys 可见 :app / :devicediscovery 的 Client AppBindRecord |
| ARM64 JNI/本机控制 | catalog 返回 status=ok，enabled=true | 同左 |
| 原生发现观察后的目录 | devices=[]，sessions=0 | devices=[]，sessions=0 |
| 发现回调 → Rust | **未证实成功，不能标记通过** | **未证实成功，不能标记通过** |

时间为 Asia/Shanghai。安装的是同一个 `0.1.0-discovery` / versionCode 1 ARM64 APK，SHA-256：

`3d96d8a5ff822de1850d0d03c0fd3f0fc89d37029493561790c8ff4eec6d5a3b`

小米第一次 USB 安装因锁屏下确认未完成而返回 INSTALL_FAILED_USER_RESTRICTED；用户解锁后重试安装成功。一加在系统安装界面确认后成功。没有关闭系统安装保护。

## 装载的直接证据

小米 `com.milink.service:core` / PID 31309：

```text
18:15:11.668 module_loaded api=102 process=com.milink.service:core
18:15:11.686 discovery_hooks_installed source=1
18:15:11.706 caller_verified
```

一加 `com.oplus.linker:devicediscovery` / PID 17940：

```text
18:16:31.771 module_loaded api=102 process=com.oplus.linker:devicediscovery
18:16:31.829 discovery_hooks_installed source=2
18:16:31.845 caller_verified
```

一加主进程早在 18:12:35 就出现同一组三条日志。小米共观察到 7 个目标包相关进程装载，一加 3 个。Hook 类和方法可解析、API 102 调用被框架接受、目标包身份核验通过，但 **安装 Hook 不等于执行过发现回调**。

## 发现观察与限度

- 小米打开原生融合设备中心及设备互联设置；设备互联原本已开启，未反复切换开关。目标 UID 日志中存在发现启动和监听注册活动，但未取得 `SharedChannelImpl` 的 NetBus found/changed/lost 命中证据。
- 一加从“与平板互联”进入“添加新设备”，实际显示“附近的设备”，原生页面注明支持 OPPO/一加/realme 平板。本轮列表没有可用新对端，未选择连接或配对。
- 最后两端模块目录均为空，IPC 会话数为 0。未捕获到该版模块将实际发现事件送达 Rust 的成功结果。
- 一加 release 日志未出现选定的 `ptc startSearch` 等标记，**日志缺失不能证明对应方法从未执行**。当前 Hook 的观察异常会被隔离，也没有独立命中计数，因此本轮无法区分“没有合适对端”“当前 UI 采用另一发现入口”“回调后字段提取/投递失败”。
- 不以历史离线设备、米家设备列表、Hook 安装日志或本机 JNI 返回成功冒充原生发现兼容。

遵照“不反复测试”，观察结束后没有再启动扫描、注入合成回调或更换协议重试。下一步应先补齐无设备内容的命中/拒绝诊断，或准备能正常被原厂发现的对照设备，再做有明确目的的单次验证；本轮没有开展这些额外操作。

## 设备收尾状态与证据

APK 保留安装，模块作用域保留。未重启整机或强停厂商互联进程，未改变框架全局配置、同账号标志、配对记录或文件传输状态。

一加通过“停止并清除”收到 enabled=false/status=ok；小米结束本模块进程并确认 :bridge 已退出，以清除临时采集状态。下次打开模块默认停止，需重新开启本机采集。

原始材料在 Git 忽略目录 `captures/runtime-20261108/`：两端 `*-module.log`、脱敏发现标记 `*-discovery-markers.txt`、目录截图 `*-catalog.png` 与一加停止截图。仅筛选本模块日志及目标 UID 的发现标记，不保留完整用户业务日志。源码和 APK 未在本轮变化，未提交或推送 Git。
