# 实机验证步骤

用户后续已授权双端安装并验证加载与发现，且要求不反复测试。本轮已执行安装、加载与一轮发现观察，结果及收尾状态见 [实机记录](runtime-validation-20261108.md)。以下保留验证步骤作为后续参考，不能把未执行的负向测试记为通过。

1. 核对 APK SHA-256、两端目标包版本和框架确切版本/API。需要 API 102，不能用旧 API 框架加载。
2. 安装 ARM64 debug APK。LSPosed 仅勾选本机对应的 com.milink.service 或 com.oplus.linker，不扩展到 Android/system_server、MiShare/OShare、accessory。
3. 按框架要求重启目标进程。检查 module_loaded api=102、discovery_hooks_installed source=1/2、caller_verified。这些只是装载/挂钩/身份核验，不代表互联成功。
4. 打开模块，点击“同意并开启本机采集”，再由用户在系统原生互联界面触发发现。刷新模块目录，检查来源、名称、临时标识、能力原值和 observed。异品牌本身可能仍互不可见，不能伪造条目。
5. 验证更新/丢失或 120 秒观察失效、停止清除与旧 token 拒绝。服务重启必须默认关闭；新版本/不同签名必须拒绝采集。
6. “检查原生文件传输支持”应返回 unsupported/native_protocol_not_integrated。本版不发起真实配对、收件或传输。
7. 检查厂商原本发现/取消流程未受影响；未同意采集时无记录。外部测试 APK 的 Binder 请求应 caller_rejected，目标包读取 catalog 应 forbidden。

只收集模块标签与测试步骤，目录设备名称分享前脱敏，不采集账户密钥、用户内容或全量 logcat。无装载日志查 API/作用域；unsupported_hook_signature 查样本；unsupported_package_identity 查版本、证书、UID；已装载无目录查实际回调、绑定限制和开关。

后续原生配对/文件须明确选定链路，保留用户确认和拒绝/超时/撤销；双向测试文件 SHA-256 一致才算传输验收通过。本次未执行原生配对和文件传输。
