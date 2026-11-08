# 纯文本剪贴板适配续轮

## 样本实际协议

`ClipBoardDataBusScene.initClipBoardScene(dvd,statusListener)` 创建 `ClipBoardConsumerImpl`，先调用 `setStatusListener(dvd,listener)`，再 `getInstance(Context)`。带明确已登记dvd的实例可绑定到本模块真实会话；没有dvd的广播实例不属于本次接管范围。

`copyToClip()` 从原生剪贴板取数据，通过OAF逻辑通道104调用 `ClipBoardService/copyToClip`。`copyToClipOld(byte[])` 对profileVersion=1.0的peer发送feature=0。接收 `ClipBoardServiceManager` 在feature=0累积UTF-8文本；feature=1是本地Android ClipData Parcel及UriRawInfo，不能作为文本或跨品牌原始Parcel发送。

发送端原厂响应观察者是原始DEX `ClipBoardConsumerImpl$3`，实现`Ld83;`，不是运行时`defpackage.d83`。真实成功响应是 `Clipboard.Result.status=0` 后onCompleted；失败走onError。OplusClipboardNativeResult仅在Transport实际完成且session/request/字节数核对通过后回注该结果，不用查询档案替代写入结果。

## 已实现的品牌边界

- OplusClipboardAdapter：原生consumer实例按alias+session登记，初始化只接受真实可用执行能力；单次发送有界、超时和断线失效；其他设备保持原调用。
- OplusClipboardReceiver：在Android主线程调用OEM `ClipBoardServiceHelper.copyPureTextToClipBoard`，实际读回相同文本才产生成功结果。
- OplusClipboardTasks：相同请求不重复写入；不同payload不能复用request；过期/执行前取消/撤销不能写入。进入RUNNING后取消不伪称远端回滚。每会话最多256条记录，满后拒绝新请求而不驱逐去重记录。
- 单次最多4096 UTF-8字节，拒绝非法UTF-8、孤立代理项、HTML/URI/Intent。建议公共传输使用Base64以避免JSON控制字符转义突破8192字节Binder限制。

只有共享层完成入站身份校验、能力/授权交集、执行进程租约和真实请求回传，才能接入主适配器并发布clipboard能力。当前Transport是品牌内部接口，不冒称公共方法已存在。进程死亡后不能把旧任务向新执行器自动重试为“保证恰好一次”；公共层需要将不确定执行结果明确失败或废弃会话。

## 实机证据与边界

2026-11-08 20:00:09（Asia/Shanghai），一加原生:app进程收到DUMP保护的OPLUS_CLIPBOARD_PROBE。日志为：

```text
clipboard_self_test native_write_readback_ok restored
```

实际调用OEM写入及读取接口，测试包含中文与补充平面字符；既有剪贴板只留在设备进程内，并通过OEM完整ClipData接口恢复。没有日志输出其内容。测试APK SHA256 `f303008919b5dbaf7d630e07b945f320d12e439ecf8ddcd7defa4cdcc65d05ef`。

随后增加保守限制：本机恢复型自测必须有一加原生Activity真实持有窗口焦点，避免Android把后台拒绝读取表示成“空剪贴板”后错误清空原内容。公开业务接收器仍由真实授权任务调度，不依赖此调试自测。

目前新增16项Oplus测试通过，20个原始DEX类58个方法描述符校验通过。**本机sink测试不是跨设备业务验收**，未确认公共执行接口前，不安装新的clipboard发送/入站适配器、不发布能力。复验脚本为scripts/validate-oplus-clipboard.py --local-sink，仅操作一加。
