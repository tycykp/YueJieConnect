# 兼容性记录

2026-11-08 晚间更新：v0.3.2/code10 已在两个目标设备安装并装载独立品牌适配器；用户真实确认后，双向原生 SDK 电池查询、实际远端执行、转换/原生回调及失联拒绝已通过。一加 UI 能显示小米但点击通用连接仍失败；小米融合中心 UI 未验收。最终源码 v0.3.3/code11 构建测试通过，和实测 code10 分别记录。下表为早期观察版记录，不代表最新代码范围；仍没有完成全部原生业务验收的组合。见 [小米本轮实测](../adapters/hyperos4/runtime-validation.md)。

截至 2026-11-08（Asia/Shanghai），双端安装、API 102 装载、Hook 安装、调用方校验及 ARM64 JNI 本机查询已通过。发现观察目录为空，回调到 Rust 的实际通路未证实；没有通过原生互操作验收的跨品牌组合。详见 [实机记录](runtime-validation-20261108.md)。

| 来源 | 样本设备/构建 | 固定包版本 | 适配入口 | 实机发现 | 原生配对/文件 |
| --- | --- | --- | --- | --- | --- |
| HyperOS 4 | Xiaomi Pad 8 Pro / 25091RP04C；OS4.0.5.0.XPYCNXM；Android 17 / 37 | com.milink.service 18.2.3.0.2610211842 / 180020300 | SharedChannelImpl 3 个回调 | 未验证 | 未实现 |
| ColorOS 17 | OnePlus PLQ110 / 一加 Ace 6；PLQ110_17.0.0.104(CN01)；Android 17 / 37 | com.oplus.linker 17.7.30 / 170070030 | 全局发现回调 2 个方法 | 未验证 | 未实现 |

协议还涉及 com.xiaomi.mi_connect_service、com.xiaomi.mirror、com.heytap.accessory 17.6.16，未加入本版 Hook 作用域。其他包版本直接拒绝观察。

实机框架：小米 LSPosed 2.2.1 / 7940，一加 LSPosed 2.2.1-it / 7919，实际 API 均为 102。记录绑定 APK 摘要、框架/API、系统构建与步骤。目录出现记录、离线 codec 或自建数据通道成功都不能替代原生互操作验收。文件验收仍需双向测试文件 SHA-256、确认/拒绝/取消/撤销验证。
