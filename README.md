# BigFish Java · 直播互动工作台

Java + Spring / Vue 的独立项目骨架。原 `../AI-Vtuber` 保留用于功能参考。

## 已完成与未完成

已完成：Java 工程与模块边界、API/事件/播放契约、SQLite 配置读写、输入校验、乐观版本检查、管理页、舞台占位页、启动/打包脚本及回归测试。

未完成：真实抖音采集、DeepSeek 回复、TTS、队列调度、播放端租约及 Live2D。骨架不会调用付费模型或连接抖音，API mode 为 SCAFFOLD。配置保存成功只表示已持久化，不表示直播引擎已应用。

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
- [HTTP 接口](docs/openapi.yaml)：仅前三个实际接口；未来接口见架构文档。
- [事件格式](docs/event-schema.md)
- [播放协议](docs/playback-protocol.md)：已约定，尚未实现。
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
