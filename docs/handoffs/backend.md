# 显式采集凭据与礼物业务追踪交接（2026-11-02）

本节为本轮最新交付，下方历史内容保留。工作树 `bigfish-worktrees/backend`，分支 `feature/backend-core`。

## 基线与提交

- 开工检查目录/分支正确、工作区干净；从 `813e8d56bdfc467f7ae5148030a60f31e8485473` 快进合入最新本地 main **`803532fa1f6a64bad038f5cd18c8ab322c53b03e`**。
- 实现与回归提交：**`a5bb696dbad51771aafa2403b9774039f429d781`**，`feat(backend): add explicit collector credentials and bounded gift tracing`。
- 实现提交后 main 仍为 `803532f`，祖先检查通过，未遗漏主线更新。本文单独提交；文档提交 SHA 见最终回复或 `git log -1 --format=%H -- docs/handoffs/backend.md`。
- 仅本地提交，未更新 main、推送远端或联系其他窗口；没有启动/停止主窗口直播进程。

## 实际修改范围与兼容性

| 文件 | 修改 |
| --- | --- |
| `configuration/SessionCollectors.java` | 显式 guest/external 工厂选择、本机凭据预校验、固定诊断；沿用现有 provider、credentials、signer 和房间绑定 |
| `configuration/CoreConfiguration.java` | 从 Spring Environment 接线凭据模式与可选礼物文件日志，启动不请求平台 |
| `management/SessionService.java` | 启动失败保留具体凭据原因；已关闭入口的礼物回调只输出旧会话拒绝证据 |
| `management/RuntimeEngine.java` | 礼物校验、去重、合并、排队、模板节点日志；接入已有合成/播放诊断，不改变业务调度 |
| `management/diagnostics/RuntimeDiagnostics.java` | 增加内部观察者构造方法，原构造方法兼容；已有任务终态去重与会话隔离保持不变 |
| `management/diagnostics/GiftTrace.java`、`GiftTraceLog.java` | 固定元数据日志及异步、有界本机保存器 |
| `core/CoreRig.java`、`CollectorCredentialsTest.java`、`CollectorConfigurationIntegrationTest.java`、`GiftBusinessTraceTest.java`、`GiftTraceLogTest.java` | 测试接线和新增 23 项本地回归 |
| `docs/handoffs/backend.md` | 本轮顶部交接 |

未修改 collector、frontend、conversation、speech、播放协议/调度实现、公共 DTO、OpenAPI、数据库、脚本或 POM。点赞感谢策略、DeepSeek 提示词、TTS 实现不变。现有 REST/WS 字段不变，`mode/conversationMode/speechMode` 的独立含义不变；HTTP 错误通过既有 `code` 字段表达。

## 连接模式与凭据配置

全部沿用独立 probe 的名称，**只由用户在本机服务运行环境提供**；没有添加配置数据库字段、凭据管理接口或浏览器 Cookie 读取。

| 环境变量 | 规则 |
| --- | --- |
| `BIGFISH_DOUYIN_CREDENTIAL_MODE` | 未设置时 `guest`；显式值仅允许小写 `guest` / `external`，空值或其他值为 `CREDENTIAL_MODE_INVALID`；启动时选择，修改后需在合适时机重启该实例 |
| `BIGFISH_DOUYIN_COOKIE` | external 必须提供的用户授权网页会话 Cookie；guest 不读取 |
| `BIGFISH_DOUYIN_USER_AGENT` | 与该会话匹配的 UA；guest 不读取 |
| `BIGFISH_DOUYIN_USER_UNIQUE_ID` | 与该会话匹配的数字访客 ID；不伪造或使用固定身份 |
| `BIGFISH_DOUYIN_CREDENTIALS_EXPIRES_AT` | 用户允许本机使用到的 ISO-8601 UTC 时间，如格式 `YYYY-MM-DDTHH:mm:ssZ`；必须晚于当前时间 |
| `BIGFISH_GIFT_TRACE_PERSIST` | 仅显式 `true` 开启下述礼物文件保存，默认关闭；控制台脱敏日志默认开启 |

