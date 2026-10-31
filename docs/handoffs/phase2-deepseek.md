# 第二轮 DeepSeek 交接

日期：2026-10-30（Asia/Shanghai）。工作树：`bigfish-worktrees/backend`，分支：`feature/backend-core`。

状态：适配器、独立验收入口与本地故障测试已完成；全量后端 83 项通过。**真实 GET /models、真实生成、与真实 TTS/抖音联调、长期付费稳定性均未验证**。当前任务进程未配置 `BIGFISH_DEEPSEEK_API_KEY`，`BIGFISH_ALLOW_PAID` 未启用；真实请求数为 0。没有把无 Key 拦截、本地 SSE 或模型目录成功记为真实生成成功。

## 基线与提交

- 开工检查 pwd / branch / status：指定工作树和 feature/backend-core，原有工作已提交，工作区干净；保留全部第一轮成果。
- 按本轮授权执行 `git merge --no-edit main`，从 `89303e9` 快进到任务基线 `a89c304ce16e8299e734938ab83f9780eba58fcf`，包含已集成行为基线 `49a7865`。无冲突，未合入其他功能分支。
- 实现和测试提交：`e2057eac3e4cbbb586063326b8bfa59c76c599ad`，`feat(conversation): harden DeepSeek discovery and streaming recovery`。
- 本文件和供应商说明随后单独提交；文档 SHA 见当前任务最终回复，或 `git log -1 --format=%H -- docs/handoffs/phase2-deepseek.md`。集成需要实现和文档两笔提交。

只修改本轮 DeepSeek 独占目录。未改公共契约、POM、CoreConfiguration、RuntimeEngine、SessionService、播放/语音/采集模块、前端、脚本或旧项目；未删文件、未推送、未建 PR、未联系其他窗口。main 工作目录只读。

## 实现与修改文件

生产代码均在 `backend/src/main/java/cn/bigfish/conversation/`：

- `DeepSeekConversation.java`：保留原工厂和 ConversationPort；快照提供模型、人设、max_tokens；仅收集观众回答，完整结束后单次交付。增加错误分类、有界 429/5xx 重试、读取期限、输出限制、诊断以及 close；取消释放实际连接，不在外部回调中持锁。
- `DeepSeekHttp.java`：每次尝试独立持有 JDK HTTP client；固定官方 HTTPS、禁止重定向，校验 MIME，限制 codec/累计响应字节，按 HTTP 状态和已知错误机器码分类，不传播上游正文。迟到错误仅保留脱敏码。
- `DeepSeekFailure.java`：稳定 Code 与 Diagnostic(code, elapsedMs, attempts, httpStatus)，无原始异常 cause/message/body。继续继承 IllegalStateException，兼容现有工厂调用方式。
- `DeepSeekConnectionProbe.java`：显式 GET /models，最多 100 个唯一模型 ID、2 个并发探测，无缓存或重试；分别报告鉴权、所选模型、生成状态。目录检测不生成文本，generation 始终 NOT_CHECKED。
- `DeepSeekSmoke.java`：独立 main，支持 --models / --check / --generate；只读指定两项环境变量，先检测再生成，最多 3 次实际生成 POST，每条 max_tokens=128、20 秒总期限、无重试。输出仅结果类别、时间、模型 ID、耗时、字符数，不打印完整回复。

测试文件：

- `backend/src/test/java/cn/bigfish/core/DeepSeekConversationTest.java`：保留原 4 项测试及断言；成功 SSE 夹具补标准 `finish_reason=stop`。
- `backend/src/test/java/cn/bigfish/conversation/DeepSeekFixture.java`：随机本机 HTTP 替身，不读取凭据，不记录 headers。
- `DeepSeekRecoveryTest.java`：7 项，含 HTTP 错误矩阵、未知模型、重试预算、半截/错误/超长流、推理内容过滤、并发新旧快照、读取/连接超时及恢复。
- `DeepSeekLifecycleTest.java`：3 项，实际 SSE 断开、取消后新请求恢复、外部回调阻塞时取消不死锁、并发上限/close。
- `DeepSeekConnectionProbeTest.java`：4 项，鉴权/模型/生成状态分离，未知模型，条数/ID/字节限制，总期限与资源释放。
- `DeepSeekSmokeTest.java`：2 项，缺少开关/Key、非法参数、超过 3 条拒绝；全程不真实联网。

新增说明：`docs/providers/deepseek.md`，包含 IDEA 本机配置、脚本入口、错误码、边界、工厂和生命周期示例。

## 行为与资源边界

