# 第三方来源与许可

## 抖音 Webcast 原生签名核心

- 来源：jwwsjlm/douyinLive，固定 revision `af34f7c2ea2c4df90c6f4659e967fb31815d9255`。
- 上游：[原生核心](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/internal/webcastsign/native.go)、[协议字段映射](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/websocket_params.go)、[MIT 许可证](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/LICENSE)。
- 版权声明：Copyright (c) 2025 今晚打打。
- 使用范围：`NativeWebcastSignature.java` 中的原生签名核心移植，以及 `NativeDouyinSigner.java` 的相关协议字段映射。未使用上游 JavaScript 回退、HTTP A-Bogus、proto 或 Go 服务。
- 完整 MIT 声明保留在 [Java 核心源码](backend/src/main/java/cn/bigfish/collector/NativeWebcastSignature.java) 和 [随 JAR 打包的 SPI 资源](backend/src/main/resources/META-INF/services/cn.bigfish.collector.DouyinSigner)。分发该组件时应保留这两处声明。

该组件声明不替代整个项目的许可证，也不代表平台协议稳定或真实直播接收已经验证。采集实现与验证范围见 [抖音说明](docs/providers/douyin.md)。
