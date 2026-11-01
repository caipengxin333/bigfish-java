# 运行诊断与故障恢复

更新于 2026-11-01。本轮在既有调度、DeepSeek、NLS 和播放链路上增加内部诊断，不新增 REST/公共 DTO，不改变默认服务选择。真实服务基本联调结果沿用 [第二轮集成交接](handoffs/phase2-integration.md)；本轮只运行本地替身，不重复付费验证。

## 先定位停在哪个阶段

主窗口可调用 `RuntimeEngine.diagnostics()` 获取只读 `DiagnosticsView`。轮询此方法不会触发模型、TTS 或供应商目录请求。现有 `status()`、RuntimeView 和 API 均保持原签名。

| 观察项 | 可以说明什么 | 下一步 |
| --- | --- | --- |
| waiting 含 NO_MESSAGES，lastReceivedAt=null | 本会话尚未收到属于它的事件 | 检查会话/采集状态或模拟事件入口；旧会话事件不会计入新会话 |
| PLAYER_NOT_READY | 没有有效且 READY 的唯一播放端 | 检查 HELLO/LEASE/READY、前台状态、心跳；不先花模型/TTS 额度 |
| MODEL_WAIT | 评论在排队或模型执行中 | 查 task.stage 的 MODEL_WAIT/MODEL_STARTED、stageAgeMs 和工作线程 active |
| SPEECH_WAIT | 模板/文本在排队或合成执行中 | 查 SPEECH_WAIT/SPEECH_STARTED 及 NLS 稳定错误码 |
| PLAYBACK_WAIT | 已有待播音频，或已派发但尚无 STARTED | 查 PLAYBACK_WAIT/PLAYBACK_SENT；后者等回执，不等同于已出声 |
| PLAYING | 当前任务已收到合法 STARTED | 查后续 COMPLETED/完成期限；不是用 TTS 返回推断播放 |
| WORKER_BUSY | 逻辑任务已结束，但原工作线程尚未退出 | 常见于取消交接或不遵守中断的适配器；看线程 active、completed、排队年龄 |
| NO_PENDING_TASKS | 会话运行、播放端就绪、当前没有任务 | 查看上次接收/成功时间和最近 SKIPPED/DROPPED 原因 |
| FAILED + RATE_LIMITED/SERVER_ERROR/超时码 | 明确失败，不是无原因沉默 | 看所属模型/合成/播放阶段、最近成功时间及下一正常任务结果 |
| DROPPED/CANCELLED + TASK_EXPIRED | 已到任务 TTL | 检查前面等待时间；过期不继续播放 |
| STOPPED | 引擎未运行 | 查 runtime.state；显式启动新会话并重新获得播放租约 |

`waiting` 是同时成立的提示列表，例如 PLAYER_NOT_READY 与 MODEL_WAIT 可以同时出现。它不覆盖最近失败，旧错误也不代表当前任务仍失败。lastError 是已有接口的最近模型/合成错误，成功不会抹掉它；判断是否恢复应看更新的阶段成功时间和 COMPLETED。

## 内部数据与读取示例

```java
var view = runtimeEngine.diagnostics();
var current = view.runtime();                 // 原内部 Status 的副本
var waits = view.waiting();                   // 固定枚举，多项可共存
var modelWorker = view.modelWorker();         // threads / active / queued / completed
var speechWorker = view.speechWorker();
var trace = view.trace();
// trace.sessionId() / capturedAt() / lastReceivedAt()
// lastModelSuccessAt() / lastSpeechSuccessAt() / lastPlaybackSuccessAt()
// lastFailure(): 最近 FAILED 的 Trace；没有时为 null
// history(): 最多 1024 个 Trace；tasks(): 最多 512 个 Task
// transitions() / reasons(): 固定枚举计数；taskEvictions() / historyEvictions()
// view.dedupEntries() / audioAssets(): 当前资源条数
```

Trace 只含序号、sessionId、服务端 taskId、事件类型、配置版本、阶段、固定原因码、服务端时间、previousStageMs 和 taskElapsedMs。`previousStageMs` 是前一阶段持续时间，例如 MODEL_COMPLETED 上记录模型运行耗时，STARTED 上记录派发到开始的等待，COMPLETED 上记录播放耗时。Task 的 stageAgeMs/taskAgeMs 是读取时的当前阶段/任务年龄。

