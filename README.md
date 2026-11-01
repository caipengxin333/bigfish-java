# BigFish Java · 直播互动工作台

基于 Java、Spring Boot 和 Vue 的直播互动项目，将评论回复、礼物感谢、语音合成与浏览器角色舞台串成一条处理链路。管理页面用于配置角色、模型和音色，舞台页面用于播放语音、展示字幕和驱动嘴型。

**当前处于开发阶段，可以体验模拟消息 → DeepSeek 回复 → 阿里云语音 → 舞台播放。真实抖音消息采集尚未打通。** 不配置任何云服务凭据，也可以用模拟回复和本地测试音运行。

## 功能与当前限制

| 能力 | 当前状态 |
| --- | --- |
| 管理页面 | 配置保存、会话启停、供应商与 Token 状态、队列、任务阶段和最近成功时间 |
| 评论与礼物处理 | 评论调用模型，礼物默认使用感谢模板；支持去重、连击合并、优先级、队列容量和过期控制 |
| DeepSeek | 已接入会话，支持模型目录检查和真实回复；也可选择 MOCK 回复 |
| 阿里云 NLS | 已验证知柜（`zhigui`）静态 Token 短句合成与浏览器播放；支持默认 STATIC 和显式 RAM 按需获取/续期，RAM 云端验收待完成 |
| 浏览器舞台 | 音频、字幕、音量驱动嘴型、独占播放端和播放回执；当前为原创 SVG 鱼形角色 |
| 抖音采集 | 已实现原生 Java 签名、游客会话自动获取、房间解析和协议处理；本地链路通过，真实接收待验证，主会话暂不开放 `DOUYIN` 来源 |
| Live2D / 长时间运行 | Live2D 尚未接入；长期连续运行和 OBS 后台播放尚未完成验收 |

舞台当前需要保持前台：切换到后台会静音并断开，返回后需重新连接和启用声音。填入房间号不会自动启用真实抖音采集。

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
2. 保持“MOCK 模拟回复”和“本地测试音”，点击“启动会话”。
3. 打开舞台，依次点击“连接播放端”和“启用声音”。
4. 保持舞台前台，点击舞台中的“发送模拟评论”或“发送模拟礼物”。应出现字幕、嘴型和约 300ms 的测试音，诊断出现 `STARTED`、`COMPLETED`。本地测试音不包含人声。
5. 可点击“采集器连击回放”验证礼物累计数量与重复包处理；点击“停止会话”结束测试。

新会话启动后，需要重新连接舞台并启用声音。`?transport=mock` 是独立的浏览器演示模式，验证 Java 后端时使用默认模式即可。

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

凭据不在网页填写，不保存在业务数据库中，也不应提交到 Git。仅在仓库中创建 `.env` 文件不会自动加载这些值。

已有 AppKey + 静态 Token 的配置可以直接沿用。RAM 模式首次合成时才签发 Token，接近到期时按需刷新；启动、配置保存和状态轮询不发签发请求。模式与凭据更改需要重启，RAM 失败不会自动改用静态 Token。页面展示到期时间、刷新结果及固定错误码，不返回密钥值。

配置完成后：

1. 在管理页查看服务状态。`MISSING` 表示缺少凭据，`UNVERIFIED` 表示尚未真实验证；状态轮询不调用云服务。
2. 配置并保存模型名称。可以点击“检查 DeepSeek 模型目录”检查鉴权和模型是否存在，此操作不会生成回复。
3. 使用阿里云时，选择并保存支持的音色，例如 `xiaoyun` 或 `zhigui`（知柜）。Edge-TTS 的 `zh-CN-...Neural` 名称不能直接使用。角色设定可要求每次回复一两句、尽量不超过 60 字。
4. 停止旧会话，选择回复服务和语音服务后重新启动。先使用“MOCK 回复 + 阿里云 NLS”测试人声，再使用“DeepSeek + 阿里云 NLS”测试完整链路。
5. 在前台舞台连接并启用声音，发送模拟评论验证模型回复，发送模拟礼物验证模板感谢。测试按钮可能触发所选云服务并产生费用；启动会话本身不发送模型或合成请求。

当前 NLS 输入上限为 300 个 UTF-16 字符，主会话音频上限为 25 秒，超限会报错。模型调用整体期限为 20 秒，语音合成为 10 秒，重试受同一个期限约束。模型与语音失败分别展示错误码。

如果没有声音，可在管理页或舞台查看“当前阶段”和最近模型、合成、播放成功时间；管理页可展开最近任务阶段。诊断区分没有消息、播放端未就绪、模型/语音等待、播放等待与取消后仍忙的工作线程。历史错误与当前等待状态分别显示，详细轨迹只保留有限条数和 5 分钟，不是永久日志。

详细说明：[DeepSeek](docs/providers/deepseek.md) · [阿里云 NLS](docs/providers/aliyun-nls.md) · [抖音采集限制](docs/providers/douyin.md)。

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
| 数据目录与端口覆盖 | `BIGFISH_DATA_DIR`、`BIGFISH_PORT`；开发前端可用 `BIGFISH_UI_PORT`、`BIGFISH_API_TARGET` |

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
- 开发协作：[任务边界与合并](docs/task-boundaries.md)、[后续开发计划](docs/phase3-plan.md)。
- 历史记录：[交接记录](docs/handoffs/)、[验证记录](docs/validation.md)。这些文档保留对应阶段的背景与验收结果，具体改动可查 Git 提交历史。

## 许可证与素材

本仓库尚未指定项目许可证，当前没有 `LICENSE` 文件；许可证需由维护者确定后补充。

默认角色为本项目编写的 SVG 鱼形角色，测试音由代码生成，未包含第三方 Live2D 模型。抖音原生签名核心采用注明 MIT 来源的 Java 移植，未引入旧项目的 JavaScript 签名脚本；范围及随包许可见 [第三方声明](THIRD_PARTY_NOTICES.md)。
