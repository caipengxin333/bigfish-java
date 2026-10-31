# BigFish Java 协作规则

## 用户约束
- 禁止批量删除文件或目录，禁止 rm -rf、递归删除、git clean。需要删除时只可删除一个明确路径文件；批量删除必须由用户手动处理。
- 每一步代码修改前先简短说明要做什么，完成后说明改动和验证结果。
- 保留原 Luna 项目及本仓库已有修改。不得回滚他人的工作。

## 工作边界

当前第三轮职责与独占目录见 docs/phase3-plan.md，优先于第二轮计划、第一轮 prompts 和历史宽范围分工。原前端窗口继续做 Java 阿里云 TTS；Vue、公共接口及会话接线由主集成窗口统一负责。独立 GitHub 上传按 docs/prompts/github-publish.md 执行，仅该任务在目标明确后可正常推送；三个实现窗口仍不推送。
1. 当前项目是独立的新实现，不是 Luna 的原地重写。首先阅读 README.md、docs/architecture.md、docs/task-boundaries.md、自己任务对应的 docs/prompts 文件。
2. 只在指定 worktree 和分支工作；每次开工检查 `pwd`、`git branch --show-current`、`git status --short`。
   - 每轮继续开发也要同步基线，不只首次开工。功能分支先执行 `git merge-base --is-ancestor main HEAD`；非 0 表示尚未包含当前 main。保留并核对本工作树已有改动，干净后执行 `git merge --no-edit main`，再阅读最新对应 handoff。不得用复制目录、reset 或整文件选一方替代合并。
   - 交付前再次检查是否包含最新 main；主窗口的修复与回归测试属于后续开发基线。改变已有修复方案必须解释原因并保留等效测试，不能为通过测试删除保护断言。
3. 不在同一工作目录同时运行多个会修改文件的任务。各 worktree 的端口、SQLite、音频和日志必须隔离；使用 scripts/dev-*.sh 自动载入 runtime/worktree.env。
4. 所有任务共享接口约定：docs/openapi.yaml、docs/event-schema.md、docs/playback-protocol.md 和 contract 包。不能自行修改公共 DTO、接口字段或依赖版本。先将提案写入本任务 docs/handoffs/<role>.md，在本窗口报告，交由集成窗口处理。
5. main 分支只由集成窗口更新。实现窗口可以在自己的分支创建本地提交，并按上条合入 main 同步基线；不合并其他功能分支、不推送远端、不创建 PR、不修改原 Luna。
6. 不自动创建/联系其他 Codex 任务；有跨模块需求时写入交接文件，由用户或集成窗口读取。
7. 完成时更新本任务 handoff，列出提交 SHA、实际修改、已运行命令及结果、限制和公共接口提案。不能只说“完成”。

## 架构和验证
- Java 21 / Spring Boot 3.5.11 / Spring AI 1.1.8；Vue 3 + TypeScript；SQLite 单实例。依赖版本由集成窗口统一管理。
- 业务层依赖 contract 接口；采集线程不等待 LLM/TTS；所有队列必须有容量与过期时间。
- 评论使用模型，礼物默认使用模板。没有 Key 时应用必须能启动，模拟模式必须在 UI/API 中标明。
- 主会话必须有 sessionId，模型、音频、回执必须关联任务 ID。旧会话任务不得在新会话播出。
- 密钥禁止进入源码、提示词、日志、前端响应、测试夹具。不要复制旧项目 config.json。不得自动发送抖音评论或购买礼物。
- Live2D 模型及第三方采集代码要记录来源与许可证，不把“可下载”等同于可分发。未经确认不复制旧模型或 AGPL 签名脚本进新仓库。
- 后端验证：`./scripts/maven.sh test`；前端：`cd frontend && npm ci && npm run build`。具体行为需要针对性测试。
- 日志记录 error type、耗时和脱敏上下文，不打印 Cookie、签名 URL 或 Key。
- 当前已集成配置、MOCK 采集/调度、管理 REST/WebSocket、DeepSeek/NLS 会话选择和供应商状态；2026-11-01 已验证 NLS 知柜真实合成、解码及 Safari 播放回执，抖音尚缺可用 signer，长期稳定性未验收。最新结果见 docs/handoffs/phase2-integration.md；语音维护须保留 docs/handoffs/phase2-speech.md 中的 PCM 转 WAV 修复。
