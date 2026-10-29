# BigFish Java · 直播互动工作台

Java + Spring / Vue 的独立直播互动项目。原 `../AI-Vtuber` 保留用于功能参考。

## 已完成与未完成

已完成：三个功能分支集成，SQLite 配置、会话启停/状态/模拟事件接口、有界调度、礼物模板、模拟采集回放、同源音频、独占播放租约，以及管理页与舞台的 HTTP/WebSocket 接线。DeepSeek 适配器通过本地模拟 SSE 验证。

尚未完成：真实抖音房间解析/签名连接提供器、真实人声 TTS、真实 DeepSeek 请求验收、Live2D 和 OBS 长期后台播放。当前 UI 启动的是 Java MOCK 会话，输出 300ms WAV 测试音；不会因为填写房间号或音色就连接真实服务。bootstrap.mode=READY 表示管理和模拟引擎已接通，能力开关仍标记 douyinCollector=false / speech=false。

技术基线：Java 21、Spring Boot 3.5.11、Spring AI 1.1.8（库依赖，无启动时 Key 要求）、SQLite JDBC 3.49.1.0、Vue 3.5.22、Vite 7.1.12。Node 建议 22.12+ 或 24 LTS，Maven 3.9+。这里选择可验证的 3.5/1.1 稳定组合，未采用此前讨论过的 Boot 4 / Spring AI 2 组合。

## 开发运行

从仓库根目录打开两个终端：

```bash
./scripts/dev-backend.sh
./scripts/dev-frontend.sh
```

默认页面 http://127.0.0.1:15173/admin ，舞台 http://127.0.0.1:15173/stage ，后端 http://127.0.0.1:18080 。依赖首次需联网下载，端口被占用不会自动关闭占用进程。

Maven 脚本会在 macOS 上定位 Java 21，并使用本项目 HTTPS 源和 `.cache/maven`；不改全局 Maven、JAVA_HOME 或 npm 配置。

## 单 JAR 交付

```bash
./scripts/package.sh
./scripts/run.sh
```

打包后页面为 http://127.0.0.1:18080/admin 和 /stage，服务在同一端口提供静态页面和 API。首次打包需联网，Node 只在构建时使用，运行已打包 JAR 只需 Java 21。

## 数据与配置

默认数据库 `runtime/app.db`，执行脚本时使用 worktree 下的绝对目录。worktree 的 `runtime/worktree.env` 只设置端口与 API 地址，脚本按本工作树自动确定数据路径，不放密钥。`runtime/` 与 `.cache/` 不入 Git。

基础配置：backend/src/main/resources/application.yml（端口、数据库、日志等，改动一般重启）。业务配置：SQLite app_config 单行，版本 CAS 更新；前端 GET 后用 expectedVersion PUT，冲突返回 409。现在不接收 API Key，后端任务需另行实现凭据安全存储。

## 文档与任务

- [架构与范围](docs/architecture.md)
- [HTTP 接口](docs/openapi.yaml)：配置、会话、模拟事件、回放及音频接口。
- [事件格式](docs/event-schema.md)
- [播放协议](docs/playback-protocol.md)：已实现模拟闭环。
- [任务边界与合并](docs/task-boundaries.md)
- [开新窗口指南](docs/开新窗口指南.md)
- [后端任务](docs/prompts/backend.md)、[前端任务](docs/prompts/frontend.md)、[采集任务](docs/prompts/collector.md)
- [验证记录](docs/validation.md)

## 检查

```bash
./scripts/maven.sh test
(cd frontend && npm run build)
# 已启动打包后的服务时：
node scripts/smoke.mjs
```

联网权限受限时允许申请依赖下载权限；不得用关闭 TLS 验证解决问题。没有远端仓库配置，没有发布、部署或外发。

## 第一轮联调操作

1. 打开 `/admin`，使用默认“真实后端接口”（不要切换浏览器本地 mock）。点击“启动模拟会话”。
2. 打开 `/stage`，点击“连接播放端”→“启用声音”。
3. 保持舞台前台，在舞台下方点击“发送模拟评论”或“发送模拟礼物”：看到短暂字幕/嘴型并听到测试音，诊断出现 STARTED、COMPLETED。
4. 点击“采集器连击回放”：真实运行 Java MockCollector，输入累计礼物 1→3→3；字幕应感谢合计 3 个小心心，重复终包不增加数量。
5. 点击“停止会话”：取消音频、清空队列、停止采集；Java 管理服务保持运行。新会话需重新连接舞台并启用声音。

开发：IDEA 导入根 pom.xml，Project SDK=21，运行 `cn.bigfish.BigfishApplication`；working directory 设仓库根目录，数据库默认 runtime/app.db。另在 frontend 执行 npm run dev。也可使用上面的脚本，避免本机全局 Maven 配置差异。

单 JAR：只需启动 Java，采集器是后端模块，Vue 构建产物随 JAR 提供。三个 bigfish-worktrees 目录是开发副本，不是三个部署服务。

真实 DeepSeek 的 API 路径已保留：服务端 `BIGFISH_ALLOW_PAID=true`、`BIGFISH_DEEPSEEK_API_KEY`，创建会话时显式 conversationMode=DEEPSEEK；本轮未设置、迁移或验证用户凭据，UI 默认只发起 MOCK。真实 TTS 仍须另行实现。
