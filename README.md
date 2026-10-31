# BigFish Java · 直播互动工作台

Java + Spring / Vue 的独立直播互动项目。原 `../AI-Vtuber` 保留用于功能参考。

## 已完成与未完成

已完成：两轮三个分支集成，SQLite 配置、会话启停/状态/模拟事件接口、有界调度、礼物模板、模拟采集回放、同源音频、独占播放租约，以及管理页与舞台的 HTTP/WebSocket 接线。DeepSeek 和阿里云 NLS 适配器已接入会话，可分别选择回复和语音服务；通过本地替身、超时/取消/重试/文件释放测试。

尚未验收：真实 DeepSeek 请求、阿里云真实合成、真实抖音收消息、Live2D 和 OBS 长期后台播放。抖音已实现房间解析、连接提供器和协议处理，但缺少可实际使用的签名实现，主会话仍拒绝 DOUYIN。默认 Java MOCK + MOCK 回复 + 300ms 测试音，无 Key 也可运行；填写房间号或音色不会自动连接真实服务。bootstrap.mode=READY 只表示管理引擎就绪；speech 表示后端已配置阿里云凭据并启用云调用，真实验证状态须看 /providers，不能据此宣称已连通。

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

基础配置：backend/src/main/resources/application.yml（端口、数据库、日志等，改动一般重启）。业务配置：SQLite app_config 单行，版本 CAS 更新；前端 GET 后用 expectedVersion PUT，冲突返回 409。API Key / NLS Token 只通过后端环境变量注入，不在网页填写或存入业务 SQLite。修改凭据后重启后端，业务配置保存后用于新任务快照。

## 文档与任务

当前第二轮分工见 [真实服务接入计划](docs/phase2-plan.md)：DeepSeek、阿里云语音、抖音采集分别由三个现有工作树实现，主窗口统一 UI/API 与最终接线。

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

1. 打开 `/admin`，使用默认“真实后端接口”（不要切换浏览器本地 mock）。保持 MOCK 回复 + 本地测试音，点击“启动会话”。
2. 打开 `/stage`，点击“连接播放端”→“启用声音”。
3. 保持舞台前台，在舞台下方点击“发送模拟评论”或“发送模拟礼物”：看到短暂字幕/嘴型并听到测试音，诊断出现 STARTED、COMPLETED。
4. 点击“采集器连击回放”：真实运行 Java MockCollector，输入累计礼物 1→3→3；字幕应感谢合计 3 个小心心，重复终包不增加数量。
5. 点击“停止会话”：取消音频、清空队列、停止采集；Java 管理服务保持运行。新会话需重新连接舞台并启用声音。

开发：IDEA 导入根 pom.xml，Project SDK=21，运行 `cn.bigfish.BigfishApplication`；working directory 设仓库根目录，数据库默认 runtime/app.db。另在 frontend 执行 npm run dev。也可使用上面的脚本，避免本机全局 Maven 配置差异。

单 JAR：只需启动 Java，采集器是后端模块，Vue 构建产物随 JAR 提供。三个 bigfish-worktrees 目录是开发副本，不是三个部署服务。

## 第二轮：使用真实模型和人声

在 IDEA 的 BigfishApplication 运行配置中设置环境变量，再重启后端；只测试一种云服务时，仅填写对应服务凭据：

| 变量 | 用途 |
|---|---|
| `BIGFISH_ALLOW_PAID=true` | 明确启用云服务调用，默认 false |
| `BIGFISH_DEEPSEEK_API_KEY` | DeepSeek 官方 API 凭据 |
| `BIGFISH_ALIYUN_NLS_APP_KEY` | 阿里云智能语音交互 NLS 项目 AppKey |
| `BIGFISH_ALIYUN_NLS_TOKEN` | NLS Token；不是 DashScope API Key，过期后需更换并重启 |

1. `/admin` 查看服务状态。`MISSING` 表示缺凭据；`UNVERIFIED` 表示存在但未真实验证。状态轮询不请求云服务。
2. 保存模型名称，可点“检查 DeepSeek 模型目录”验证鉴权和已保存模型是否在目录；此检查不生成回复，不能替代生成验收。
3. 使用阿里云前，把音色改为 `xiaoyun`（或页面列出的其他支持音色）并保存。Edge 的 `zh-CN-...Neural` 标识不可直接使用。建议在角色设定中要求“每次只用一两句回答，尽量不超过 60 字”；NLS 最多 300 个 UTF-16 字符，主会话最多 25 秒音频，超长返回错误，不静默截断。
4. 停止旧会话后选择回复服务与语音服务，再启动。可先 MOCK 回复 + ALIYUN_NLS，测试礼物模板人声；再 DEEPSEEK + ALIYUN_NLS，用模拟评论验证模型到音频的完整链路。每次测试按钮都可能触发所选真实云服务。
5. 保持 `/stage` 前台，连接并启用声音，用舞台上的模拟评论/礼物按钮测试。启动会话本身不发送模型或合成请求。

Token 自动刷新仅有扩展接口，尚未实现阿里云 CreateToken；当前凭据更新需重启。模型整体期限 20 秒，阿里云合成整体期限 10 秒，内部重试最多两次且共享期限。模型、合成失败显示独立错误码，不无限阻塞后续任务。抖音真实来源仍不可选择。

第二轮集成结果和待验收项见 [集成交接](docs/handoffs/phase2-integration.md)，供应商配置细节见 [DeepSeek](docs/providers/deepseek.md)、[阿里云 NLS](docs/providers/aliyun-nls.md)、[抖音](docs/providers/douyin.md)。
