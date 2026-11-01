# 第三轮后端：运行诊断与恢复交接

日期：2026-11-01（Asia/Shanghai）。工作树：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/backend`，分支：`feature/backend-core`。

已交付：有界内部阶段诊断、只读快照、失败单次计数、工作线程占用期间保留有界队列、空模型输出/音频释放失败可见化，以及可重复的持续运行/故障恢复测试。最终后端 **150 项：149 通过、1 真实 NLS 测试默认跳过、0 失败/错误**。本轮真实供应商请求 **0 次**，没有收费长测。

## 基线与本地提交

- 开工 pwd、branch、status 确认指定工作树和 feature/backend-core，工作区干净，保留全部既有提交。
- 开工 `git merge --no-edit main` 快进到 `e39ca19893cde16a27937eac30c415db11a726cb`。确认包含 `c8be17c` PCM→WAV 修复和 `364d620` 同步规则。
- 实现/回归：`942afb7474586df31ce52e8ea459231520040b5b`，`feat(runtime): add bounded task diagnostics and recovery regression coverage`。
- 交付前发现 main 新增 README 提交，工作区代码提交后合入最新 `55a6fa04758b86be5550af5fd1c3d07cbc560b3c`，合并提交 `d593a1baca07ff2c3a474f237dc9542056ef9d4f`。仅 README 变化，无冲突。
- 保序修正/回归：`65bc046261204262dd306cc24ee228cbc1f361cd`，`fix(runtime): preserve FIFO during worker handoff rejection`。最终全量测试覆盖此代码。
- 文档单独提交；其 SHA 放在当前任务最终回复，或 `git log -1 --format=%H -- docs/handoffs/phase3-stability.md`。集成需包含上述代码提交与文档，不能只取最后的文档提交。

未修改 main 工作目录、旧项目、speech、collector、configuration、SessionService、ManagementController、contract、Vue、POM 或 OpenAPI。没有删除文件、自动 reset/stash、推送、PR、跨窗口消息或停止其他工作树进程。本轮仅按明确授权同步 main，没有合并其他功能分支。

## 审计和实际改动

保留已有且有测试的机制：不可变配置快照；评论/模板/合成/播放有界队列；优先级、同级 FIFO、150ms 礼物合并、欢迎忙时跳过/间隔；事件 TTL；模型整体 20s、本地语音 5s/NLS 10s；DeepSeek 限定重试、实际 HTTP/SSE 关闭；音频最长 25s、duration+10s 剩余 TTL；独占播放租约、STARTED/完成期限、停止与旧会话/epoch 隔离。没有重做 DeepSeek/NLS 适配器。

实际补缺口：

1. 新 `management/diagnostics/RuntimeDiagnostics.java`：按 sessionId/server taskId 记录 RECEIVED、模型等待/开始/完成、语音等待/开始/完成、播放等待/派发/STARTED/COMPLETED，以及跳过/丢弃/取消/失败。记录配置版本、类型、固定原因、服务端耗时和最近成功时间；不接收业务正文、昵称、外部 eventId、凭据、HTTP 或原始异常数据。
2. `RuntimeEngine.java`：新增兼容的只读 `diagnostics()`；生成任务身份后记录接收/去重/限频/合并结果；显式区分空模型输出。失败必须仍是当前活动任务并通过单次标记，避免引擎超时后 worker 再次计数、清理异常覆盖首次失败。cause 遍历最多 8 层。迟到结果不能更新新会话轨迹或成功时间。
3. `RuntimeEngine.java` + `scheduler/BoundedPriorityQueue.java`：取消后原 worker 仍占用时，不继续取出后续任务再丢弃；业务队列保留且照常容量/TTL 淘汰。线程交接拒绝使用 restoreFirst 恢复同优先级队首，不延长 TTL、不新增执行器积压。开始阶段只在 worker 实际运行时记录。
4. 队列新增固定 DiscardReason 和命名工厂 withReasons；原 Consumer 构造函数和优先级规则兼容。原队列方法引用无需改动。
5. `playback/PlaybackCoordinator.java`：写入共享诊断记录器，但不回调引擎，避免反向锁；记录排队、派发、合法 STARTED/COMPLETED、客户端失败、超时、短 TTL、背压、断线/停止。忽略 ACK 自由错误文本及客户端时间；hasStarted 区分已派发与真正开始。原协议/构造函数保留。
6. `playback/AudioAssets.java`：新增内部 size() 观测条数。合成文件在加载到有界内存后先 release 再发布，避免发布后清理失败导致“已出声但才报错”；一次释放异常不重复释放、不重复计错。保留 NLS PCM/WAV、文件归属及长音频保护测试。

详细诊断记录最多 **1024 条**、任务表最多 **512 条**、保留 **5 分钟**。任务表满时优先移除终态记录，记录容量淘汰数；详细 lastFailure 也到期。固定枚举计数与最近接收/三种成功时间是会话标量，新会话重置；不会随任务数增长。正常终态不被迟到覆盖。集合为不可修改副本，读取不调用云服务。

## 文件范围

生产代码（5 文件）：

- `backend/src/main/java/cn/bigfish/management/RuntimeEngine.java`
- `backend/src/main/java/cn/bigfish/management/diagnostics/RuntimeDiagnostics.java`
- `backend/src/main/java/cn/bigfish/scheduler/BoundedPriorityQueue.java`
- `backend/src/main/java/cn/bigfish/playback/PlaybackCoordinator.java`
- `backend/src/main/java/cn/bigfish/playback/AudioAssets.java`

新增测试（4 文件）：`core/RuntimeDiagnosticsTest.java`、`core/RuntimeStabilityTest.java`、`core/PlaybackDiagnosticsTest.java`、`conversation/DeepSeekStabilityTest.java`，均位于 `backend/src/test/java/cn/bigfish/`。

文档（3 文件）：`docs/runtime-stability.md`、`docs/providers/deepseek.md`、本交接。DeepSeek 说明更新为已完成基本联调及既有目录 REST 的当前状态，不再把第二轮历史“未接线”当现状。

## 实际测试与结果

| 操作 | 结果 |
| --- | --- |
| 同步基线后 `./scripts/maven.sh -B -ntp test` | 140 项，139 通过、1 真实 NLS 跳过、0 失败 |
| 核心/诊断/播放/DeepSeek 专项 | 48 项全部通过，含原回归；后续再补 1 项 FIFO 回归 |
| 同步最新 main 后首次全量 | 149 项，148 通过、1 跳过、0 失败 |
| 最后保序修正后 `./scripts/maven.sh -B -ntp test` | **150 项，149 通过、1 跳过、0 失败/错误**；2026-11-01 16:55:05 +08，21.863 秒 |
| `git diff --check` / staged 检查 | 通过；最终文件范围与敏感字面量检查见交付确认 |
| `git merge-base --is-ancestor main HEAD` | 交付检查为 0；包含 main `55a6fa0` |

专项命令：

```bash
./scripts/maven.sh -B -ntp -pl backend \
  '-Dtest=RuntimeDiagnosticsTest,RuntimeStabilityTest,PlaybackDiagnosticsTest,RuntimeEngineTest,PlaybackCoordinatorTest,SpeechOwnershipTest,IngressPrimitivesTest,DeepSeek*Test' test
