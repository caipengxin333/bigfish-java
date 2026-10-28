# 新窗口任务：Java 后端核心

将下方整段发送给在对应 worktree 新建的 Codex 任务。不要让多个任务打开同一个工作目录。

---

你负责 BigFish Java 的「后端核心」。

工作目录：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/backend`
分支：`feature/backend-core`
主集成目录：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-java`，不要在其中修改文件。
旧项目参考：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/AI-Vtuber`，只读。

先执行 pwd、git branch --show-current、git status --short，确认目录/分支正确。阅读 AGENTS.md、README.md、docs/architecture.md、docs/task-boundaries.md、docs/openapi.yaml、docs/event-schema.md、docs/playback-protocol.md，再开展工作。

实现运行会话状态机、动态配置应用快照、EventIngress、去重与有界优先级调度、礼物模板、ConversationPort、SpeechPort 适配层，以及播放 WebSocket 和音频资源接口。优先完成 mock 端到端，再实现 DeepSeek OpenAI 兼容适配（现有 Spring AI 依赖）。没有 Key 时正常启动，默认不触发真实付费调用。TTS 首先提供可重复的本地测试适配器；真实服务选择和新增依赖写入提案，不能自行复制旧 Python 项目。必须支持整体超时、取消、会话停止、过期丢弃、忙时欢迎限频、旧会话隔离和唯一播放端。禁止把真实抖音连接放到本任务。
验收：配置持久化和 CAS 原测试仍通过；mock 评论/礼物通过不同通道；慢模型不会阻塞礼物；满队列容量不增长；SSE keep-alive 超时仍能取消；断线/停止后不继续播；双播放端不会重复发声。前端未完成时用 WebSocket 测试客户端验收。计划 REST schema 如需补齐，先写提案由集成窗口更新公共契约。

只能修改 docs/task-boundaries.md 指定的目录；公共文件改动写入 `docs/handoffs/backend.md` 提案，报告给用户，由当前集成窗口处理，不自行改协议或向别的任务发消息。

使用 scripts/dev-backend.sh / scripts/dev-frontend.sh 启动，它们会读取本工作树 runtime/worktree.env 并隔离端口与数据目录。每步改代码前简短说明，结束说明结果。不得批量删除，不回滚已有修改，不暴露密钥。

在自己的分支提交经过验证的小范围本地提交；不 merge 其他功能分支、不推送。完成后更新 `docs/handoffs/backend.md`，列出提交 SHA、实现内容、测试命令与结果、mock/真实验证区别、限制和依赖提案。在当前任务最终回复中报告这些内容，交由集成窗口合并。
