# 新窗口任务：Java 抖音消息采集器

> 第一轮已完成。本轮请使用 [phase2-collector.md](phase2-collector.md)，不要重新执行本文件中的第一轮任务。


将下方整段发送给在对应 worktree 新建的 Codex 任务。不要让多个任务打开同一个工作目录。

---

你负责 BigFish Java 的「抖音采集」。

工作目录：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/collector`
分支：`feature/douyin-collector`
主集成目录：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-java`，不要在其中修改文件。
旧项目参考：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/AI-Vtuber`，只读。

先执行 pwd、git branch --show-current、git status --short，确认目录/分支正确。阅读 AGENTS.md、README.md、docs/architecture.md、docs/task-boundaries.md、docs/openapi.yaml、docs/event-schema.md、docs/playback-protocol.md，再开展工作。

实现 CollectorPort 的独立 Java 采集器。先构造可回放的脱敏消息夹具和 mock 实现，验证事件 schema、礼物数量与连击增量；随后研究当前真实房间连接、WebSocket 心跳、确认消息、解压、Protobuf 解析、重连和关播停止。旧 ../AI-Vtuber 仅作只读参考，实际绝对路径见开新窗口指南。不要调用 DeepSeek、TTS 或实现管理页，不把事件回调阻塞在业务处理上。
签名 JavaScript 与上游协议代码存在许可证边界；先记录需要的第三方组件、版本和许可证，给出纯 Java / Java 调 JS 的可行性结论。不能把 AGPL 脚本或限制分发的资源直接复制到新仓库。新增依赖先在本任务 handoff 提案，等待集成窗口更新公共 POM。真实网络验证只读接收，不发评论、不购买礼物。
验收：单个礼物至少 1、同组累计转增量、重复包不重发、不同用户同礼物不误合并；异常包不使整个连接退出；重连带退避和停止开关；关播 state=ENDED，stop 幂等；不记录 Cookie/签名 URL；真实收到与模拟事件分别报告。无法获取真实礼物时明确未验证，不宣称整个链路完成。

只能修改 docs/task-boundaries.md 指定的目录；公共文件改动写入 `docs/handoffs/collector.md` 提案，报告给用户，由当前集成窗口处理，不自行改协议或向别的任务发消息。

使用 scripts/dev-backend.sh / scripts/dev-frontend.sh 启动，它们会读取本工作树 runtime/worktree.env 并隔离端口与数据目录。每步改代码前简短说明，结束说明结果。不得批量删除，不回滚已有修改，不暴露密钥。

在自己的分支提交经过验证的小范围本地提交；不 merge 其他功能分支、不推送。完成后更新 `docs/handoffs/collector.md`，列出提交 SHA、实现内容、测试命令与结果、mock/真实验证区别、限制和依赖提案。在当前任务最终回复中报告这些内容，交由集成窗口合并。
