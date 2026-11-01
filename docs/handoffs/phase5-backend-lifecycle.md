# 第五轮：一键互动的会话与播放生命周期

日期：2026-11-01（Asia/Shanghai）。工作树：`bigfish-worktrees/backend`；分支：`feature/backend-core`；开发端口：18081。

## 基线与提交

- 开工实际 HEAD：`94c0fefc8332b8757fcf843f2147a20ed2187342`，工作区干净，上一轮成果完整保留。
- 开工及交付前核对最新本地 main：`03e26b619715e68c9de00294d753eaa481dad31c`。`git merge-base --is-ancestor main HEAD` 均返回 0，已经包含该基线，本轮无需执行合并。
- 播放隔离修复：`009d6cc2bed59676da2800aa34b2a02c1e95cf9c`（`fix: fence playback leases until transport closure`）。
- 会话清理修复：`3728d59ac2c54c3975c9f31f6b4d1c1b582f5e5d`（`fix: complete session cleanup despite cancellation failures`）。
- 本交接文件另行本地提交，文档 SHA 在最终回复报告，不在文档内自引用。
- 没有修改/合并 main，没有合并其他功能分支，没有推送、PR、reset、stash、批量删除或联系其他窗口。没有停止主项目或其他窗口的进程。

## 实际修复与复现依据

1. **关闭中不能提前释放独占租约。** 原 StageSocketHandler 在协议错误、传输错误、writer 退出时先调用 disconnected，再实际关闭 socket，窗口内第二端可以获得 PLAYER_LEASE。新增内部 `disconnecting(Peer)` 只撤销当前租约/任务，保留旧租约隔离；框架关闭回调或确认 `socket.isOpen()==false` 后才清除隔离。原租约到期仍允许新连接，不延长 15 秒期限。
2. **旧连接回调不能作用于复用 ID 的新连接。** PlaybackCoordinator 的拥有者/退役连接检查改为对象身份，仍同时校验 sessionId/playerId/leaseGeneration。StageSocketHandler 查找消息、错误及关闭回调时校验 WebSocketSession 对象，移除使用 compare-and-remove；活跃的相同 transport ID 不允许注册覆盖。旧 READY/心跳/ACK/关闭/writer 回调不能匹配新端。
3. **关闭失败不突破连接容量。** close 幂等；如果底层关闭失败且 socket 仍打开，保留原连接槽位直到框架报告关闭，最多 8 个连接、每端 16 帧出站队列不变。不用提前删句柄或无限重试掩盖未退出的连接。
4. **采集启动部分失败先关闭主链路。** SessionService 在 collector.start 或工厂抛出异常时，先 engine.collectorFailed，关闭入口/模型/TTS/播放，再执行有界采集退出等待。原测试复现退出等待中主会话仍为 RUNNING；修复后等待时已为 FAILED，未释放的 Handle 仍阻止新采集器启动（COLLECTOR_STOPPING）。
5. **取消异常不截断停止。** RuntimeEngine 对端口 cancel 的 RuntimeException 记录固定 `MODEL_CANCEL_FAILED` / `SPEECH_CANCEL_FAILED`，继续取消 Future、清队列、回收音频和撤销播放。每个任务用已有失败标记去重；旧 session 的迟到异常不覆盖新会话。工作线程先记录原任务错误，再尝试 cancel，避免取消错误掩盖原失败。没有记录原始异常内容或新增公共字段。
6. **部分启动分配的模型会清理且只关闭一次。** 后续启动步骤失败时执行 stop 清理已取得的模型。关闭时移除持有引用，FAILED 后再次停止不重复 close；测试先复现 close 计数为 2，再验证为 1。语音端口仍由原供应商生命周期管理，不改 NLS/Token 实现。

生产修改仅四个文件：

