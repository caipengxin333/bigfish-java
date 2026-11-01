# 第六轮：故障诊断与运行状态一致性

日期：2026-11-01（Asia/Shanghai）。工作树 `bigfish-worktrees/backend`，分支 `feature/backend-core`，开发端口 18081。

## 基线与提交

- 开工实际 HEAD 为 `c3ffe6edc6d91bc7dec8e84e58e93e5e765b8390`，工作区干净。按本轮同步要求执行 `git merge --no-edit main`，快进至最新本地 main **`75dd3bcae38eb8d46f16bbab4c53e87e835d9bd2`**，无冲突，未覆盖或丢弃已有修改。
- 已阅读 AGENTS、task-boundaries、phase4-douyin-session、phase5-backend-lifecycle、phase5-integration、failure-summary-ui。主窗口采集根因优先摘要、同会话证据约束及音频中断释放播放端修补全部保留。
- 实现提交：**`0d950db52ee9f375a97af90f45aa42641c4336a5`**（`fix: preserve session root causes and consistent diagnostics`）。本文单独提交，其 SHA 在最终回复报告。
- 交付前再次核对 main，仍为 `75dd3bc`，ancestor 检查通过。没有向 main 合并，没有合并其他功能分支，没有推送、PR、stash、reset、批量删除或联系其他窗口。

## 实际修复

1. **会话终止根因优先。** 引擎在采集/运行/启动故障清理前固定本次终止原因及时间。随后模型 cancel、模型 close 或旧任务清理异常不能覆盖 `COLLECTOR_FAILED`，也不追加从属清理失败计数。`collector.lastError` 继续保留采集器固定细分码。用户随后显式 stop 可以转为 STOPPED，但历史根因不抹除；新会话才重置。
2. **读取已发生的采集终态时同步协调。** runtime/providers/bootstrap/diagnostics 使用同一 SessionService 本地协调路径，避免必须等下一次 250ms 定时协调才由 RUNNING 变为 FAILED。一次 runtime 响应使用一次采集状态观察，避免把之后的 FAILED 观察拼到之前的 RUNNING 快照。读取不会启动采集、模型、TTS、Token 或任何连接探测；可能执行已结束/失败会话的本地收尾，与定时协调语义相同。
3. **停止竞态保留历史原因。** 采集已经 FAILED、定时协调还没运行时，stop 先记录该终态，再按用户请求停止，保留 PAGE_STRUCTURE_CHANGED / COLLECTOR_FAILED；重复 stop 不重复计数或刷新首次记录时间。
4. **启动失败属于新尝试。** 引擎创建新 sessionId、模式和诊断上下文后再分配模型。分配失败记录 `PROVIDER_UNAVAILABLE`，不会沿用旧会话错误/任务记录。旧采集器通过 `ensureReleased` 后才清除旧 Run 引用；COLLECTOR_STOPPING 门禁不变。未通过 HTTP 校验或语音选择等前置检查时，还没有创建新会话，错误仍是该请求的失败，不能将原 runtime 当作一次新启动结果。
5. **主动取消不是服务故障。** CancellationException、InterruptedException、DeepSeek/NLS 的固定 CANCELLED 被记录为 `CANCELLED / PROVIDER_CANCELLED`，不增加 failures、不刷新 lastFailure/lastError。用已有单任务标记封闭该结果，后续释放回调不能再把它计作失败。实际模型/语音失败及整体超时仍计一次，保持 RUNNING 并允许后续任务恢复。

生产修改仅：`management/RuntimeEngine.java`、`management/SessionService.java`、`management/ManagementController.java`。

新增测试仅：`core/FailureConsistencyTest.java`（5 项）、`core/TaskFailureRecoveryTest.java`（6 项）。没有改前端、采集器解析/签名/协议、DeepSeek/NLS/Token 内部实现、数据库、依赖、Java 公共 DTO、OpenAPI 或线上播放消息格式。`SessionService.diagnostics()` 是内部 Java 读取方法，不是新 REST 接口。

## 验证与复现

| 检查 | 实际结果 |
| --- | --- |
| 首批故障回归（修复前） | 4/4 复现：采集根因变成 MODEL_CANCEL_FAILED；主动取消 failures=1；读取返回 RUNNING/collector FAILED；启动失败沿用旧 sessionId |
| 第一组修复回归 | 36/36 通过 |
| 加入模型/合成恢复验证 | 42/42 通过 |
| 立即 stop 的单项回归（修复前） | 复现 runtime.lastError 为空，丢失已发生的 COLLECTOR_FAILED；已补协调顺序 |
| **最终 `./scripts/maven.sh test`** | **232 项：230 通过、2 项真实 NLS/Token 默认跳过，0 失败、0 错误**；23:04:43 完成，包含上述最终修补 |
| 差异与范围 | `git diff --check`、暂存检查通过；未删除或弱化既有断言 |

针对性命令：