RECEIVED → MODEL_WAIT → MODEL_STARTED → MODEL_COMPLETED → SPEECH_WAIT → SPEECH_STARTED → SPEECH_COMPLETED → PLAYBACK_WAIT → PLAYBACK_SENT → STARTED → COMPLETED。礼物/关注/入场省略模型阶段。终态还包括 SKIPPED、DROPPED、CANCELLED、FAILED；终态不接受迟到覆盖。礼物合并中的后续事件以自己的服务端 taskId 记录 GIFT_MERGED，实际合成/播放沿用首条任务 ID。

不保存评论正文、昵称、userId、外部 eventId、音频字节/路径、Key、供应商正文、原始 Throwable、HTTP 数据或客户端 ACK.errorCode/occurredAt。模型/NLS 异常只从已有枚举映射为固定原因；播放 FAILED 映射 PLAYER_FAILED，不使用客户端传来的自由文本。Snapshot 中所有集合为不可修改副本。

容量与时间边界：

- 历史最多 1024 条、任务表最多 512 条，详细记录保留 5 分钟；读写时淘汰。任务表满时优先移除已终结任务，必要时移除最早条目并增加 taskEvictions。诊断淘汰不影响业务队列，也不允许迟到结果重新创建条目。
- 最近三种成功时间、最近接收时间、固定枚举累计计数属于会话标量，在新会话清零；这些标量可保留超过 5 分钟。lastFailure 的详细记录仍受 5 分钟限制。
- 新会话重置诊断，旧会话的完成、失败、回执均不能更新新会话。停止后保留旧会话诊断供读取，直到过期或下次启动。
- 模型/合成工作线程各最多 1 个，SynchronousQueue 不积压执行任务。取消后若线程仍在退出，任务留在原有界业务队列；极短的线程交接拒绝会把刚取出的任务恢复到同优先级队首，不延长 TTL。
- 默认评论/模板/合成/播放队列各 8，配置允许至 100；播放最多一个在途任务。AudioAssets 仍最多 128 个、单个内容最多 2MB。单个快照最多复制固定上限的元数据，不含音频。
- 诊断锁不回调引擎/播放；锁顺序为引擎 → 播放 → 诊断，避免在播放回执中反向等待引擎。

计数口径不同：原 `runtime.failures`/`dropped` 仍是进程累计；新 `trace.transitions/reasons` 是当前会话计数。FAILED 包括播放端失败；runtime.failures 保留模型/语音/引擎口径，不用诊断反向修改它。不要把两者相加当作“失败任务总数”，也不要把 reasons.NONE 次数当作任务数。去重、无声 LIKE、繁忙欢迎、合并分别记录 DUPLICATE、LIKE_NO_SPEECH、WELCOME_BUSY/WELCOME_INTERVAL、GIFT_MERGED。

## 审计中保留与修复的行为

| 已有机制（保留） | 本轮核对结果 |
| --- | --- |
| 评论用模型、礼物用模板 | 慢模型时合并礼物仍能先通过合成/播放；不共用模型工作线程 |
| 礼物 60、评论 50、关注 10、入场 5；同级 FIFO | 有界优先队列规则保留；新增线程交接失败时的 FIFO 恢复测试 |
| 礼物 150ms 合并窗口、欢迎 30s 默认间隔且忙时跳过 | 原规则和原回归保留；新增长跑中同时注入 FOLLOW/ENTRANCE |
| 评论 60s、礼物 120s、欢迎 40s TTL | 接收、出队、合成回调和播放前继续检查；诊断区分到期与播放剩余 TTL 不足 |
| 模型 20s；本地语音默认 5s，NLS 10s | 引擎/适配器双重截止时间；每个任务最多计一次失败 |
| DeepSeek 单次连接 3s、读取 5s、429/5xx 至多重试一次 | 本轮未改适配器；80 次本地 HTTP 尝试验证持续故障后恢复及每次资源关闭 |
| 播放租约 15s、STARTED 5s、完成为 duration+5s | 回执/TTL/断线撤销与独占租约保留；分别记录 START_ACK_TIMEOUT / COMPLETION_TIMEOUT |
| 音频最长 25s，派发前预留 duration+10s TTL | NLS PCM→WAV 修复和时长保护保留，短 TTL 音频不会派发 |

