# 第三方来源与许可

本项目原创代码采用 [Apache License 2.0](LICENSE)。下列第三方组件保留各自的许可证和版权声明；外部依赖、Live2D 模型与 SDK 不因项目许可证而改变授权范围。

## 抖音 Webcast 原生签名核心

- 来源：jwwsjlm/douyinLive，固定 revision `af34f7c2ea2c4df90c6f4659e967fb31815d9255`。
- 上游：[原生核心](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/internal/webcastsign/native.go)、[协议字段映射](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/websocket_params.go)、[MIT 许可证](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/LICENSE)。
- 版权声明：Copyright (c) 2025 今晚打打。
- 使用范围：`NativeWebcastSignature.java` 中的原生签名核心移植，以及 `NativeDouyinSigner.java` 的相关协议字段映射。未使用上游 JavaScript 回退、HTTP A-Bogus、proto 或 Go 服务。
- 完整 MIT 声明保留在 [Java 核心源码](backend/src/main/java/cn/bigfish/collector/NativeWebcastSignature.java) 和 [随 JAR 打包的 SPI 资源](backend/src/main/resources/META-INF/services/cn.bigfish.collector.DouyinSigner)。分发该组件时应保留这两处声明。

该组件声明不替代整个项目的许可证，也不代表平台协议稳定或真实直播接收已经验证。采集实现与验证范围见 [抖音说明](docs/providers/douyin.md)。

## Live2D Cubism Web Framework

- 版权：Copyright(c) Live2D Inc. All rights reserved.
- 官方 SDK 固定为 **Cubism SDK for Web 5-r.4**；Framework tag commit `8df84780f2aa1298f3b30965cdae143e049f3c8e`。
- 来源：[官方 Framework](https://github.com/Live2D/CubismWebFramework/tree/5-r.4)、[官方 SDK 压缩包](https://cubism.live2d.com/sdk-web/bin/CubismSdkForWeb-5-r.4.zip)。压缩包 SHA-256：`d78904d908bd232b800219e01732e4ea2f0562b5e9f35a2670742a1c16d22942`。
- `frontend/vendor/cubism-framework/src` 的 46 个 TypeScript 文件及上游 LICENSE/README/CHANGELOG 与官方包一致；项目单独提供编译配置和渲染适配层，没有修改上游源码或复制旧 Luna bundle。
- Framework 适用 [Live2D Open Software License Agreement](https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html)，不属于本项目 Apache-2.0 授权范围。获取、使用和分发该部分须遵守 Live2D 的条款；发布应用还应按用途核对 [SDK 发布许可](https://www.live2d.com/en/sdk/license/)，不由本声明认定自动豁免。
- 保留源码版权头及 `frontend/vendor/cubism-framework/LICENSE.md`、`SOURCE.md`；前端产物包含 `licenses/cubism-framework/` 及本声明，后端 JAR 同时包含 `META-INF/LICENSE`、`META-INF/THIRD_PARTY_NOTICES.md`。

## Cubism Core 声明与外部运行库

- 版权：Copyright(c) Live2D Inc. All rights reserved.
- 仓库仅包含官方 `live2dcubismcore.d.ts` 编译期声明及其 LICENSE，位于 `frontend/vendor/cubism-core-types/`；适用 [Live2D Proprietary Software License Agreement](https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html)，不重新授权为 Apache-2.0。
- 可执行 Core 不在仓库、前端产物或 JAR 中。用户按官方条款获取并配置本机文件；当前适配固定 Core **05.01.0000**，SHA-256 `25ae938cb4fe282ce189b357bcc97e603d1e1f7ec78bf04150d401c23cdc792f`，浏览器执行前检查 SRI 和版本。变更版本需重新适配、验证，不能只替换文件绕过检查。

## 本机 BigFish 测试模型（不随项目分发）

- 作者：Mao绒绒家园；[原发布](https://www.bilibili.com/video/BV1yep465EQz)。本机模型来源记录为 `xkk6663/dsh-live2d-mao@52ef4746fa690c46e2596ea84b872b7af8a5e10c`。
- 仅沿用本机 SOURCE.md 记录的非商业测试范围；商业直播与再分发授权尚未取得。模型、贴图、表情及含模型的测试截图不纳入仓库或构建产物。
- 保留模型水印、作者署名和本机 SOURCE.md；前端禁用明确用于去除水印/署名的表情。这不代替模型作者的授权，也不把模型归入项目代码许可证。