```sh
./scripts/maven.sh -B -ntp \
  -Dtest=FailureConsistencyTest,TaskFailureRecoveryTest,RuntimeLifecycleTest,SessionLifecycleTest,DouyinSessionTest,RuntimeStabilityTest,RuntimeDiagnosticsTest,PlaybackDiagnosticsTest,SessionApiIntegrationTest,DouyinSessionApiTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

本地替身与可控时钟验证了：PAGE_STRUCTURE_CHANGED 且无事件时状态/根因对应；有在途模型和播放任务时采集失败清理及迟到取消异常；模型/合成失败和超时后下一任务成功；取消、释放、同任务失败只计一次；主动停止与服务故障区分；停止重开后旧失败/成功回调不污染新会话。测试中的 DouyinCollector 只使用抛固定异常的本地 provider，没有真实 HTTP/WSS。

原有机制保留并通过：DOUYIN/MOCK 与房间隔离、旧采集器退出门禁、无 READY 不调用模型/TTS、断线不重启会话、租约/旧 epoch 隔离、音频撤销、PCM→WAV、STATIC/RAM Token、配置 CAS、队列容量/TTL、诊断 10,000 次输入压力及 30 分钟虚拟时间恢复测试。虚拟时间测试不等于真实云端长测。

日志均仅在本机：`/tmp/bigfish-phase6-before.log`、`/tmp/bigfish-phase6-focused.log`、`/tmp/bigfish-phase6-stop-before.log`、`/tmp/bigfish-phase6-full.log`。测试使用本工作树与独立临时数据目录；获准使用本机端口/JVM attach 后运行，没有复制主项目数据库或凭据。

## 现有字段的准确含义

各 HTTP 请求是独立快照，不保证跨请求事务一致。继续按 **sessionId + state** 匹配 runtime 与 diagnostics，过期响应不能覆盖新会话；capturedAt 为服务端取诊断快照时间。

| 字段 | 范围、含义与限制 |
| --- | --- |
| `runtime.sessionId / state` | 当前或最近一次引擎启动尝试；RUNNING 只代表业务引擎运行，不代表播放器 ready、采集收到消息或供应商成功。单任务失败一般仍 RUNNING；采集失败使会话 FAILED，关播使 STOPPED；用户显式 stop 后是 STOPPED |
| `runtime.lastError / lastErrorAt` | 当前会话最近保留的引擎错误及服务端记录时间，初始为空字符串。任务后续成功不清除历史错误；一旦发生会话终止故障，则固定终止根因和时间，清理错误不得覆盖。新会话重置；不是活跃故障布尔值，也不是进程级历史列表 |
| `collector.state / lastError` | 当前/最近采集 Run 的状态与固定最近错误。FAILED/ENDED 在自动清理后保留；显式 stop 转 STOPPED。错误码可能是重连前的历史错误，不能仅凭非空错误码把 CONNECTED/RECONNECTING 宣判为失败。新 Run 重置；启动尚未创建采集器时为 STOPPED、空码 |
| `collector.received` | 当前/最近 Run 的归一化尝试数，可包含未知、坏输入与重复；不是有效评论数，更不是礼物数或播放数 |
| `collector.emitted` | 当前/最近 Run 向 sink 派发并正常返回的次数；不保证引擎接受、模型生成或播放成功 |
| `collector.rejected` | 当前 Run 的引擎拒绝数（ACCEPTED/DUPLICATE 除外）；不能简单用 emitted−rejected 推导有效任务数，存在去重、欢迎跳过等不同处理 |
| `collector.duplicates` | 采集器自身去重/连击抑制计数，与引擎任务去重不是同一计数 |
| `diagnostics.lastFailure` | 当前会话最近一个任务的 FAILED 轨迹，最多保留 5 分钟；是历史任务结果，不代表整个会话 FAILED。采集、启动故障未必有任务，因此可为 null；正常取消/过期不更新它。后续成功不立即清除它 |
| `diagnostics.recent` | 当前会话最近最多 20 条阶段变化，按 sequence 排序；不是全部任务或全部失败列表，不能用长度推算任务/失败累计。相同 taskId 可有多条阶段记录 |
| `lastModelSuccessAt` | 当前会话最近一次有效非空模型结果完成阶段的服务端时间；不表示合成或播放成功，不由模型目录检查更新 |
| `lastSpeechSuccessAt` | 当前会话最近一次语音阶段完成的时间：音频已通过引擎时长检查、读取与 release，且仍属于有效任务；不表示浏览器已取得或播放音频 |
| `lastPlaybackSuccessAt` | 当前会话有效租约下，同一任务先 STARTED 后 COMPLETED 的服务端处理时间；忽略客户端自报 occurredAt。是回执证据，不是服务器直接测量物理声音 |
| 三个成功时间的保留 | 每个只存一个时间，当前会话内保留，可能比 recent 的 5 分钟窗口更早；新会话重置为 null。null 表示该会话没有记录到对应完成阶段，不等于供应商配置失效 |
| `runtime.failures` | **进程累计**的引擎失败计数，不随新会话归零：模型/语音任务失败按任务去重，另含采集/运行/引擎启动故障及无终止根因时的取消清理异常。正常取消不计；已有终止根因后的从属清理不计。播放器 FAILED/ACK 超时记录在任务诊断，未汇入此计数，因此它不是全链路失败总数 |
| `runtime.dropped` | 进程累计的调度丢弃/繁忙跳过等计数，不是所有非法输入、取消或供应商失败总数 |
| `taskEvictions / historyEvictions` | 当前会话因容量上限而淘汰的计数；按时间保留期清理不会累加这些字段，不能据此恢复全部历史 |

错误记录有界：引擎仅保留一个固定码/时间及一个终止优先标记；采集器仅保留固定最近错误和有限枚举计数；任务表最多 512、内部轨迹最多 1024、保留 5 分钟，公开轨迹最多 20。latest failure 最多一个并按 5 分钟清理；成功时间各一个。没有新增无界 Map、队列或异常对象存储。

## 当前前端能可靠展示什么

- 对本轮真实案例对应的**本地注入结果**：runtime FAILED、collector FAILED/PAGE_STRUCTURE_CHANGED、runtime.lastError=COLLECTOR_FAILED，计数均 0，匹配会话的任务轨迹和成功时间为空。可展示“采集失败导致互动/播放停止”；不应展示“模型/语音也发生故障”。本轮没有重新做真实探测。
- 配置/开关、CATALOG_CHECKED 只是准备条件或目录检查结果，不更新本次任务成功时间。必须保留主窗口按当前会话证据汇总的规则。
- 模型/语音异常和整体超时使用 FAILED，停止/播放器变化/主动取消使用 CANCELLED，队列/TTL 丢弃使用 DROPPED 等，固定 reason 可辅助解释。不能把所有未 COMPLETED 轨迹算为服务异常。
- 播放当前可可靠区分客户端 `PLAYER_FAILED`、服务端 START_ACK_TIMEOUT/COMPLETION_TIMEOUT、PLAYER_CANCELLED、PLAYER_DISCONNECTED/LEASE_EXPIRED 等。客户端原始 errorCode 不入诊断；下载失败、解码失败、被浏览器阻止目前都可能合并为 PLAYER_FAILED，管理页不能自行猜具体原因。
- 若同一任务的前序阶段还在 recent 中，可按 taskId/sequence 确认失败发生环节；同一会话、已确认环节的成功时间严格晚于失败时间，可展示“之后该环节有成功记录”。不能因此断言整条链路或所有供应商均健康。
- 若前序轨迹已被裁剪、失败已经超过保留期、成功与失败时间相同、出现跨环节失败，现有字段只能可信地展示“历史故障”和“最近成功记录”，不足以无歧义声明“故障已恢复”。禁止解析错误字符串前缀来猜环节；不能把 lastFailure=null 或 lastError 非空分别直接当成健康/正在失败。

## 最小公共契约提案（未实现，交主窗口统一维护）

针对“最近一次任务故障是否在同环节已有后续成功”，建议只为 `RuntimeDiagnosticsView.lastFailure` 的响应投影增补：

```text
phase: MODEL | SPEECH | PLAYBACK
recoveredAt: ISO-8601 timestamp | null
```

- phase 由服务端在失败发生时记录，不能由 UI 推断；现有 reason（例如 RATE_LIMITED、OVERALL_TIMEOUT）可能被多个环节使用，只有 stage=FAILED 本身无法识别环节。
- recoveredAt 仅当**同一会话、同一环节、另一任务成功的服务端 sequence 晚于该失败**时设置；时间戳相等也可有确定顺序。它表示这次历史失败之后有成功证据，不表示正在处理的任务不会再失败，也不代表所有环节均恢复。
- 继续最多保留一条 lastFailure，沿用 5 分钟期限；新增字段按可选处理，旧客户端继续用原字段展示。Java/TypeScript/OpenAPI 由主窗口同步，不创建新接口。采集终止根因仍使用 runtime/collector，不伪造 taskId 填入任务 lastFailure。
- 若将来必须同时回答三个环节各自是否恢复，再另行批准最多 3 项的结构化环节结果；不能从单个全局 lastFailure 承诺全链路健康。
- 播放细分类别可另行确认现有 ACK.errorCode 的**固定白名单映射**到诊断 reason（如下载/解码/播放被阻止）；无需改线上消息格式，未知值继续归 PLAYER_FAILED，绝不透传任意客户端内容。本轮未扩展该枚举或公共字段。

## 剩余待办与真实服务边界

PAGE_STRUCTURE_CHANGED 的页面解析适配仍由采集器窗口处理；本轮没有改解析器、签名或采集协议。真实抖音 HTTP/WSS、DeepSeek、NLS、Token 签发请求均为 **0**。未停止主项目/其他窗口进程，未复制配置、数据库或密钥。

主窗口后续联调应保留 failure-summary-ui 的采集根因优先、同 sessionId/state 校验和默认折叠技术详情；按本表区分当前状态与历史结果。公共恢复字段批准前，显示有限证据即可，不用前端猜测内部状态。本轮诊断回归通过不等于真实抖音接收、浏览器物理发声或云端长期稳定性验收。