guest 继续使用 `WebDouyinConnectionProvider.guest(bindingSigner)`；external 使用现有 `WebDouyinConnectionProvider(CredentialSource, bindingSigner)` 和 `DouyinCredentials`，没有重写采集器、签名或解析。credentials 的权威校验是 collector 包内私有方法；本轮在工厂做同等最小预校验，实际 provider 在解析/签名前仍执行原校验。重连重新读取凭据来源，过期则失败，不降级游客。

启动日志固定为 `douyin_connection mode=GUEST|EXTERNAL|INVALID validation=... platform=UNVERIFIED`。guest 的配置结果是 `GUEST_AUTO`；external 合法格式且未超过本地截止时间才为 `LOCAL_VALID`。**LOCAL_VALID 不是平台登录验证成功，不代表 Cookie 在平台仍有效，更不代表已连接或已收到礼物。** 平台拒绝/验证要求仍由既有采集器产生固定错误码。本轮没有增加有效 WSS 的凭据到期定时断开机制；本地截止时间在启动和下一次解析/重连时检查。

external 缺失、格式错误、本地截止时间已过，分别为 `CREDENTIALS_MISSING`、`CREDENTIALS_INVALID`、`CREDENTIALS_EXPIRED`。环境读取/解析异常也转换为固定原因，不输出属性值或异常 cause。错误配置不会阻止 Spring 启动或 MOCK 使用；显式启动 DOUYIN 时才返回 HTTP 503 与 `DOUYIN_<原因>`，主会话 `FAILED` / `runtime.lastError=COLLECTOR_FAILED` / `collector.lastError=<原因>`。入口已关闭且没有创建新采集线程。原 `COLLECTOR_STOPPING` 门禁保持。

本工作树开发启动（guest，不需要凭据）：

```bash
BIGFISH_DOUYIN_CREDENTIAL_MODE=guest BIGFISH_GIFT_TRACE_PERSIST=true ./scripts/dev-backend.sh
```

external 启动：用户先在自己的运行环境设置上述四项凭据，不在命令示例、交接、截图或 Git 文件中放真实值，再执行：

```bash
BIGFISH_DOUYIN_CREDENTIAL_MODE=external BIGFISH_GIFT_TRACE_PERSIST=true ./scripts/dev-backend.sh
```

脚本沿用本工作树 `runtime/worktree.env`，端口 18081，独立数据目录；本轮没有写入该文件。不要为了本次集成停止主窗口当前直播，由用户/主窗口安排启用时机。启动、查询、保存配置均不自动连接抖音；仍需显式 POST 会话 `mode=DOUYIN`，回复和语音可独立选择 `MOCK` / `LOCAL_TEST_TONE`。`BIGFISH_DOUYIN_LIVE_PROBE` 等 probe 专用开关不是主会话开关。

## 礼物日志如何阅读

日志行前缀 `gift_trace`，内容为 JSON。字段仅有 `sequence/at/sessionId/taskId/eventType/phase/reason/relatedTaskId/configVersion/quantity/stageMs/taskMs`。ID 只保留 UUID 格式；阶段/原因仅允许固定枚举，数量有界。**不保存 Cookie、UA、访客 ID、用户 ID/昵称、礼物名称、评论正文、感谢文本、上游 eventId/comboId、签名 URL、HTTP 数据或原始异常。** 本轮只关联后端生成的 sessionId/taskId。

