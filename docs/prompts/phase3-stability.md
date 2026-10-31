# 第三轮任务一：后端运行诊断与故障恢复

你继续负责 BigFish Java 后端。本轮已有 DeepSeek、阿里云 NLS 和模拟事件的基本联调成功，目标是定位“为什么不说话”、证明失败后下一任务能恢复，避免长时间运行后积压。直接实施，不重建框架，不把已有适配器再实现一次。

## 工作目录与同步

工作目录：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/backend`。
分支：`feature/backend-core`，端口 18081/15174。

1. 检查 pwd、git branch --show-current、git status --short。保留已有修改；不要自动 reset、stash 或覆盖他人工作。
2. 工作区干净后执行 `git merge --no-edit main`。阅读最新 AGENTS.md、docs/phase3-plan.md、docs/handoffs/phase2-integration.md、docs/openapi.yaml、docs/playback-protocol.md 与本文件。
3. 基线应包含 `c8be17c` 和 `364d620`；它们之后若有 main 提交也要同步。每步修改前简述内容，完成后说明变化及验证。禁止批量删除。

## 任务

1. 先审计当前 RuntimeEngine、DeepSeekConversation、调度和播放协调器已有的截止时间、队列、取消机制。已有且验证充分的实现保留，只补实际缺口。
2. 增加有界的内部诊断：按 sessionId/taskId 关联事件接收、模型开始/完成、合成开始/完成、等待播放、STARTED/COMPLETED、丢弃/取消/失败。记录阶段耗时、固定原因码、最近成功时间；不存评论正文、昵称、密钥、原始异常或 HTTP 数据。诊断条数、保留时间和任务表必须有上限，旧会话迟到结果不能污染新会话。
3. 给主窗口提供只读诊断快照及接线示例，让前端可以区分：没有消息、播放端未就绪、模型等待、语音等待、等待播放、限流/超时、任务过期。不要在本分支新增公共 REST/DTO 或自行改 RuntimeView；新增内部方法尽量保持现有调用兼容。
4. 验证礼物模板不等待模型，评论、礼物、关注、入场的既有优先级/合并/TTL/繁忙跳过规则有效。排查阻塞、资源泄漏、重复失败计数或取消后的迟到任务。必要修复必须有可重现的回归测试；不要用无限重试、清空全部任务、自动重启服务或无声切换测试音来掩盖失败。
5. 增加可重复的本地持续运行/故障恢复测试：消息突发、慢/永不完成的模型或 TTS、429/5xx、播放端断线与重新连接、停止重开、旧任务回调。用本地替身与可控时钟加速覆盖超过原先 20 分钟沉默场景的状态变化；明确加速测试不等于真实 20 分钟云端压测。
6. 给出真实长测的步骤、观测指标、停止条件和费用估算方法，但本轮不自动运行收费长测。已有真实调用正常时无需反复付费验证；如确有协议修复需要，按既有最多 3 次短请求规则执行并记录。

## 修改范围

可修改 conversation/event/scheduler/playback 包、management/RuntimeEngine.java、新增 management/diagnostics 包、相应 conversation/core 测试，及 docs/runtime-stability.md、docs/providers/deepseek.md、docs/handoffs/phase3-stability.md。
不改 speech、collector、configuration、SessionService、ManagementController、contract、Vue、POM、OpenAPI。公共需求写具体提案，由主窗口统一接线。不要联系其他聊天或停止 main 的进程。

## 验证与交付

- 默认测试只用本地替身；证明队列/线程/诊断存储有界，正常任务可在故障后恢复，停止后不继续播放。
- 运行 `./scripts/maven.sh -B -ntp test`；失败先区分沙箱端口/JVM attach 限制与代码问题，不删除断言。
- 交付前同步最新 main，提交本地修改，写 docs/handoffs/phase3-stability.md，列出基线/实现 SHA、文件范围、实际测试结果、诊断读取示例、公共字段提案和未验证项。
- 不合并其他功能分支、不更新 main、不推 GitHub、不创建 PR。最终回复给出提交 SHA 和交接文件绝对路径。
