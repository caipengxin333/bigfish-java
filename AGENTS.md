# BigFish Java 协作规则

## 用户约束
- 禁止批量删除文件或目录，禁止 rm -rf、递归删除、git clean。需要删除时只可删除一个明确路径文件；批量删除必须由用户手动处理。
- 每一步代码修改前先简短说明要做什么，完成后说明改动和验证结果。
- 保留原 Luna 项目及本仓库已有修改。不得回滚他人的工作。

## 工作边界

当前职责以用户第四/五轮任务及 docs/task-boundaries.md 为准，覆盖第三轮历史分工：后端窗口负责 Java 会话与播放生命周期，前端窗口回归 Vue 页面及浏览器音频生命周期，采集窗口独占采集解析/签名。Java 语音适配本轮没有新任务，不能因旧职责顺手修改。公共契约、文档和最终联调由主集成窗口负责，结果见 docs/handoffs/phase5-integration.md。独立 GitHub 上传按 docs/prompts/github-publish.md 执行；实现窗口仍不推送。
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
- 当前已集成显式 MOCK/DOUYIN 会话、DeepSeek/NLS、STATIC/RAM Token、任务诊断及舞台一键互动。DOUYIN 能力开放不代表接收通过；最近真实解析返回 PAGE_STRUCTURE_CHANGED，真实评论/礼物仍未验收。普通切后台不主动断开，冻结/失效后手动恢复同会话。最新集成结果见 docs/handoffs/phase5-integration.md；RAM 云端签发与长期后台稳定性未验收。须保留 c8be17c 的 PCM 转 WAV、停止/租约隔离及主窗口音频中断释放播放端修复。
- 前端最新故障摘要修补见 docs/handoffs/failure-summary-ui.md；后续先同步 main，保留采集根因优先、同会话诊断证据及默认折叠的技术详情，不能把后续播放断开显示为首要故障。