| 节点 | 日志依据 |
| --- | --- |
| 进入有效会话的 EventIngress | `RECEIVED`：还未通过业务校验，不表示原始 WSS 帧接收或礼物最终播出 |
| 校验、去重 | `VALIDATED`、`DEDUP_PASSED`；无效事件 `DROPPED/INVALID_EVENT`，串房另有 `INGRESS_REJECTED/ROOM_MISMATCH`；重复 `SKIPPED/DUPLICATE` |
| 连击合并 | `MERGE_CHECKED` 携带 `NO_MERGE_CANDIDATE/MISSING_USER_ID/MERGE_QUANTITY_LIMIT`；合并成功 `MERGED_INTO/GIFT_MERGED`，`relatedTaskId` 指向保留的主任务，`quantity` 是合并后数量；被并入的收件任务终态为 `SKIPPED/GIFT_MERGED` |
| 队列、模板 | `TEMPLATE_QUEUED → SPEECH_WAIT → TEMPLATE_RENDERED → SYNTHESIS_QUEUED`；模板数量来自原有增量合并，礼物不等待模型；拒绝/淘汰使用 `QUEUE_FULL/CAPACITY_REDUCED/TASK_EXPIRED` 等原固定原因 |
| 合成 | `SPEECH_STARTED → SPEECH_COMPLETED`；失败 `FAILED` 携带原语音映射码，如 `RATE_LIMITED/OVERALL_TIMEOUT/INVALID_AUDIO` |
| 播放 | `PLAYBACK_WAIT → PLAYBACK_SENT → STARTED → COMPLETED`；前两者不证明浏览器已发声，STARTED/COMPLETED 来自有效租约回执；失败/取消分别为 `PLAYER_FAILED/PLAYER_CANCELLED` 等固定原因 |
| 断线、租约、停止 | 原有 `PLAYER_DISCONNECTED/LEASE_EXPIRED/PLAYER_CHANGED/SESSION_STOPPED` 等原因；已经关闭的采集入口只记录 `INGRESS_REJECTED/SESSION_NOT_RUNNING`，旧 sessionId 不写入新会话运行诊断 |

同一 task 的终态使用已有 RuntimeDiagnostics guard，只计一次；失败后的取消/释放/迟到回调不能再添加第二个终态。`MERGED_INTO` 是关联证据，随后 `SKIPPED` 是收件终态，统计合并次数应按阶段/taskId 去重，不能直接按 reason 出现行数计数。旧会话日志保留在文件中；前端当前会话诊断仍在开始时重置。

`stageMs/taskMs` 在已有生命周期记录中表示上一阶段耗时/任务累计耗时；新业务节点仅为时间点，两个耗时字段为 0。`quantity` 只在校验、入队、合并、模板节点填写，其他节点的 0 不表示用户送了零个礼物。`sequence` 是当前日志实例的序号，拒绝入队会有缺号；跨进程按 `at + sessionId + taskId` 阅读，不能假定 sequence 永不重置。

## 本机保存路径与上限

启用 `BIGFISH_GIFT_TRACE_PERSIST=true` 后，路径固定在：

```text
<BIGFISH_DATA_DIR>/diagnostics/gifts/
  gift-trace.lock
  gift-trace-0.jsonl
  gift-trace-1.jsonl
  gift-trace-2.jsonl
  gift-trace-3.jsonl
```

本工作树未额外覆盖 DATA_DIR 时，绝对路径为：
`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/backend/runtime/diagnostics/gifts/`。