- `backend/src/main/java/cn/bigfish/management/SessionService.java`
- `backend/src/main/java/cn/bigfish/management/RuntimeEngine.java`
- `backend/src/main/java/cn/bigfish/playback/PlaybackCoordinator.java`
- `backend/src/main/java/cn/bigfish/playback/StageSocketHandler.java`

新增测试：`core/SessionLifecycleTest.java`（2 项）、`core/RuntimeLifecycleTest.java`（4 项）、`core/PlaybackLifecycleTest.java`（2 项）、`core/StageSocketLifecycleTest.java`（2 项）。增强已有 `core/StageWebSocketIntegrationTest.java`，确认断线后 sessionId 不变、会话 RUNNING、采集仍 CONNECTED、播放器未就绪，再重连播放新任务。

## 已有机制保留与验证

| 要求 | 验证结果 |
| --- | --- |
| 并发/重复启动 | 8 个并发请求只有 1 个成功、工厂仅创建 1 个采集器，其余保留 409 SESSION_ACTIVE；没有把重复开始当重启 |
| 重复停止/旧会话隔离 | 8 个停止请求稳定返回 STOPPED；旧 ID 无法停止新会话；部分失败资源未释放仍返回 COLLECTOR_STOPPING |
| 无 READY 不调用模型/TTS | 断线、仅 HELLO 未 READY 时突发评论/礼物，端口调用为 0；队列不超过现有容量，推进本地时钟后按 TTL 丢弃；有效 READY 后恢复 |
| 连接生命周期独立 | 实际本机 WebSocket 断开后仍为同一 RUNNING 会话、同一采集器；重连拿新 generation，旧音频 URL 已 404，新任务可以播放 |
| 独占与迟到回调 | 第二端拒绝抢占；关闭实际完成前保持隔离；对象 ID 被复用后旧 READY/错误/关闭回调无效；旧 ACK/epoch 不能完成或投递新任务 |
| 心跳/租约 | 保留 5 秒心跳建议、15 秒租约；错误 session/player/generation/连接身份不能续租；到期 READY/心跳不能复活旧租约 |
| 停止竞争 | 20 轮本地并发 stop/READY/心跳/COMPLETED/断线，最终 connected/ready/playing 均 false、音频与播放队列为空 |
| 模型/TTS 迟到 | 停止时并发模型等待与语音 release；停止重开后放行旧完成回调，旧文本/音频不进入新会话、诊断不污染，新任务正常恢复 |
| 上一轮 DOUYIN | 房间绑定、配置变更不串房、独立模式选择、禁止 simulate/replay、ENDED/FAILED 清理与旧 Handle 门禁的现有测试全部保留并通过 |
| 其他回归 | 配置持久化/CAS、PCM→WAV、STATIC/RAM Token、模型期限、队列/诊断容量、唯一播放端与音频回收均通过 |

未重写调度、采集或供应商适配器。没有新增自动重连会话、自动重启、后台标签页宽限或伪造 READY。

## 测试命令与实际结果

针对性测试：

