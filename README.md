# BigFish Java · 直播互动工作台

基于 Java、Spring Boot 和 Vue 的直播互动项目，将评论回复、礼物感谢、语音合成与浏览器角色舞台串成一条处理链路。管理页面用于配置角色、模型和音色，舞台页面用于播放语音、展示字幕和驱动嘴型。

**当前处于开发阶段，已接入抖音消息采集、DeepSeek 回复、阿里云语音合成和浏览器角色舞台。礼物接收与语音感谢已由用户实测确认正常（2026-11-03）。** 管理页支持游客 / Cookie 身份配置和独立连接检查。不配置云服务凭据也可用模拟消息、模拟回复和本地测试音运行。

## 功能与当前限制

| 能力 | 当前状态 |
| --- | --- |
| 管理页面 | 配置保存、游客 / Cookie 身份切换、20 秒连接检查、本浏览器启动选项、打开舞台、停止互动、供应商与 Token 状态、队列和任务诊断 |
| 评论与礼物处理 | 评论调用模型，礼物默认使用感谢模板；支持去重、连击合并、优先级、队列容量和过期控制；礼物接收及语音感谢已通过用户实测 |
| DeepSeek | 已接入会话，支持模型目录检查和真实回复；也可选择 MOCK 回复 |
| 阿里云 NLS | 已验证知柜（`zhigui`）静态 Token 短句合成与浏览器播放；支持默认 STATIC 和显式 RAM 按需获取/续期，RAM 云端验收待完成 |
| 浏览器舞台 | 一键开始/停止互动、恢复同会话播放、音频、字幕、音量驱动嘴型、独占播放端和播放回执；默认原创 SVG，可切换到本机 Live2D |
| 抖音采集 | 已接入显式 `DOUYIN` 主会话，指定房间的真实评论和礼物接收已有验证；支持游客 / Cookie 模式、自动解析访客标识、本机使用期限和脱敏诊断 |
| Live2D | 已接入固定 BigFish 模型的本机加载、嘴型、眨眼、待机、手动表情和布局设置；外部模型/Core 需单独配置，不随项目分发 |
| 长时间运行 | 长期连续运行、跨浏览器兼容性和 OBS 后台播放尚未完成验收 |

普通标签页切换不会主动断开舞台；浏览器冻结、休眠、音频中断或租约到期时需恢复播放，长期后台和 OBS 行为尚未验收。保存房间号不会自动连接抖音；必须明确选择消息来源并点击舞台“开始互动”。此操作不会替用户在抖音开播。

## 快速开始

### 环境要求

从源码启动或打包需要：

| 工具 | 要求 |
| --- | --- |
| Java | JDK 21，`JAVA_HOME` 指向该安装目录 |
| Maven | 3.9+，`mvn` 可在终端使用 |
| Node.js / npm | Node.js 22.12+ 或 24，安装时附带 npm |
| Shell | 下列 `.sh` 脚本需要 Bash，可在 macOS / Linux 环境使用 |

后端使用 Spring Boot 3.5.11、Spring AI 1.1.8 和 SQLite；前端使用 Vue 3、TypeScript 和 Vite。无需单独安装 MySQL 或 Redis。完整依赖版本见 [Maven 配置](pom.xml) 和 [前端配置](frontend/package.json)。

### 启动开发服务

克隆仓库后，在仓库根目录打开两个终端。

终端一，启动后端：

```bash
./scripts/dev-backend.sh
```

终端二，启动前端：

```bash
./scripts/dev-frontend.sh
```

首次运行需要联网下载依赖。前端脚本会在缺少 `node_modules` 时安装依赖；Maven 脚本使用项目内的 HTTPS 源与 `.cache/maven` 缓存。macOS 上未配置有效 `JAVA_HOME` 时，脚本会尝试定位已安装的 Java 21。