- 最多 **4 × 1 MiB = 4 MiB** 日志内容，另加空锁文件；单行上限 2048 bytes。文件写满后仅截断下一个固定槽文件，不删除文件或目录，不产生按日期无限增长的文件。
- 单个 daemon 写线程，等待队列最多 **1024 条**。业务线程只尝试入队，不等待磁盘或控制台；满队列丢弃本条诊断、累计 dropped 并输出 `gift_trace_loss` 固定告警，不丢弃业务任务或让业务异常。
- `GiftTraceLog.status()` 是内部方法，可读 delivered/persisted/dropped/ioFailures/queued/workerAlive 和文件状态；未新增 REST。`FILE_INIT_FAILED/FILE_WRITE_FAILED` 会明确告警，文件不可用时继续控制台诊断，不回退其他目录。内存满、已关闭或控制台异常统一计入 dropped；文件错误由 ioFailures/文件状态单独表达。
- 固定诊断目录/文件拒绝符号链接，POSIX 目录 0700、文件 0600；文件锁防止两个实例同时写同一组槽。既有异常文件或过大文件会使保存不可用，不擅自删除或清理。
- 正常关闭最多等待写线程 2 秒排空，超时记录 `SHUTDOWN_BUDGET` 并放弃尚未保存的诊断；进程崩溃/磁盘故障/拥塞不保证完整持久化。这是受限排障日志，不是无损业务账本。极端永久阻塞的 I/O 可能留下正在退出的 daemon，业务线程不等待它；没有无限重试。
- 文件不随会话停止、诊断 5 分钟保留期或 REST 最近 20 条窗口清空；容量轮转仍会覆盖旧记录。保存前后都不会把运行目录作为静态资源发布。不做自动上传、导出接口或远程保存。
- 本机查看可用 `rg '"phase":"FAILED"|"reason":"QUEUE_FULL"|"phase":"COMPLETED"' runtime/diagnostics/gifts/gift-trace-*.jsonl`，再用 sessionId/taskId 筛选；文件名不是严格时间顺序。

## 测试结果

| 命令/验证 | 结果 |
| --- | --- |
| `./scripts/maven.sh -B -ntp -pl backend '-Dtest=CollectorCredentialsTest,DouyinSessionTest' test` | 初始凭据接线 15/15 通过，保留 DOUYIN 生命周期 |
| `./scripts/maven.sh -B -ntp -pl backend '-Dtest=CollectorCredentialsTest,CollectorConfigurationIntegrationTest,GiftBusinessTraceTest,GiftTraceLogTest' test` | 最终新增专项 **23/23** 通过，2026-11-02 15:15:44 +08:00，日志 `/tmp/bigfish-gift-targeted-final.log` |
| `./scripts/maven.sh -B -ntp test` | 最终 **277 项：275 通过、2 项真实 NLS/Token 默认跳过，0 failure/error**，2026-11-02 15:16:51 +08:00，22.335 秒；日志 `/tmp/bigfish-gift-full-final.log` |
| 凭据与 Spring | 默认 guest 不读取 external 凭据；external 构造与过期复查；缺失/格式错误/未知模式/属性读取异常的固定码；LOCAL_VALID + UNVERIFIED 脱敏日志；Spring external 缺凭据正常启动、查询和 MOCK 可用、DOUYIN 明确 503 |
| 礼物 | 模板不调用模型、重复/合并及主任务关联、满队列 50 次拒绝容量不涨、TTL、LIKE 不产生语音/礼物日志、合成失败后成功、播放 FAILED/CANCELLED 区分、重复 ACK、停止后的迟到语音、断线和损坏日志观察者 |
| 文件 | 80+ 行跨重启保留、历史过期不清文件、固定槽轮转字节上限/完整 JSON、慢写入队列容量与 dropped、关闭释放线程、文件锁释放、目录/文件符号链接拒绝、运行中写入失败一次停用文件且控制台继续 |
| 既有回归 | 配置持久化/CAS、DOUYIN/MOCK 隔离、关播/失败根因、COLLECTOR_STOPPING、实际回环 WebSocket/音频/租约、PCM→WAV、STATIC/RAM 和 Live2D 资源测试均保留并通过 |
| 差异与基线 | `git diff --check`、staged check、`git merge-base --is-ancestor main HEAD` 通过；禁止修改目录与基线差异为空 |

测试仅使用人工凭据、Mockito provider、本地替身、临时 SQLite/日志和回环 WebSocket；没有读取真实 Cookie、连接真实房间、调用云模型/语音/Token 或停止主窗口服务。本轮未额外打包或启动独立开发服务。新增测试首次编译引用了不存在的语音错误枚举，已改用现有 `PROVIDER_UNAVAILABLE`；没有删除/弱化原断言。测试经授权在沙箱外执行以支持 JVM attach/本机回环端口。

## 待集成与未验证项