```

默认真实 NLS 测试 `AlibabaNlsLiveTest` 因显式 live 开关未开启而跳过，未删除断言或禁用原测试。开发中曾出现新增队列构造重载与旧方法引用歧义，改成命名工厂；新测试 lambda 的变量声明编译错误也已修正。以上均为代码/测试问题，不归咎沙箱。最终测试按既有批准在沙箱外运行以支持本机端口/JVM attach。

本机日志：`/tmp/bigfish-phase3-baseline.log`、`/tmp/bigfish-phase3-core.log`、`/tmp/bigfish-phase3-final.log`，不提交、不作为跨机器依赖。本轮未启动持久开发服务，测试 HTTP/WebSocket 只用本机临时端口；未改前端，未运行前端构建。

证据范围：

- 2000 条突发评论：评论队列仍为 8，之后按 TTL 淘汰；10000 条诊断：历史≤1024、任务表≤512，5 分钟后详细记录消失。
- 30 个故障循环、超过 31 分钟虚拟时间：每轮模型和 TTS 超时各计一次；礼物绕过慢模型并合并，欢迎忙时跳过；429/5xx 后正常评论恢复，TTS 超时后正常礼物恢复；每轮断线重连，每 5 轮停止重开。
- 全程断言业务队列≤8、两个执行器各≤1工作线程且排队为0、音频≤9、去重≤4096、诊断上限；最终正常播放完成、停止后音频归零，关闭后两个 worker 退出。
- 暂时忽略中断的端口：只占一个线程，下一任务仍留在有界队列；明确释放屏障后下一任务成功，旧会话回复不出声、不污染新会话诊断。
- 20 轮本地 HTTP 429/429 → 503/成功：80 次尝试全部关闭资源、只交付20次成功输出。原长 keep-alive/持续 token 总期限、取消后服务器观察断开测试仍通过。
- STARTED 等待超时与完成超时原因不同；低优先级拒绝、播放剩余 TTL 不足、旧回执、断线/停止清理有明确原因。原 WebSocket 客户端唯一播放端、配置持久化/CAS、音频资源/文件释放测试保留通过。

## 诊断读取与最小接线示例

已有 RuntimeEngine Bean 无需重新接线；构造时内部安装共享记录器。集成管理层可以读取：

```java
var view = runtimeEngine.diagnostics();
var snapshot = view.trace();
// 返回经公共 DTO 筛选的字段，勿直接把内部 record 固化为公共协议。
String sessionId = snapshot.sessionId();
var waiting = view.waiting();
var lastReceived = snapshot.lastReceivedAt();
var lastPlayed = snapshot.lastPlaybackSuccessAt();
var failure = snapshot.lastFailure(); // null 表示本会话保留窗口内没有 FAILED
var history = snapshot.history();     // 最多1024条；主窗口可限量展示最近100条
var tasks = snapshot.tasks();         // 最多512条；含当前阶段年龄
var workers = view.modelWorker();     // threads / active / queued / completed
```

waiting 可同时包含 NO_MESSAGES、PLAYER_NOT_READY、MODEL_WAIT、SPEECH_WAIT、PLAYBACK_WAIT、PLAYING、WORKER_BUSY、NO_PENDING_TASKS；未运行时 STOPPED。限流/超时/过期从 Trace 的 reason 和终态判断，不能仅凭队列空或 lastError 非空判断还在故障。MODEL_COMPLETED、SPEECH_COMPLETED、COMPLETED 分别更新最近成功时间。

原 runtime.failures/dropped 是进程累计，新 trace.transitions/reasons 是当前会话累计且包括播放失败/各阶段转换。它们口径不同，不能相加。waiting 为多提示列表；trace 对象不含评论内容，Runtime Status 也不是账单或模型使用量。完整解释、限额与只读示例见 [运行稳定性](../runtime-stability.md)。

## 公共字段提案（本分支未添加 API/DTO）

1. 建议在主窗口选定一个只读诊断资源（例如 `GET /api/v1/runtime/diagnostics`，仅提案），沿用现有管理访问控制。默认返回摘要和最近最多100条记录，最多允许512条任务视图；禁止任意不限量导出。轮询不触发供应商请求。
2. 摘要字段建议：sessionId、capturedAt、state、waiting[]、lastReceivedAt、lastModelSuccessAt、lastSpeechSuccessAt、lastPlaybackSuccessAt、lastFailure（可空）、queueDepth/capacity、workers（每通道 threads/active/queued/completed）、audioAssetCount、dedupCount、historyEvictions/taskEvictions；保留现有 RuntimeView，不在本分支直接扩字段。
3. Trace DTO 建议：sequence、sessionId、taskId、eventType（非法输入时可空）、configVersion、stage、reason、at、previousStageMs、taskElapsedMs。Task DTO 建议：taskId、eventType、configVersion、stage、reason、receivedAt、updatedAt、stageAgeMs、taskAgeMs。枚举与空值语义以本文和实现为依据，由主窗口统一 OpenAPI/TS/Java。
4. 前端按 sessionId 丢弃迟到响应；区分“合成成功”“已派发”“收到 STARTED”“完成”，展示最近成功以证明恢复。欢迎限频、LIKE不发声、礼物合并属于 SKIPPED，不渲染成供应商故障。异常和历史原因不清空普通成功信息；新会话重置。
5. UI 可每2–5秒取摘要，用户展开任务时取有限轨迹；前端也要限制缓存条数。不要把历史每轮重复快照无限追加。history 仅5分钟，30分钟长测需有界摘要采样，不能事后一次查询还原全程。
6. 若后续增加分页，cursor 应同时带 sessionId/sequence，过期/被淘汰要返回明确缺口；不要用缺失诊断记录推断业务从未执行。当前内部快照已足够主窗口实现首版展示。

## 未验证项与真实长测计划

- 用户已确认基本真实联调；本轮没有读取/复制任何凭据，没有重复真实短请求，没有云端20/30分钟长测，没有 OBS/后台验收或真实抖音采集。
- 加速31分钟测试只覆盖逻辑时钟、生命周期与本地替身恢复，不代表真实网络时延、服务限频、Token续期或长期资源曲线。真实NLS格式修复及原测试已保留；Token任务仍由语音窗口负责。
- 永久忽略中断的任意外部代码不能安全强杀；本实现保持线程/队列有界并显示 WORKER_BUSY，只有端口返回后才能恢复。没有重启服务、清空全部业务任务或静默切换测试音来掩盖它。
- 诊断为内存记录，重启丢失，默认无持久化。高流量会淘汰详细轨迹；固定计数与淘汰计数可解释缺口，不宣称完整审计日志。模块状态和 worker 计数是读取时观测值，不能替代整个进程的资源监测。
- [真实长测计划](../runtime-stability.md) 已写出30分钟/最多30评论+30礼物、每条128输出tokens、前台单播放端、断线/重开步骤，5秒诊断采样、资源/延迟指标、连续3失败/60秒无完成/越界/超预算等停止条件。费用按实际账号输入输出token价格及NLS有效字符计费单位估算，计入最多两次尝试；没有写死价格或自动启动收费任务。
- 不新增依赖。公共诊断 REST/DTO/前端展示与实际长测预算由主窗口处理；此分支可直接合并后运行全部本地回归。
