# 新窗口任务：Vue 前端与播放舞台

> 第一轮已完成。本轮请使用 [phase2-speech.md](phase2-speech.md)，不要重新执行本文件中的第一轮任务。


将下方整段发送给在对应 worktree 新建的 Codex 任务。不要让多个任务打开同一个工作目录。

---

你负责 BigFish Java 的「前端与舞台」。

工作目录：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/frontend`
分支：`feature/frontend-stage`
主集成目录：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-java`，不要在其中修改文件。
旧项目参考：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/AI-Vtuber`，只读。

先执行 pwd、git branch --show-current、git status --short，确认目录/分支正确。阅读 AGENTS.md、README.md、docs/architecture.md、docs/task-boundaries.md、docs/openapi.yaml、docs/event-schema.md、docs/playback-protocol.md，再开展工作。

完善 Vue 管理页、配置编辑、保存/生效状态区别、运行状态、队列与失败原因展示；实现 /stage 音频播放器、字幕、音量驱动占位动画，并按播放协议连接 WebSocket。前端不能持有供应商 API Key，管理页不能发声。保留现有配置保存/409 提示能力。真实接口未实现时，使用可显式选择的 mock transport 进行开发，不伪造“已连接抖音/已合成”。UI 显示当前模式。
验收：npm run build 通过；加载失败/保存冲突/超时均有提示；需要点击启用声音；STARTED 与 COMPLETED 对应真正播放；重复任务不重播；过期任务、旧 sessionId 丢弃；断线/停止立即闭嘴；第二个页面租约被拒时保持静音；移动宽度可用。Live2D 先做可插拔入口和原创简单占位，不直接复制限制分发的旧模型。公共 contracts.ts 或接口字段变更先提案，不自行重定义。

只能修改 docs/task-boundaries.md 指定的目录；公共文件改动写入 `docs/handoffs/frontend.md` 提案，报告给用户，由当前集成窗口处理，不自行改协议或向别的任务发消息。

使用 scripts/dev-backend.sh / scripts/dev-frontend.sh 启动，它们会读取本工作树 runtime/worktree.env 并隔离端口与数据目录。每步改代码前简短说明，结束说明结果。不得批量删除，不回滚已有修改，不暴露密钥。

在自己的分支提交经过验证的小范围本地提交；不 merge 其他功能分支、不推送。完成后更新 `docs/handoffs/frontend.md`，列出提交 SHA、实现内容、测试命令与结果、mock/真实验证区别、限制和依赖提案。在当前任务最终回复中报告这些内容，交由集成窗口合并。