1. 用户/主窗口安排启用 external 及可选文件保存的时机；本轮不更换正在直播进程的环境。**真实 external 登录有效性、真实评论/礼物接收、真实礼物到 NLS/浏览器发声均未在本轮验证。** 本地测试不能替代这些验收。
2. 本轮日志起点是 EventIngress；采集器在发出 LiveEvent 之前的帧解析、过滤、连击增量和去重仍只见其既有聚合诊断。没有把上游消息 ID、用户标识或 Cookie 写进日志，也未建立新的采集↔业务逐条关联。若需要此能力，先由用户与主窗口确认脱敏关联方案及采集边界，本轮未实现或联系其他窗口。
3. 公共接口无需新增。本轮新增固定错误值 `DOUYIN_CREDENTIAL_MODE_INVALID`、`DOUYIN_CREDENTIALS_MISSING/INVALID/EXPIRED` 沿用现有错误结构；若主窗口需要完善 OpenAPI 错误示例或前端提示，由主窗口统一维护。REST 最近 20 条不扩大，文件状态也不扩展成公共接口。
4. 若后续 collector 的凭据校验规则改变，主窗口应同时审核工厂预校验一致性；可以再讨论提取共享公开校验入口，本轮不越界改 collector。

---

# Live2D 外部静态资源服务交接（2026-11-02）

本节为本轮最新交付；下方历史内容保留。工作树 `bigfish-worktrees/backend`，分支 `feature/backend-core`。

## 基线与提交

- 开工时目录/分支正确，工作区干净，HEAD 为 `e1db461`；先快进合入当时最新本地 main **`15be0b96c0e71fc8eb29c6320f33fe03ac74cb8a`**。
- 资源服务实现提交：**`be893e494057cf12aacc573e0f911f9b7bce812b`**（`feat(backend): serve bounded external avatar assets read-only`）。
- 交付前 main 新增许可证文档。实现提交后在干净工作区合入 **`d8fccd2376caf603123c056d81ba42a7ccaa1490`**，本分支合并提交/最终代码基线为 **`ee56fc0934617ea7804b01b5f39a17c55394123c`**。该增量仅为 LICENSE、README、THIRD_PARTY_NOTICES；`git diff be893e4 HEAD -- backend` 为空，已验证的 Java/配置/测试未变化。main 祖先检查通过。
- 本文单独提交，文档提交 SHA 见本轮最终回复，或 `git log -1 --format=%H -- docs/handoffs/backend.md`。全部提交仅在本地功能分支；未合并回 main、未推送、未联系其他窗口。

## 实际文件范围

1. `backend/src/main/java/cn/bigfish/configuration/AvatarAssetsConfiguration.java`：启动目录校验、独立静态资源映射、原始 URI 防护、真实路径约束、MIME、缓存验证与流式文件响应。
2. `backend/src/main/resources/application.yml`：仅增加 `bigfish.avatar.assets-dir: ${BIGFISH_AVATAR_ASSETS_DIR:}`。
3. `backend/src/test/java/cn/bigfish/core/AvatarAssetsIntegrationTest.java`、`AvatarAssetsDisabledTest.java`：10 项资源专项测试。
4. `docs/handoffs/backend.md`：在顶部增加本轮交接，保留历史内容。

没有修改 frontend、采集、模型回复、语音、播放调度、公共 contract/OpenAPI、数据库结构、脚本或依赖。既有 PageController 无需修改；新增 URI guard 只对 `/avatar-assets/` 原始请求生效，防止容器先将穿越路径规范化后转交其他控制器。

## 配置与目录

启动环境变量 **`BIGFISH_AVATAR_ASSETS_DIR`**，对应 Spring 配置 **`bigfish.avatar.assets-dir`**。必须指定已有、可读且可进入的**绝对目录**；仅在启动时读取，变更根目录需重启后端。HTTP 请求和业务配置 API 不能更换目录。

```text
/绝对路径/bigfish-avatar-assets/
  models/
    BigFish/
      BigFish.model3.json
      大肥鱼.moc3
      BigFish.physics3.json
      textures/texture_00.png
      expressions/笑脸.exp3.json
  sdk/
    live2dcubismcore.min.js
```