实际修复：

1. 原失败路径仅检查 session/epoch/TTL，超时取消后的工作线程仍可能重复计数或覆盖错误。现在还检查当前活动任务对象与单次失败标记；异常 cause 遍历最多 8 层。30 轮超时各计一次，之后普通任务可恢复。
2. 不遵守中断的端口占据旧工作线程时，原实现仍会取出下一条再因 executor 拒绝而丢弃。现在先检查工作线程，队列继续受容量/TTL 限制；线程释放后恢复。不会为被卡住的端口无限创建线程或自动重启。
3. 空模型成功返回原先没有可见原因；现在为 MODEL_EMPTY_OUTPUT，任务终结并允许下一任务继续。
4. 原音频释放位于播放发布后的 finally；清理失败可能发生在已经出声后。现在加载到有界内存后先释放，再发布；清理异常不会播出该任务，且不重复释放/重复计错。主异常存在时保留首次失败原因。没有修改 SpeechPort 或 NLS 文件所有权实现。

仍有边界：Java 无法安全强制终止忽略中断的任意外部代码；若端口一直不返回，WORKER_BUSY 可持续存在，该通道只能按原 TTL 丢弃，不能保证恢复。测试让它在明确屏障释放后返回，证明有限线程和下一任务恢复；真实适配器仍必须遵守非阻塞 cancel/资源关闭契约。时钟若被系统回拨，年龄显示下限为 0；加速时钟测试不度量真实云端延迟。

## 可重复本地测试

```bash
# 只在本工作树运行；不会访问 main 的服务/数据目录
./scripts/maven.sh -B -ntp -pl backend \
  '-Dtest=RuntimeDiagnosticsTest,RuntimeStabilityTest,PlaybackDiagnosticsTest,DeepSeekStabilityTest' test

./scripts/maven.sh -B -ntp test
```

- RuntimeStabilityTest：先突发 2000 条评论，队列保留 8 条后到期；30 轮模型永不完成 → 礼物先播 → 模型超时 → 429/5xx → 正常评论恢复 → TTS 永不完成/超时 → 正常礼物恢复 → 断线重连，每 5 轮停止重开。虚拟总时间超过 31 分钟，检查队列、执行线程、执行器积压、去重、音频、诊断上限，最后停止后无待播音频。
- 同类测试还覆盖忽略中断的调用跨会话迟到返回、下一任务保留与恢复、空回复、音频清理异常。正常关闭后两个工作线程退出。
- RuntimeDiagnosticsTest：10000 条诊断压力、条数/保留时间/不可修改副本/旧会话隔离、全阶段关联和耗时、敏感文本不进入诊断、等待播放与已 STARTED 的区别、无 READY 和 TTL 到期，以及恢复 FIFO。
- PlaybackDiagnosticsTest：开始/完成回执超时、旧回执、低优先级拒绝、播放 TTL 不足、停止清理和重连恢复。
- DeepSeekStabilityTest：20 轮真实本机 HTTP 429/429 → 503/成功，共 80 次尝试，输出 20 次，所有 80 个请求资源释放；继承原 SSE keep-alive/持续 token 总期限与实际连接断开测试。

这些是可控时钟、本地 HTTP 和 Java 替身。**不等于真实 20/30 分钟云端压测，也不证明供应商长期可用、Token 续期或浏览器后台持续播放。**

## 真实长测方案（仅计划，未执行）

在集成窗口接好诊断读取后，单独批准持续时间、请求总量、最大费用与所用账号/区域。已有基本调用成功，无需为本计划再重复付费短验收。启动只用对应工作树 `scripts/dev-backend.sh` / `scripts/dev-frontend.sh`；本后端工作树为 18081/15174，不能连接/停止 main 的 18080。

