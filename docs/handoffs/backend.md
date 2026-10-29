# Java 后端核心交接

任务/分支：后端核心 / feature/backend-core
基线：a3ac3acdcef2f185df39e0dc6d7819a36b0628f1
最终实现提交：cec2726578058dd978777716e65634abe60bf42b
交接日期：2026-10-30；测试与启动验收日期：2026-10-29。
状态：后端核心与 mock 端到端已本地实现、验证并提交；管理 REST 接线等待公共 schema。没有修改公共契约、POM、主集成工作区或旧项目，没有推送、合并或联系其他任务。

## 本地提交

1. `dd2271bd37f623ef34657443d99ca969467f6742`：配置快照、事件校验、容量/TTL 去重、有界优先队列、礼物模板、基础测试与接口提案。
2. `166c07ff8898d7a20004b865ad440665f97da14e`：显式启用的 DeepSeek OpenAI 兼容流式适配；整体截止时间、取消、HTTP 连接释放、错误冷却；本地 SSE 故障测试。
3. `cec2726578058dd978777716e65634abe60bf42b`：mock 会话生命周期、异步调度、配置快照应用、WAV 测试适配器、WebSocket 租约、音频接口及端到端测试。

本交接文档在上述实现提交之后单独提交；文档提交 SHA 见当前聊天最终回复，或 `git log -1 --format=%H -- docs/handoffs/backend.md`。集成时合并整个 feature/backend-core 分支，不要只取最后一笔文档提交。

## 实际实现

- `RuntimeEngine`：STOPPED → STARTING → RUNNING → STOPPING → STOPPED，初始化/调度异常进入 FAILED；不退出 Spring Boot。内部入口 `startMock()`、`startMock(boolean useDeepSeek)`、`stop(sessionId)`、`status()`；实现现有 `EventIngress`。会话默认 MOCK、房间固定 mock-room；不实例化真实采集器。
- `ConfigStore`：保留 SQLite CAS 持久化，增加已持久化配置的内存快照。事件接收时捕获不可变 ConfigSnapshot；保存与应用版本分别记录。旧任务继续使用旧版本的 persona/model/voice/readComment；配置保存不会改变已提交任务。
- `EventValidator` / `RecentEvents`：类型字段白名单、整数与长度约束、昵称清理、复制 payload；TTL 使用服务端接收时间。每会话 eventId 去重，5 分钟 / 4096 项上限，停止时清空。
- 调度：评论、模板、合成三条有界队列；每条使用 synthesisQueueCapacity（默认 8），待播放队列使用 playbackQueueCapacity（默认 8）。同级 FIFO，满队列淘汰最低优先级中最旧的条目；低于队内最低优先级的到达项返回 REJECTED_FULL。每条队列独立计算容量，模型与合成各最多 1 个活动任务，执行器不积压任务。
- 礼物走模板，评论走 ConversationPort；150ms 内按用户/giftId/comboId/配置版本合并已去重的增量。慢模型不会占用礼物通道。FOLLOW/ENTRANCE 共用欢迎间隔，忙时跳过；LIKE 接受但不发声。被限频欢迎返回 ACCEPTED，并增加 dropped 计数。
- 模型整体 20s、合成整体 5s，均受事件 TTL 限制；取消、停止、过期、播放器代次变化阻止迟到回调入队。未准备好播放器时暂停模型/合成，队列继续受容量及 TTL 限制。断线撤销已有待播工作；重连不自动重播已派发任务。
- `MockConversation` 返回带 `[模拟回复]` 标识的确定性文字；`LocalTestSpeech` 生成可重复的 300ms / 16kHz / 单声道 WAV 测试音。所有任务复用工作树 audio 目录中的一个测试文件，避免音频磁盘无限增长；它不是可理解的人声 TTS。
- `/ws/stage`：PLAYER_HELLO、PLAYER_LEASE、PLAYER_REJECTED、PLAYER_HEARTBEAT/HEARTBEAT_ACK、PLAYER_READY、PLAY_TASK、PLAYBACK_ACK、CANCEL_TASK、SESSION_STOPPED。5s 心跳 / 15s 租约；连接 ID、playerId、sessionId、leaseGeneration 联合校验；同 playerId 的另一个连接也不能抢租约。
- READY 之前不下发，每次只有一个未完成任务；STARTED 超时 5s，完成超时 30s，重复 STARTED 不延长截止时间。超时关闭播放端；撤销后等待旧连接确认关闭或旧租约到期才允许接管，避免两个有效播放端同时发声。
- WebSocket 最多 8 个连接，每连接出站最多 16 帧、输入最多 8192 字节 / 每秒 30 帧；10s 未 HELLO 关闭。发送由专属 writer 处理，事件入口不执行网络发送。使用 Spring 默认同源限制。
- `/api/v1/audio/{assetId}`：仅 UUID 形式的注册 ID；同源、audio/wav、no-store、nosniff；不接收文件路径。文件限制在本实例音频目录，读取最多 2MB；注册表最多 128 项。完成/断线/停止/过期撤销资源，返回 404。
- `DeepSeekConversation`：使用现有 Spring AI 1.1.8 的 OpenAiApi，无新依赖；固定官方 HTTPS 地址，不接受前端提供的 endpoint。只有显式 `startMock(true)`、`BIGFISH_ALLOW_PAID=true` 和 `BIGFISH_DEEPSEEK_API_KEY` 三者齐备才可发出真实请求。Key 不进入 SQLite/配置响应/日志/提示词/测试夹具。每次请求独立持有 HTTP client，连接超时 3s，整体超时不会被 SSE 心跳或 token 重置；取消同时关闭底层 client。失败冷却 5s，无自动重试或隐式切换付费服务。
- bootstrap 仍为 SCAFFOLD；capabilities 标记 mockMode/localTestSpeech/conversation/playback，speech=false、douyinCollector=false。完整管理 REST 未接线前不宣称 UI 可直接启动会话。