模型入口：`/avatar-assets/models/BigFish/BigFish.model3.json`；Core 入口：`/avatar-assets/sdk/live2dcubismcore.min.js`。中文文件名、UTF-8 编码路径及目录内正常相对资源引用可用。这里只放允许公开的模型文件；不要混放凭据或运行数据。

开发启动示例，在本工作树执行（脚本继续读取自己的 `runtime/worktree.env`，端口 18081）：

```bash
BIGFISH_AVATAR_ASSETS_DIR="/绝对路径/bigfish-avatar-assets" ./scripts/dev-backend.sh
```

JAR 启动示例（`runtime` 为本实例已存在的独立数据目录）：

```bash
BIGFISH_PORT=18081 BIGFISH_DATA_DIR="$PWD/runtime" \
BIGFISH_AVATAR_ASSETS_DIR="/绝对路径/bigfish-avatar-assets" \
java -jar backend/target/bigfish-server-0.1.0-SNAPSHOT.jar
```

无需启动旧 Python 项目。启动不下载 SDK、不扫描磁盘、不创建直播会话；本轮没有复制真实模型、Core 或旧项目凭据。

## HTTP 与访问范围

| 情况 | 行为 |
| --- | --- |
| 未配置/空目录配置值 | 应用正常启动，资源 GET/HEAD 返回 404 |
| 显式不存在、非目录、不可读/不可进入、相对目录或过宽根目录 | 启动失败，固定 `AVATAR_ASSETS_DIR_INVALID`，说明配置项及所需条件；不携带原始路径/异常原因，不回退其他目录 |
| 合法 GET / HEAD | 200；HEAD 无响应体且保留正确 Content-Length；文件通过 Spring Resource 流式读取 |
| Range / If-None-Match | 支持 206 分段与 304 验证；不会为计算 ETag 读取整个文件 |
| 缺文件、目录、非允许文件 | 404；不会返回 SPA index.html，不提供目录列表 |
| POST / PUT / PATCH / DELETE / OPTIONS | 405，Allow 为 GET, HEAD；不支持上传、删除、代理或服务端执行 |
| 穿越、绝对路径、编码/双编码逃逸、反斜杠、隐藏段、分号路径参数 | 应用拒绝 404，部分非法编码由容器提前返回 400 |
| 符号链接 | 每个请求检查真实路径；必须仍在资源根内，真实目标也必须是允许资源。逃逸及伪装指向 config.json 的链接返回 404 |

允许范围为 `models/<模型目录>/...` 下的 `.moc3`、`.png`，以及 `.model3.json`、`.physics3.json`、`.pose3.json`、`.motion3.json`、`.exp3.json`、`.cdi3.json`、`.userdata3.json`、`.motionsync3.json`。SDK 只允许 `sdk/live2dcubismcore.min.js`。任意 JSON、其他脚本、配置、密钥、SQLite、音频、HTML 不属于发布范围；目录列表及隐藏文件均关闭。

MIME：JSON 为 `application/json`，moc3 为 `application/octet-stream`，PNG 为 `image/png`，Core JS 为 `text/javascript`；设置 `X-Content-Type-Options: nosniff`。

缓存为 `Cache-Control: no-cache, private`，含合法路径的缺文件响应；拒绝路径使用 no-store。弱 ETag 来自高精度修改时间、长度和文件身份的摘要，不保留文件内容缓存，关闭只有秒精度的 Last-Modified 验证。同秒、同长度的正常写入或原子替换可刷新；若维护工具刻意保留所有这些元数据，需要更新时间戳或变更 URL 版本参数。根目录变化需重启，目录内正常文件更新不需重启。

## 验证结果