- 保留默认 MOCK 和无 Key 正常启动；工厂仅验证开关/非空 Key，不宣称鉴权成功、不联网。模型来自当前账号目录，不硬编码可用模型名。
- 请求带 `reasoning_effort=none`，忽略 `reasoning_content`；单候选文本、stop 结束、非空才交付。length/content_filter、格式错误、未结束 EOF、超长文本均失败且无半截播出。Spring AI 隐藏 [DONE]；收到 stop 后的正常 EOF 也接受。
- 生成最多 4 个同时进行的请求，无积压队列；目录检测最多 2 个。每尝试独立 client，成功/失败/取消/超时关闭。连接 3 秒、默认读取 5 秒；调用方整体预算覆盖全部重试/退避，不被 keep-alive 或持续 token 重置。生成期限上限 60 秒，现有会话一般 20 秒；探测上限 15 秒，独立入口 10 秒。
- 每单次响应 codec 65,536 字节、累计流体 262,144 字节；最多 4,096 chunk、2,000 Java 字符。诊断最多 128 条、5 分钟。目录最多 100 个唯一 ID，单 ID 100 字符。
- 默认仅对 429/5xx 重试一次，250ms 退避；鉴权/余额/权限/模型/网络超时/坏流不重试，成功不会再次生成。独立真实入口 maxAttempts=1，确保“3 条”不会变成 6 次付费请求。去除旧全局 5 秒错误冷却，下一次请求可立即尝试恢复，不隐式切换供应商。
- 取消先取得终态时禁止 sink；成功已提交时取消不撤回或等待 sink。现有引擎 sessionId/任务/epoch/TTL 检查必须保留，防止成功刚提交又停止时的旧会话输出。回调应快速入队；适配器不负责中断调用方已执行的代码。
- 请求上下文使用当前 Reactor 3.7.16 的 `reactor.onErrorDropped.local` 做局部脱敏，不设置全局 hook；依赖升级时需复测取消日志行为。

## 已运行验证

| 命令 / 操作 | 结果 |
| --- | --- |
| 合入 main 后 `./scripts/maven.sh -B -ntp test` | 原基线 67 项全部通过 |
| `./scripts/maven.sh -B -ntp -pl backend '-Dtest=DeepSeek*Test' test` | 最终 20 项通过，0 失败/错误/跳过 |
| 最终 `./scripts/maven.sh -B -ntp test` | **83 项通过，0 失败/错误/跳过**；2026-10-30 02:07:53 +08，12.372 秒 |
| `env -u BIGFISH_DEEPSEEK_API_KEY -u BIGFISH_ALLOW_PAID ./scripts/dev-backend.sh -B -ntp -Dspring-boot.run.main-class=cn.bigfish.conversation.DeepSeekSmoke -Dspring-boot.run.arguments=--models` | 子进程退出 3，明确 PROVIDER_DISABLED / no requests sent；Maven 退出 1 为预期拒绝 |
| `env -u BIGFISH_DEEPSEEK_API_KEY -u BIGFISH_ALLOW_PAID ./scripts/dev-backend.sh -B -ntp` | 正常启动 BigfishApplication，端口 18081；新增独立 main 未破坏正常入口选择 |
| `curl --silent --show-error --fail http://127.0.0.1:18081/actuator/health` | HTTP 200，status=UP |
| `curl --silent --show-error --fail http://127.0.0.1:18081/api/v1/runtime` | STOPPED / MOCK / MOCK / LOCAL_TEST_TONE，活动模型/语音均 false |
| 停止本次新启动进程并检查端口 | 仅向本任务 PID 83514 发 TERM；Spring/Hikari 优雅关闭，18081 无监听；Maven 子进程退出 143 为主动停止 |
| `git diff --check` / staged 检查 | 通过；变更均在授权范围，未包含 runtime/.cache/凭据 |

最终日志在本机 `/tmp/bigfish-phase2-final.log`，专项日志 `/tmp/bigfish-phase2-faults.log`，无 Key 独立入口日志 `/tmp/bigfish-phase2-smoke-disabled.log`。日志不作为跨机器依赖，测试可重跑。测试只访问本机随机端口或注入异常；没有使用生产 Key 的测试夹具。

验证过程中发现“大 JSON 超 codec 上限”误归 NETWORK_ERROR，现已归 INVALID_RESPONSE 并通过边界测试；取消的迟到异常不再打印原始 Reactor 错误堆栈。没有删除失败断言或跳过测试。Maven/JVM 和本机 HTTP 在授权后于沙箱外执行；沙箱内 curl 访问被拒，授权后同 URL 成功。前端未修改，未运行前端构建。

保留原配置持久化/CAS、mock 评论与礼物独立通道、慢模型不阻塞礼物、有界队列、旧会话/停止/断线隔离、唯一播放端及 WebSocket 客户端测试的全量通过结果。

## 真实验证记录与待完成事项