```sh
./scripts/maven.sh -B -ntp \
  -Dtest=StageSocketLifecycleTest,SessionLifecycleTest,RuntimeLifecycleTest,PlaybackLifecycleTest,PlaybackCoordinatorTest,StageWebSocketIntegrationTest,DouyinSessionTest,RuntimeStabilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- 初次新增回归：5 项中 4 项失败，分别复现提前发租约、旧连接控制新端、取消异常中断停止、部分启动清理顺序错误；并发启动已通过。
- 修复后第一组：23 项通过。补充测试时修正一处测试代码编译问题（RuntimeEngine 非 AutoCloseable，改为 finally 显式 close），没有修改生产接口迁就测试。
- 扩展针对性测试：**31/31 通过**。随后增加失败后重复 stop 的单项断言，先复现 close=2，再修复引用清理。
- 最终完整命令 **`./scripts/maven.sh test`**：**221 项，219 通过、2 项真实 NLS/Token 实测默认跳过，0 失败、0 错误**。2026-11-01 20:37:07 完成；包含最终引用清理改动及上述所有针对性测试。
- `git diff --check` 与暂存 diff 检查通过。测试使用本工作树 Maven 与隔离临时 SQLite/音频目录，真实 WebSocket 测试仅监听随机本机端口，不接触主项目数据库/密钥。

本机日志未提交：`/tmp/bigfish-phase5-regression-before.log`、`/tmp/bigfish-phase5-focused.log`、`/tmp/bigfish-phase5-close-before.log`、`/tmp/bigfish-phase5-full.log`。运行时已允许测试需要的 JVM attach/本机端口权限，未删除环境相关断言。

## 兼容性与前端待联调

**REST 路由、请求字段、Java 公共 DTO、OpenAPI、WebSocket envelope/消息格式、数据库和依赖均未改。** 不新增“启动并播放”接口；MOCK/DOUYIN 来源和 conversationMode/speechMode 独立选择保持原样。新增 disconnecting 仅为 Java 内部方法。

一键流程仍为：本地音频解锁 → 查询 runtime → 无活动会话才 POST sessions → 接入同一个 sessionId → PLAYER_HELLO → PLAYER_LEASE → PLAYER_READY。如果并发页面都看到无会话，其中一个收到 SESSION_ACTIVE 后应重新读取 runtime 并接入，不能反复 stop/start。

停止仍为本地先静音，再 POST `/sessions/{sessionId}/stop`，可对同一已停止 ID 重试。旧 ID 收到 SESSION_MISMATCH 后重新获取 runtime，不应替用户停止新会话。若旧采集器尚未释放，COLLECTOR_STOPPING 表示等待资源退出，不应自动换模式或重启服务。

状态解释：

- RUNNING 是业务会话存在，不等于 player.ready，也不等于 Douyin 接收成功。CONNECTED/READY/PLAYING 来自当前有效租约，终止后不会保留虚假 true。
- `player.connected=false` **不保证此刻立刻可获租约**：旧传输仍在关闭且原租约未到期时，仍返回 PLAYER_REJECTED / PLAYER_ALREADY_ACTIVE。等待关闭确认或原租约自然到期后，按原 HELLO 流程重连，不绕开独占规则。
- 新连接必须重新 HELLO/READY；完成、取消、过期音频不重放。前端普通切换标签页可以保留连接，但冻结/丢失心跳后仍按真实 15 秒期限撤销，后端不推测浏览器后台状态。
- 新固定错误码 MODEL_CANCEL_FAILED/SPEECH_CANCEL_FAILED 使用已有 runtime.lastError 字符串；允许前端显示安全的通用清理失败提示。停止仍继续完成，失败计数按任务去重。
- 上一轮 `phase4-douyin-session.md` 的来源选择与 DOUYIN runtime 字段映射提案仍需主窗口统一处理，本轮未越界更新前端/公共契约。

可选公共字段提案（未实现、非本轮合并前提）：若页面必须区分“当前有人占用”与“旧连接正在释放”，由主窗口统一增加 `RuntimeView.Player.releasing: boolean`，定义为无当前 player 且旧传输关闭未确认、原 lease 尚有效。现有格式下只能根据 PLAYER_REJECTED 与 runtime 提示等待重连；不能把 connected=false 当作抢占许可。

待前端/主窗口验证：真实浏览器点击后的 AudioContext 解锁、管理页与舞台的一键接入、标签切换/浏览器冻结恢复、后台音频静音、停止响应丢失时重试、跨页面争抢提示。后端测试不能替代浏览器音频权限和物理声音的实际验收。

## 真实服务边界

本轮真实抖音 HTTP/WSS、DeepSeek、NLS/CreateToken 调用均为 **0**；没有运行任何真实 probe 或收费测试。上一轮 PAGE_STRUCTURE_CHANGED 属于采集解析问题，未修改解析器/签名/协议，不因本轮生命周期测试通过而改变其“真实评论/礼物接收未验证”的结论。