| 验证 | 实际结果 |
| --- | --- |
| `./scripts/maven.sh -B -ntp -pl backend '-Dtest=AvatarAssets*Test' test` | 首批 9/9 通过；随后增加目录进入权限回归，最终专项共 10 项随完整测试全部通过 |
| 目录可读但不可进入的回归 | 修补前 1 failure，复现未拒绝问题；增加进入权限检查后通过 |
| 最终 `./scripts/maven.sh -B -ntp test` | **254 项：252 通过，2 项真实 NLS/Token 默认跳过，0 failure/error**；2026-11-02 01:12:34 +08:00 完成，25.977 秒 |
| `./scripts/maven.sh -B -ntp -DskipTests package` | 最终 BUILD SUCCESS，01:13:19 +08:00 完成；无新依赖或外部资源打包 |
| `scripts/dev-backend.sh` 启动，18081 | 人工资源 GET/HEAD、中文、MIME、404、穿越/符号链接、Range/ETag、页面/API 路由通过，资源读取后会话仍 STOPPED |
| `java -Xmx96m -jar ...`，18081 | 同样通过；**两个并发 30 MiB 文件响应**正常，客户端按 64 KiB 块读取，不累计完整文件 |
| JAR 未配置 / 显式无效目录 | 未配置正常启动且资源 404；无效目录固定诊断并退出，不回退 |
| JAR 检查 | 没有 moc3、model3.json、PNG、Core 或 avatar-assets 条目；3 个资源配置编译类与 JAR 内容逐字节一致，环境变量映射已打包 |
| 既有行为 | 完整回归包含配置持久化/CAS、真实回环 WebSocket、MOCK 评论/礼物音频、租约与停止隔离、采集修复；原断言均保留 |
| 差异检查 | `git diff --check` / staged check 通过 |

最初测试遇到沙箱 JVM attach 和端口绑定限制，按权限流程在沙箱外重跑，未删除或弱化断言；另修正了一处新增测试的 AssertJ API 用法。最后四种启动模式均在最终代码和最终 JAR 上串行复验通过。

人工资源及独立 SQLite 位于本工作树忽略目录 `runtime/avatar-smoke-*/`；每次运行仅占用 18081，并在结束时关闭本轮创建的进程，最终端口无监听。没有停止主项目/Luna，没有真实抖音、DeepSeek、NLS 或 Token 调用。临时文件未提交，也没有执行批量删除。

证据：`/tmp/bigfish-avatar-targeted-unrestricted.log`、`/tmp/bigfish-avatar-permission-red.log`、`/tmp/bigfish-avatar-final-test.log`、`/tmp/bigfish-avatar-final-package.log`、`/tmp/bigfish-avatar-final-smoke.log`。临时启动验收脚本为 `/tmp/bigfish-avatar-smoke.py`。

## 主窗口接线与未验证项

1. **开发模式 Vite 需要转发 `/avatar-assets/`。** 当前 `frontend/vite.config.ts` 只代理 `/api`、`/actuator`、`/ws`。建议由前端/主窗口增加 `'/avatar-assets': process.env.BIGFISH_API_TARGET || 'http://127.0.0.1:18080'`；backend 工作树前端环境继续指向 18081。后端不开放跨域来替代同源代理。打包同源页面无需代理。
2. 本轮仅提供资源服务，不新增上传管理、业务 DTO、OpenAPI 或 WebSocket 字段，公共契约无调整需求。目录路径只留在服务端启动配置，不应回传浏览器。
3. 本轮未复制/渲染真实模型或 SDK，真实 Live2D 加载、纹理解码、动作/表情、嘴型、授权及前端失败展示由前端和主窗口联调。若实际模型引用非上述约定命名的文件，应先提供具体引用并审核发布范围，不扩大到任意目录/文件。
4. 没有运行 npm 或 bundle-ui 构建；启动验证用独立人工 index 页面核对 `/admin`、`/stage` 转发和缺失资源不落到 SPA，不能据此宣称真实 Vue/Live2D 渲染已验收。外部模型/Core 必须继续留在外部目录，主窗口正常打包 UI 时也不能复制进 static、target/classes 或 JAR。

---

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
