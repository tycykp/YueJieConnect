# Rust 协议工具

qj-transport 实现 SCP V4、SYSTEM_SERVICE、PROTOBUF、无扩展/CRC/加密的普通控制帧编解码：12 字节主头、大端长度、严格一帧边界。agent_address 取 namespace+channel 的 SHA-256 末四字节。

证据为 ColorOS E25/E27、专项 C09/C18。测试是合成向量，不是抓包或互操作证明。尚无 TCP 流缓冲、握手、认证或网络服务，也未接入 APK。其他头、加密、CRC、扩展与业务帧明确拒绝。