修改目录：backend/src/main/java/cn/bigfish/{configuration,event,scheduler,conversation,speech,playback,management}；backend/src/test/java/cn/bigfish/core；docs/handoffs/backend.md。原 ManagementIntegrationTest、contract、公共协议、前端、脚本和依赖版本未改动；无文件删除。

## 运行与验证

- `./scripts/maven.sh test`：原有 5 项基线测试通过。
- `./scripts/maven.sh -B -ntp test`：最终 **26 项通过，0 失败、0 错误、0 跳过**；包括原有配置持久化、CAS、非法配置及不接受凭据字段的 5 项测试。
- 新增测试分布：IngressPrimitivesTest 4、RuntimeEngineTest 7、PlaybackCoordinatorTest 4、DeepSeekConversationTest 4、StageWebSocketIntegrationTest 2。
- 针对性验证：10,000 条事件洪泛时队列不超过 8；同级 FIFO/优先级保护/TTL；去重缓存上限；礼物重复事件去重及增量合并；慢模型不阻塞礼物；旧任务配置版本固定；忙时/间隔欢迎限频；旧会话 stop/event 隔离；模型与合成截止时间取消；迟到模型输出不入新播放器。
- SSE 测试只连接临时本地 HTTP 服务器：正常多 chunk + DONE、持续 keep-alive、持续 token、主动 cancel。整体超时与 cancel 不输出迟到回复，服务器确认连接断开。曾发现仅取消 Reactor future 未及时关闭 SSE 连接的问题，已通过每请求独立 client + shutdownNow 修复，测试通过。
- WebSocket 测试使用 JDK HttpClient 真实连接随机本地端口的 Spring Boot：READY 门控、评论/礼物分别到达、WAV 下载并检查 RIFF、STARTED/COMPLETED 推进、第二播放端拒绝、断线/停止撤销音频、重连不重播。无需前端页面完成即可复现。
- `env -u BIGFISH_DEEPSEEK_API_KEY -u BIGFISH_ALLOW_PAID ./scripts/dev-backend.sh -B -ntp`：正常启动。实际监听 127.0.0.1:18081；打开的 SQLite 为本 worktree 的 runtime/app.db，音频为 runtime/audio/local-test-tone-v1.wav。
- `curl --fail --silent --show-error http://127.0.0.1:18081/actuator/health` 返回 `{"status":"UP"}`；bootstrap 返回 STOPPED / SCAFFOLD / mockMode=true / localTestSpeech=true。验收进程已通过原终端 Ctrl-C 停止，18081 已释放。
- `git diff --check` / `git diff --cached --check` 通过；变更清单检查均在授权目录内，未提交数据库、音频、缓存或密钥。未改前端，因此未运行前端构建。
- 环境限制记录：首次 Maven 因沙箱 DNS 失败，原集成测试在沙箱内也因 JVM agent 附加失败；授权后在沙箱外以同一脚本运行通过。本机服务的 curl 亦需沙箱外访问。这些没有通过改依赖/禁用断言规避。

## mock 与真实验证范围、限制

- 已验证：mock 业务链路、真实 WebSocket/HTTP 本地传输、Spring AI 对本地兼容 SSE 服务器的正常返回/超时/取消。没有访问真实 DeepSeek，没有消费模型额度，没有真实 TTS、抖音连接或浏览器实际出声验证。
- 当前没有 sessions/runtime/events/simulate 的 HTTP 实现；前端启动按钮需集成窗口先批准下方 schema 再接线。Java 内部服务不是新的公共 DTO；不要直接把内部 record 当正式 REST schema 固化。
- 真正的人声合成、音色选择效果、前端自动播放许可/音量嘴型/字幕渲染尚未验证；本地测试音忽略文本和音色。真实服务接入需要按下方提案选型。
- 凭据只从进程环境读取；尚无安全落库、在线验证、热轮换或管理界面。切换真实服务只在新会话时进行。roomId 为 mock-room，未实现真实房间切换或 CollectorPort 生命周期绑定。
- 去重有意限制为 4096 项 / 5 分钟；超出窗口的重放不能保证永远去重。事件 ID 正确性和累计礼物转增量仍由采集器遵守现有契约。
- 队列、超时、欢迎与礼物合并阈值仍采用固定初值/现有配置。断线选择丢弃已派发和旧代次待播任务以防重复，不做重放恢复；不存在跨进程会话持久化。失败不暗中回退为 mock 或其他付费供应商。
- 默认日志只记录错误类型、内部任务/会话 ID、耗时；不输出模型原始异常消息或业务 payload。不要启用 HTTP wire debug 来记录凭据。