建议一次批准的基准运行：30 分钟，最多 30 条短评论、30 条礼物；只用合成测试文本，不收真实观众隐私。每条评论 maxOutputTokens≤128，人设要求一两句。先由预算决定间隔，最多每分钟各一条；失败不补发、不无限重试。舞台保持前台、唯一播放器 READY。第 10 分钟人为断开/重新连接，第 20 分钟停止并显式新开会话，第 30 分钟停止；断开期间跳过注入，不事后补齐额度。429/5xx/永不完成故障仍留给本地替身，不能向真实服务故意制造故障洪泛。

每 5 秒读取诊断摘要，在本机受限日志中记录时间、session/task ID、阶段、固定原因、队列深度、最老任务年龄、三种最近成功时间、工作线程 active/queued/completed、音频/文件数量、诊断淘汰次数；完整最近轨迹只在出现异常时取样。导出文件另设上限（例如每次运行一个最多 10MiB 文件，到限停止记录并结束测试），不持久化正文/昵称/Key/完整 HTTP。当前仅有内部 Java 方法，HTTP 接口需主窗口批准实现；不要假设本分支已有诊断 URL。

同时由外部进程观测 RSS、JVM 线程/打开连接数、CPU、当前工作树音频目录的文件数/总字节，每分钟取样；不要用包含环境变量的进程详情命令导出凭据。统计模型/合成/播放等待 p50/p95、FAILED 和过期率、最长没有 COMPLETED 的时间、恢复后首次 COMPLETED 用时。详细历史只有 5 分钟，长测需要有界摘要采样，不能靠事后一次快照还原全程。

停止条件：

- 达到批准的时间、生成/合成实际尝试数或费用上限任一项；用户要求停止。
- 连续 3 个任务失败，或有新输入且播放器 READY 却超过 60 秒没有 COMPLETED；先停止收费输入，保存有界诊断。
- 任一业务队列超过配置容量、执行器出现积压、每通道工作线程超过 1、诊断超过上限、停止后仍派发/播放、旧会话音频进入新会话或双播放端发声。
- 出现鉴权/余额/权限问题、内存/线程/文件数持续上升，或证据显示取消未关闭连接。不要靠自动重启、清空全部队列、切换测试音掩盖问题。

费用估算先取实际账号、模型、区域和测试时段价格；以下为公式，不引用固定单价：

- DeepSeek 按输入/输出 token 计费，并区分输入缓存命中。保守按全部未命中：`C_model = A_model × (T_in × P_in + T_out × P_out) / 1,000,000`；A_model 包含重试，T_in 包含人设和测试评论，T_out 按上限 128。价格/时段以当时[官方定价](https://api-docs.deepseek.com/quick_start/pricing/)为准。
- 当前 NLS 短文本按计费调用单位估算：`C_speech = Σ ceil(effectiveChars_i / 100) × P_unit`，i 覆盖合成尝试；中文/标点等有效字符口径取[官方计费项](https://help.aliyun.com/zh/isi/getting-started/pricing)。不要把 Java UTF-16 长度直接当账单字符数，也不要套用长文本/CosyVoice/DashScope 单价。
- 30 评论+30 礼物的方案，默认模型至多 2 次尝试/评论，保守 A_model≤60；模型文本和礼物模板各需合成，NLS 按至多 2 次尝试预算 A_speech≤120。本次计划额外限制每条 effectiveChars≤300（不是 Java 的 300 字符上限），则最多 360 个 NLS 计费单位，再乘账号单价。实际需同时满足主会话音频≤25秒；失败是否收费由供应商账单确认，不预先抵扣免费额度。
- 将模型和语音币种统一，加入税费/区域/资源包差异与明确余量，写下数值上限再批准。测试后用供应商用量和实际 attempts 核对。诊断本身不保存 usage 或账单，不宣称能自动硬性控制总费用。

验收记录应列日期、提交 SHA、实际 wall-clock 时长、模型/音色、事件/尝试数、队列/线程/文件峰值、失败原因、下一任务是否恢复、费用或用量、停止后静音/资源回收证据，并区分浏览器前台和 OBS/后台场景。