| 入口 | 默认地址 |
| --- | --- |
| 管理页面 | [http://127.0.0.1:15173/admin](http://127.0.0.1:15173/admin) |
| 角色舞台 | [http://127.0.0.1:15173/stage](http://127.0.0.1:15173/stage) |
| 后端健康检查 | [http://127.0.0.1:18080/actuator/health](http://127.0.0.1:18080/actuator/health) |

也可以用 IDEA 导入根目录 `pom.xml`，SDK 选择 21，运行 `cn.bigfish.BigfishApplication`，工作目录设为仓库根目录。前端在 `frontend` 目录执行 `npm ci`、`npm run dev`。脚本与 IDEA 启动方式二选一，避免重复占用后端端口。

### 不配置 Key，先验证播放

1. 打开管理页面，保持默认“真实后端接口”。这里表示连接本机 Java 服务；消息来源仍为 MOCK。
2. 保持“模拟采集”“MOCK 模拟回复”和“本地测试音”。这三项自动记在同源浏览器中；房间、人设、模型名称和音色仍需点击“保存配置”写入后端。
3. 点击“打开舞台”，在舞台点击一次“开始互动”。页面协调创建或接入已有会话、播放连接与声音授权；浏览器阻止声音时再点击“点击启用声音”。
4. 展开舞台的“高级调试：模拟事件与队列”，点击“发送模拟评论”或“发送模拟礼物”。应出现字幕、嘴型和约 300ms 的测试音，播放诊断出现 `STARTED`、`COMPLETED`。本地测试音不包含人声。
5. 可点击“采集器连击回放”验证礼物累计数量与重复包处理；点击“停止互动”结束测试。

后端已有会话时，“开始互动”接入该会话，不另建一套处理流程。仅本页断线时使用“恢复播放”；第二个舞台不能抢占有效播放租约。`?transport=mock` 是独立的浏览器演示模式，验证 Java 后端时使用默认模式即可。

## 配置 DeepSeek 与阿里云语音

云服务凭据由**后端进程**读取。可以在 IDEA 的“运行 → 编辑配置 → BigfishApplication → 环境变量”中填写，或由终端、服务器进程管理器注入环境变量，修改后重启后端。

| 环境变量 | 用途 |
| --- | --- |
| `BIGFISH_ALLOW_PAID` | 设为 `true` 才允许云服务调用，默认 `false` |
| `BIGFISH_DEEPSEEK_API_KEY` | DeepSeek API Key，仅测试阿里云时可不填 |
| `BIGFISH_ALIYUN_NLS_APP_KEY` | 阿里云智能语音交互 NLS 项目的 AppKey，仅测试 DeepSeek 时可不填 |
| `BIGFISH_ALIYUN_NLS_TOKEN_MODE` | 可选：默认 `STATIC`；显式设为 `RAM` 使用 AccessKey 按需签发与续期 |
| `BIGFISH_ALIYUN_NLS_TOKEN` | STATIC 模式的 NLS Token，不是 DashScope API Key；过期后需更新并重启后端 |
| `BIGFISH_ALIYUN_ACCESS_KEY_ID` | 仅 RAM 模式需要，与 NLS 项目权限匹配的阿里云 AccessKey ID |
| `BIGFISH_ALIYUN_ACCESS_KEY_SECRET` | 仅 RAM 模式需要，对应 AccessKey Secret；不支持把 STS 临时凭据当作此模式使用 |

上述 DeepSeek 和阿里云凭据不在网页填写，不保存在业务数据库中，也不应提交到 Git。抖音 Cookie 使用下文的独立身份配置入口。仅在仓库中创建 `.env` 文件不会自动加载这些值。

已有 AppKey + 静态 Token 的配置可以直接沿用。RAM 模式首次合成时才签发 Token，接近到期时按需刷新；启动、配置保存和状态轮询不发签发请求。模式与凭据更改需要重启，RAM 失败不会自动改用静态 Token。页面展示到期时间、刷新结果及固定错误码，不返回密钥值。

配置完成后：

1. 在管理页查看服务状态。`MISSING` 表示缺少凭据，`UNVERIFIED` 表示尚未真实验证；状态轮询不调用云服务。
2. 配置并保存模型名称。可以点击“检查 DeepSeek 模型目录”检查鉴权和模型是否存在，此操作不会生成回复。
3. 使用阿里云时，选择并保存支持的音色，例如 `xiaoyun` 或 `zhigui`（知柜）。Edge-TTS 的 `zh-CN-...Neural` 名称不能直接使用。角色设定可要求每次回复一两句、尽量不超过 60 字。
4. 停止旧互动，在管理页选择回复服务和语音服务，在舞台点击“开始互动”。先使用“MOCK 回复 + 阿里云 NLS”测试人声，再使用“DeepSeek + 阿里云 NLS”测试完整链路。
5. 展开舞台的高级调试区，发送模拟评论验证模型回复，发送模拟礼物验证模板感谢。测试按钮可能触发所选云服务并产生费用；启动会话本身不发送模型或合成请求。

当前 NLS 输入上限为 300 个 UTF-16 字符，主会话音频上限为 25 秒，超限会报错。模型调用整体期限为 20 秒，语音合成为 10 秒，重试受同一个期限约束。模型与语音失败分别展示错误码。

如果没有声音，先看管理页或舞台顶部的故障摘要和下一步操作；采集、模型、合成与播放状态分开展示。单条任务失败时会话可能仍在运行，后续同环节成功后，故障转为折叠区的历史记录。详细错误码、最近任务阶段及成功时间可在诊断区展开；详细轨迹只保留有限条数和 5 分钟，不是永久日志。

详细说明：[DeepSeek](docs/providers/deepseek.md) · [阿里云 NLS](docs/providers/aliyun-nls.md) · [抖音采集限制](docs/providers/douyin.md)。

## 抖音接入与礼物排查

默认使用游客模式。管理页保存数字房间号，在“抖音接入身份”选择“使用登录 Cookie”，填写完整 Cookie、同一浏览器的 User-Agent 和本机使用期限，保存后可点击“检查连接（20 秒）”。访客标识默认自动解析，失败时才手动补填。检查只接收消息，不调用大模型或语音；没有观察到礼物不能据此判定无礼物权限。获取方法见页面说明和 [抖音配置](docs/providers/douyin.md#主会话凭据配置)。

页面配置无需重启 Java，下一次互动使用新版本；编辑前先停止互动或连接检查。Cookie 只保存在后端内存，刷新页面保留、重启后清空；启动环境变量仍可提供默认值，页面显示当前来源。切换游客或清除会屏蔽本进程的旧环境 Cookie。凭据管理仅允许本机同源访问，不提供远程登录、自动续期或扫码登录。

开始正式互动时，选择抖音采集并在舞台点击“开始互动”。独立连接检查显示采集连接及评论、礼物接收结果；正式互动会继续处理礼物、生成感谢语音并交给舞台播放。该礼物接收与感谢流程已通过用户实测。

控制台默认输出 `collector_inbound`（接收和解析）与 `gift_trace`（业务处理）脱敏日志。可设置 `BIGFISH_GIFT_TRACE_PERSIST=true`，将礼物业务轨迹额外保存到 `<BIGFISH_DATA_DIR>/diagnostics/gifts/`，最多 4 个各 1 MiB 的轮转文件；这些文件不包含采集端日志。若更换房间或凭据后出现异常，可按日志区分解析失败、去重、队列丢弃及合成/播放失败，判读步骤见 [礼物排查](docs/providers/douyin.md#礼物没有感谢时如何定位)。点赞按当前策略不生成感谢语音。

## 本机 Live2D 形象

默认 SVG 无需模型资源。使用 Live2D 时，将模型和指定版本的 Cubism Core 放入**独立资源目录**，设置后端环境变量 `BIGFISH_AVATAR_ASSETS_DIR` 为该目录的绝对路径，重启后端；随后在舞台展开“舞台形象设置”，选择“Live2D · 本机 BigFish”。

资源目录须与 `BIGFISH_DATA_DIR` 分离，不放入默认的 `runtime/` 数据目录及其子目录。前端通过同源 `/avatar-assets/` 读取，Java 不需要启动 Python 辅助服务。模型缺失或渲染失败会回退 SVG，已有声音链路保持可用。

当前仅支持固定 BigFish 入口，没有自动扫描、目录选择或上传功能。大小、位置、背景仅保存在当前浏览器；模型/Core 不进入 Git、前端构建产物或 JAR。目录结构、SDK 固定版本、启动方式及使用范围见 [Live2D 配置](docs/live2d.md)。

## 打包与运行

在仓库根目录执行：

```bash
./scripts/package.sh
./scripts/run.sh
```

打包脚本构建前端并将静态页面放入后端 JAR。运行后使用 [管理页面](http://127.0.0.1:18080/admin) 和 [角色舞台](http://127.0.0.1:18080/stage)，页面与 API 共用 `18080` 端口，不需要再启动 Vite。

产物为 `backend/target/bigfish-server-0.1.0-SNAPSHOT.jar`。复制到其他机器后，仅需 Java 21；在可写工作目录中运行：

```bash
java -jar bigfish-server-0.1.0-SNAPSHOT.jar
```

服务器上的凭据仍通过 Java 进程环境注入。默认监听 `127.0.0.1:18080`，该命令不会将管理页面开放到公网。Node.js 和 Maven 仅用于源码构建。

## 数据与配置存储

| 内容 | 位置 / 生效方式 |
| --- | --- |
| 启动配置 | [application.yml](backend/src/main/resources/application.yml)；端口等启动参数修改后重启 |
| 角色、模型、音色等业务配置 | 默认存入 SQLite `runtime/app.db`；网页保存后用于新任务，房间或服务类型切换需新建会话 |
| 生成音频 | 默认在 `runtime/audio/` |
| API Key / NLS Token | 由后端运行环境提供，修改后重启 |
| 抖音凭据 | 页面提供游客/Cookie 选择，仅后端内存；启动环境为默认来源，不写 SQLite。页面更新下次互动生效，重启丢失页面配置 |
| 礼物业务轨迹 | 可选 `BIGFISH_GIFT_TRACE_PERSIST=true`，保存到数据目录下 `diagnostics/gifts/`，容量轮转会覆盖旧记录 |
| 数据目录与端口覆盖 | `BIGFISH_DATA_DIR`、`BIGFISH_PORT`；开发前端可用 `BIGFISH_UI_PORT`、`BIGFISH_API_TARGET` |
| Live2D 资源 | `BIGFISH_AVATAR_ASSETS_DIR` 指定独立绝对目录；更换根目录后重启；形象偏好保存在浏览器 |

启动脚本默认使用仓库内 `runtime/` 的绝对路径；直接执行 `java -jar` 时，默认数据目录相对于当前工作目录。需要固定存储位置时设置 `BIGFISH_DATA_DIR`。SQLite 适合当前单实例运行方式。

`runtime/`、`.cache/`、本机环境文件、IDE 配置及构建产物已配置 Git 忽略规则。多工作树开发使用的 `runtime/worktree.env` 仅存端口和 API 地址等非敏感配置。

## 项目结构与处理流程

```text
bigfish-java/
├── backend/       Java 后端：采集、调度、模型、语音、配置和播放控制
├── frontend/      Vue 管理页面与角色舞台
├── scripts/       开发启动、构建打包与检查脚本
└── docs/          接口、服务接入、设计和开发记录
```

消息经过校验、去重和有界调度后，评论进入模型服务，礼物进入感谢模板，再交给语音服务生成音频。后端通过 WebSocket 通知舞台播放，舞台返回播放开始、完成或失败回执。

开发时前后端分别运行；打包后为一个 Java 进程。采集器属于后端模块，无需单独部署一个采集器服务。

## 开发验证

最近一次集成验证（2026-11-03）：后端 322 项测试中 320 项通过，2 项真实云端测试未启用；前端 140 项测试全部通过，生产构建、JAR 打包和本机页面 / HTTP 联调通过。礼物接收与语音感谢另由用户实测确认正常。长期连续运行、OBS 后台播放及跨浏览器兼容性仍待进一步验证。

后端测试：

```bash
./scripts/maven.sh test
```

前端测试与构建（在 `frontend` 目录执行）：

```bash
npm ci
npm test
npm run build
```

启动打包后的服务后，可在仓库根目录执行 `node scripts/smoke.mjs` 检查页面、健康状态和配置接口。该脚本会临时修改并恢复显示名称，应在独立测试实例上运行。真实云服务验收需另行配置凭据并显式触发，普通测试不代表线上服务已验证。

## 更多文档

- 接口与协议：[HTTP API](docs/openapi.yaml)（包含只读 `/api/v1/runtime/diagnostics`）、[事件格式](docs/event-schema.md)、[播放协议](docs/playback-protocol.md)。
- 运行排查：[运行稳定性与诊断](docs/runtime-stability.md)。
- 设计与前端：[架构设计](docs/architecture.md)、[前端与舞台](frontend/README.md)。
- 开发协作：[任务边界与合并](docs/task-boundaries.md)、[集成交接记录](docs/handoffs/)。
- 历史记录：[交接记录](docs/handoffs/)、[验证记录](docs/validation.md)。这些文档保留对应阶段的背景与验收结果，具体改动可查 Git 提交历史。

## 许可证与素材

本项目原创代码采用 **Apache License 2.0（Apache-2.0）**，完整条款见 [LICENSE](LICENSE)。第三方代码、依赖与素材仍按各自的许可证或授权条款使用，项目许可证不替代其原有声明。

默认角色为本项目编写的 SVG 鱼形角色，测试音由代码生成，未包含第三方 Live2D 模型。抖音原生签名核心采用注明 MIT 来源的 Java 移植，未引入旧项目的 JavaScript 签名脚本；范围及随包许可见 [第三方声明](THIRD_PARTY_NOTICES.md)。

外部 Live2D 模型及 Cubism SDK 的使用和分发须分别遵守其授权；本项目采用 Apache-2.0 不代表这些第三方资源获得商业使用或再分发许可。
