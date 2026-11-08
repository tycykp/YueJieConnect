# 本地验证记录 · 2026-11-08

本文件记录安装前的本地构建阶段。后续已按用户授权完成双端安装和一轮发现观察，当前实机结论见 [双端实机记录](runtime-validation-20261108.md)。工作区 E:\强兼互联，原有三份未跟踪协议报告与 captures 保留，没有 Git 提交或推送。

## 可测产物

- APK：`android/module/build/outputs/apk/debug/module-debug.apk`，另复制至 `dist/qiangjian-connect-0.1.0-discovery-arm64-debug.apk`。
- 包名 `io.github.qiangjian.connect`，版本 `0.1.0-discovery` / 1，min/target SDK 37，ARM64，debug 签名。
- 大小 **570710 字节**。
- APK SHA-256：`3d96d8a5ff822de1850d0d03c0fd3f0fc89d37029493561790c8ff4eec6d5a3b`。
- APK 内 Rust SO SHA-256：`18655ae60b30677cabfeef7c6e7e4c9b6b680549a866bbdb22e927749969def8`。

## 已通过

| 验证 | 结果与范围 |
| --- | --- |
| 官方 API | 102.0.0 AAR/tag/commit/摘要核对；详见 api102.md |
| 原始 DEX | 两包 APK/DEX 哈希、4 个类、5 个发现回调及 getter 描述符与冻结基线一致 |
| Rust 单元测试 | `cargo test --locked --workspace`：core 4 项、transport 2 项全部通过 |
| Rust 质量检查 | `cargo fmt --all -- --check` 与 clippy workspace/all-targets `-D warnings` 通过 |
| JNI 主机集成 | 真实 Windows cdylib → JVM：同意开关、Unicode、观察/目录、重放、越权拒绝、撤销与 unsupported 通过 |
| ARM64 Rust | NDK 29 交叉编译 release 通过，真实 JNI 导出存在，动态依赖仅 libc/libdl |
| Android | assembleDebug、testDebugUnitTest（2 项）、lintDebug 成功；最后一次使用 offline 与正常依赖摘要校验，未重新生成 metadata |
| APK 装载元数据 | java_init.list 入口、min/targetApiVersion=102、固定 scope、禁用热重载；只有 ARM64 JNI 库 |
| APK 签名/结构 | apksigner v2 验证通过，ZIP CRC 通过；aapt 显示 min/target API 37 和 arm64-v8a |
| 16KB 对齐 | zipalign `-c -P 16 4` 通过；4 个 ELF LOAD 的 align 均为 16384；未压缩 SO 的 ZIP 偏移 49152 |
| APK 类定义 | 33 个类，包含 DiscoveryModule；不内置 libxposed/旧 Xposed/Android 框架类 |
| 工作区 | `git diff --check` 通过；未自动提交、推送 |

Rust 测试覆盖会话调用方/序号、撤销/过期/重启、容量前的输入限制、未知字段/服务拒绝、来源隔离、能力不推断为配对，以及 SCP 不支持字段/截断/多余字节。测试输入都是合成数据，不是真实设备会话。Windows MSVC 链接时输出的“正在创建库”被 rustc 标为 linker stdout 提示，不是测试失败。

## 保留的 3 条 lint 警告

- `DiscouragedPrivateApi`：仅在目标进程 Hook Application.attach；ROM 限制/生命周期需要实机确认，缺失签名时拒绝启用。
- `ChromeOsAbiSupport`：本版明确仅 ARM64 Android 17，不提供 ChromeOS/x86。
- `ExportedService`：厂商签名不同，不能使用模块 signature 权限绑定。服务每个 transaction 都做 UID/PID/包/版本/当前证书校验，Rust 再校验 token/期限/序号/命令授权。外部应用可请求绑定，但无法因此访问业务。此边界还需实机负向测试。

没有隐藏这些警告或将 lint 通过写成实机安全验收。

## 本地构建阶段的未验证项（后续状态见实机记录）

API 102 在两份 ROM 的实际装载、5 个回调的真实命中、跨包 Binder、Android ARM64 JNI 执行和 UI 行为均未实测。当前实现只桥接只读本机设备目录，没有原生跨品牌设备投射、配对、认证、会话或文件传输。SCP codec 未接入网络；Lyra/NDSF/完整 OAF 业务仍是阻碍。

下一步按 [实机验证步骤](device-validation.md)安排安装与发现观察；在此之前不能宣称原生互联成功。