- 日期：2026-10-30。环境检查仅判断指定变量存在性/开关，未输出值、未扫描其他项目配置。
- 真实模型 ID：未发现。真实 GET /models 次数：0。真实生成次数：0。真实延迟范围：无数据。鉴权/模型/生成结论：全部未验证。
- 用户需在本机 IDEA 的非共享运行配置中填写凭据和开关，再依次运行 --models、--check、--generate；最多 3 条、每条 128 tokens 的具体步骤见 [供应商说明](../providers/deepseek.md)。无需把 Key 贴入聊天或修改仓库。
- 当前基线的默认 model 不能视为真实可用 ID；必须从账号目录选择。目录通过仍不证明生成权限/余额/回答可用。
- 这轮未做真实模型 + mock 事件 + 播放端联合请求，也未做 20/30 分钟稳定性验收。需要凭据及独立预算后由集成窗口安排；不能用本轮本地测试代替。
- 当前 speech 仍是可重复本地测试音，本任务没有实现真实人声或连接抖音。

## 供集成调用的最小示例

原 CoreConfiguration 工厂签名保持兼容，无需为了启用本实现修改原接线：

```java
DeepSeekConversation.create(allowPaid, key); // 只应在显式 DEEPSEEK 会话中创建
```

新增显式连接检测（管理层异步执行，不在采集线程等待）：

```java
try (var probe = DeepSeekConnectionProbe.create(allowPaid, key)) {
    var check = probe.check(requestId, snapshot.settings().model(), Duration.ofSeconds(10))
        .toCompletableFuture().get(11, TimeUnit.SECONDS);
    // 映射 authentication / selectedModel / generation / availableModels / diagnostic。
    // selectedModel=null 则仅列目录，generation 永远 NOT_CHECKED。
}
```

生成仍调用 `reply(taskId, commentEvent, snapshot, deadline, sink)`；停止使用现有 `cancel(taskId)`。如需会话级销毁可调用具体实现 `close()`，也可通过 AutoCloseable；当前每次传输均自行释放，无会话级持久在线 HTTP。取消先赢时没有回调；成功已提交仍需引擎最后一道旧会话检查。`diagnostic(taskId)` 可读取最终脱敏信息。

## 公共接口与接线提案（本分支未实施）

1. **连接检测/目录 REST**：由集成窗口选定路径并修改 OpenAPI/DTO。建议请求仅有 requestId、selectedModel（可空）、configVersion，不接受 Key/endpoint；检测永远只 GET、不生成。返回 provider=DEEPSEEK、credentialConfigured、paidCallsEnabled、authenticationStatus/modelStatus/generationStatus（NOT_CHECKED/PASSED/FAILED）、selectedModel、availableModels、configVersion、checkedAt、errorCode、elapsedMs、attempts、httpStatus。开关/非空 Key 仅表示本机就绪，不能代替 PASSED。目录缺失模型时鉴权仍 PASSED、模型 FAILED、生成 NOT_CHECKED。
2. **显式生成验收**：独立入口已可用。若增加 UI 按钮，应单独明确其将消费额度，先检验所选模型，最多 3 次实际 POST、每条 max_tokens≤256、无自动重试；记录短结果/耗时，不返回凭据或上游完整错误。不能在加载页面、保存配置或启动应用时自动生成。
3. **运行错误可见性**：当前 RuntimeEngine.failed 仅记外层异常类型及计数，未把 DeepSeekFailure 的错误码送给 UI。请集成层安全解包 CompletionException/ExecutionException，把 Diagnostic 映射到已批准的 RuntimeView 状态/有限错误历史；CancellationException 可结合 `diagnostic(taskId)`，引擎先触发的期限应标成引擎超时。建议附 taskId/sessionId/configVersion，更新前校验仍属当前会话，避免旧失败污染新状态。不要日志输出原始 cause、headers、正文或自由文本。
4. **配置和检测失效**：保存模型/凭据轮换后，把旧检测结论置为未验证，避免新配置沿用旧 PASSED；检测响应关联配置版本/所选模型，迟到结果不能覆盖新状态。环境变量 Key 在进程内不会自动热更新，需要重启或由集成窗口设计安全轮换。
5. **生命周期**：保留现有调用方 session/epoch/TTL 二次检查；不要给取消与 sink 添加同一外部锁造成锁顺序反转。若未来统一 ConversationPort.close，先更新公共契约；本轮没有修改它。
6. **依赖**：无新增依赖、无版本升级提案。沿用固定 Spring AI/JDK HTTP。Reactor 升级时重验请求局部迟到错误处理；若框架以后提供稳定公开替代入口，由集成窗口统一调整。

集成窗口合并后重新跑全量测试、补公共状态/页面接线，并在凭据就绪后补独立真实短请求记录。不要把第一轮旧 handoff 的“REST 未接线/全局冷却”等历史描述当作本轮现状；本交接针对 a89c304 之后的增量。
