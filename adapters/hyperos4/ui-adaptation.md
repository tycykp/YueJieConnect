# 原生设备中心接入（继续适配，2026-11-08）

本轮代码：XiaomiCirculateAdapter（新增 :ui 品牌入口）。公共 wire 保持 v2；一加已安装 code11 可作为原有真实电池执行端，无需为本轮 UI 变更重装一加。以下均以原始 com.milink.service APK 核实，尚待本轮实机结果更新。

## 查明的调用链

CirculateWorldActivity(:ui) → MainFragment → CirculateClientProxy → MiuiPlusClient.startDiscovery → sc.a.e → CirculateDeviceManager.o.q → world.service 回调 → 原生设备卡片。

点击 AndroidPhone 卡片进入 PhoneCard/BleView，原厂 getAndUpdateBattery → gg.a → DeviceProfileManager → :core SharedChannelImpl。上一轮已实测的真实 battery_status 路由继续执行，未另造电池视图或本机模拟值。

原 DeviceControlProvider 主要承担组合目录和置顶等数据，并不是本次手机卡片最直接的发现入口。本轮不写系统数据库，不更改账户/同账号标记。

## 已写入的行为

- 仅在 :ui 进程安装新的 Circulate hooks；:core 保持上一轮独立 XiaomiProtocolAdapter；其他进程不扩作用域。
- 原生 startDiscovery 成功返回后登记实例，真实认证 projection 到达后调用该实例自己的 sc.a.e，按真实 name/model/os_build 构造 AndroidPhone 形态。stop/release/session 变化用 sc.a.f 撤回。
- 只发布当前 session_id 有效且 battery_status 在实际能力交集中的手机。协议196608用于该样本的本地设备分组，connectState 始终0，未声明 Mirror/应用通道已连接。
- PhoneCard 保留原厂资料与电池逻辑；尚未实现的镜像、相机、响铃、蜂窝、远控控件仅对模块登记代理隐藏/阻断展示。其他原生设备原样 proceed。
- 对模块登记代理的 circulateService 请求返回原厂不支持错误路径；混合目标列表保留其他真实原生设备，不将 queryRealPeer 当通用连接成功。
- 不改动一加专属代码；公共接口、Noise版本和授权范围本轮不变。

## DEX 证据

scripts/verify-xiaomi-ui.py 已通过：9类、39方法、9字段，记录于 dex-ui-evidence.json。包括真实字段 id（不是 JADX 的 f17887id）、mCallback、ExtraBundle$b 方法和原生卡片策略方法。

## 当前验证状态

Java编译已通过，完整APK/单测/lint与真机UI验证继续进行。不能仅因代码发布回调就标为用户已看到设备卡片或业务成功。