实现核对依据：固定依赖的本地 Spring AI 1.1.8 源码；[DeepSeek OpenAI 兼容调用](https://api-docs.deepseek.com/quick_start/agent_integrations/)、[SSE keep-alive 说明](https://api-docs.deepseek.com/quick_start/rate_limit/)、[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)。这些只用于格式核对，不代表真实服务验收。

## 公共接口提案（待集成窗口确认）

当前仅实现已有播放协议和内部 Java 会话/模拟入口。以下管理 REST 需集成窗口先更新 OpenAPI 与公共 DTO，再接线，避免前后端自行定义不同字段。

- POST /api/v1/sessions：请求 `{mode:"MOCK", conversationMode:"MOCK"}`；默认 MOCK。显式 DEEPSEEK 还须服务器允许付费且配置环境凭据。DOUYIN 本任务拒绝。返回 sessionId、mode、conversationMode、speechMode（LOCAL_TEST_TONE）、state、roomId、savedConfigVersion、appliedConfigVersion。已有运行会话返回 409。
- POST /api/v1/sessions/{sessionId}/stop：幂等；旧 ID 不停止新会话；返回对应会话的停止结果。
- GET /api/v1/runtime：上述状态、各队列深度/容量、模型/合成活动数、播放端租约状态、累计丢弃/失败计数；不含凭据、供应商错误原文、用户内容或本地路径。
- POST /api/v1/events/simulate：仅 MOCK；请求复用 LiveEvent v1，服务端覆盖 receivedAt；返回 EventIngress.Result。建议 202 ACCEPTED，409 REJECTED_SESSION，429 REJECTED_FULL，400 INVALID，200 DUPLICATE。
- GET /api/v1/audio/{assetId}：按已约定播放 URL 提供 audio/wav，Cache-Control: no-store；仅当前会话且未过期资源；未知/旧/过期 ID 404，禁止路径访问。请在 OpenAPI 补登记。
- 保留 bootstrap.mode=SCAFFOLD，直到上述管理入口获确认；以现有 capabilities 布尔字典标记 mock/localTestSpeech，不将测试音误报为真实 TTS。
- PLAYER_READY payload 在原文未明确，建议确认与 heartbeat 一样携带 playerId、leaseGeneration；实现按此校验。

## 调度与依赖提案

- 事件 TTL 暂固定：评论 60s，礼物 120s，欢迎 40s；去重 5min/4096 项；模型整体 20s、合成整体 5s；单模型与单合成并发；礼物合并窗口 150ms；欢迎忙时跳过。后续可由集成窗口增加正式配置字段。
- GIFT.quantity 按 event-schema 已定义为增量，后端不会再次将其当累计值相减；累计转增量由采集器负责。后端按事件 ID 去重，按用户/giftId/comboId 合并增量。
- 不新增依赖。DeepSeek 使用现有 Spring AI；真实 TTS 供应商、凭据安全存储/轮换接口待选型。第一版只生成确定性的 WAV 测试音，不宣称是可理解的人声。

## 集成步骤（由集成窗口执行）

1. 阅读并确认上述管理 REST、PLAYER_READY 和配置扩展提案，先更新公共 OpenAPI/DTO/播放协议需要补充的说明。不要调整现有事件 quantity 的增量含义。
2. 在主集成窗口合并 feature/backend-core 全部分支提交；本窗口没有执行 merge/rebase/cherry-pick/push。
3. 将正式管理接口委派给 RuntimeEngine：默认 startMock()，stop 必须携带 sessionId，simulate 复用 EventIngress.accept。真实 DeepSeek 请求必须保留明确付费选择及服务端开关。当前状态/统计从 status() 映射到经确认的公共 schema。
4. 前端按现有协议连接 /ws/stage；每页面实例唯一 playerId，启用声音后带租约代次发送 READY，按实际声音开始/结束 ACK；断线、租约到期、CANCEL_TASK、SESSION_STOPPED 立即停音。开发代理应支持同源 /ws/stage 与 /api/v1/audio。
5. 合并后重新执行后端测试与前端构建，使用集成 worktree 自己的脚本/端口/数据目录做联合验收。真实模型与真实 TTS 另行授权、配置、验证，不将本地 SSE/WAV 测试算作真实服务验收。
