# 本机 Live2D 形象

默认 SVG 不需要外部资源。当前可切换到固定 BigFish 模型，支持实际播放音量驱动嘴型、眨眼、轻微待机、模型物理效果、手动表情和布局设置。没有模型上传、目录扫描、多模型选择、AI 情绪或第二个 OBS 播放端。

## 资源目录

将已有授权的模型包与指定 Core 放在一个独立、已有且可读的绝对目录，例如：

```text
/absolute/path/avatar-assets/
  sdk/
    live2dcubismcore.min.js
    LICENSE.md
  models/
    BigFish/
      BigFish.model3.json
      大肥鱼.moc3
      大肥鱼.physics3.json
      大肥鱼.cdi3.json
      大肥鱼.8192/texture_00.png
      EXP/...
      SOURCE.md
```

保留 model3.json 引用的相对文件名和目录结构。根目录必须与 `BIGFISH_DATA_DIR` 分离，不能是该数据目录或其子目录；默认数据目录是项目的 `runtime/`，因此不要把资源放在 `runtime/avatar-assets`。也不能指定文件系统根、用户主目录或当前工作目录本身。

HTTP 只公开受限的模型文件和确切的 Core 文件；不公开 SOURCE、目录列表、任意 JSON、音频、HTML、其他 JS 或运行数据。模型文件更新后可在页面“重新加载形象”，修改资源根目录则需重启 Java。

## 启动与选择

开发启动，在仓库根目录执行：

```bash
BIGFISH_AVATAR_ASSETS_DIR="/absolute/path/avatar-assets" ./scripts/dev-backend.sh
```

另一个终端运行 `./scripts/dev-frontend.sh`。Vite 的 `/avatar-assets` 与 API 使用同一个 `BIGFISH_API_TARGET`，缺省 `http://127.0.0.1:18080`；各工作树继续使用自己的隔离配置。

IDEA：在 BigfishApplication 的运行配置“环境变量”中添加 `BIGFISH_AVATAR_ASSETS_DIR`，值为真实绝对路径，然后重启。IDEA 不会自动读取 shell 启动脚本。

JAR：

```bash
BIGFISH_AVATAR_ASSETS_DIR="/absolute/path/avatar-assets" java -jar bigfish-server-0.1.0-SNAPSHOT.jar
```

在舞台展开“舞台形象设置”，选择“Live2D · 本机 BigFish”。第一次载入贴图可能需要等待；缺资源或不兼容时会提示并显示 SVG。先用模拟采集、模拟回复、本地测试音发送模拟评论或礼物，核对实际播放和完成回执。形象偏好不会启动互动，也不改变服务选择或播放租约。

大小、位置、背景保存于同源 localStorage 的 `bigfish.browser.avatar.v1`，不跨浏览器/端口同步。手动表情不持久化。透明选项只作用于形象区域，整个页面尚不是 OBS 专用透明输出。

## SDK 固定版本

- 官方 [Cubism SDK for Web 5-r.4](https://cubism.live2d.com/sdk-web/bin/CubismSdkForWeb-5-r.4.zip)。获取和使用前阅读[官方条款](https://www.live2d.com/en/sdk/download/web/)。
- Framework 固定 tag commit `8df84780f2aa1298f3b30965cdae143e049f3c8e`；源码在 `frontend/vendor/cubism-framework`，通过独立 TypeScript 编译接入。
- Core 为该官方包 `Core/live2dcubismcore.min.js`，版本 **05.01.0000**；SHA-256：`25ae938cb4fe282ce189b357bcc97e603d1e1f7ec78bf04150d401c23cdc792f`。复制到上述外部 sdk 目录，保留其许可文件。浏览器使用固定 SRI 并核对版本，不能用旧项目其他版本的 Core 随意替换。
- Core、模型、贴图均不随仓库和构建产物提供，默认构建无需它们；不要放到 frontend/public 或 backend/src/main/resources/static。
- 模型实际贴图为 8192×8192，文件约 30 MB，单张 RGBA 解码约 256 MiB，另有 GPU 开销。设备能力不足时回退 SVG；未完成跨 GPU 和长时性能验收。

## 故障排查与范围

| 表现 | 检查 |
| --- | --- |
| 启动时报 AVATAR_ASSETS_DIR_INVALID | 目录是否已有、可读可进入、为绝对路径，且与运行数据分离 |
| 模型文件缺失 | 根目录及 model3 引用的大小写、中文文件名、相对路径是否完整；重启是否带上环境变量 |
| Core 缺失或版本不符 | 是否为指定官方文件、SRI 是否匹配；不是只看文件名相同 |
| WebGL 或贴图错误 | 当前浏览器/GPU 是否支持；先保留 SVG 验证声音 |
| 有声音但嘴不动 | 是否有有效 LipSync 映射和模型嘴部绑定；不是收到文字就触发嘴型 |
| 切换浏览器后变回 SVG | 偏好只保存在当前浏览器和当前端口，重新选择即可 |

浏览器只读取同源资源，不能通过本设置选择服务器任意磁盘目录。正式资源目录由启动配置指定；后续若增加模型目录扫描/选择，需单独设计并验收。

本机大肥鱼模型仅按其 SOURCE.md 记录用于非商业测试，保留水印和署名；本项目 Apache-2.0 不替代模型与 SDK 授权。完整来源与使用范围见 [第三方声明](../THIRD_PARTY_NOTICES.md)。本轮验证及尚未完成项见 [集成交接](handoffs/phase9-integration.md)。
