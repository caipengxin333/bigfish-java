# 入站 MALFORMED 解码修复与首次有效接收（2026-11-02）

本节为最新交接；下方 2026-11-01 页面初始化及首轮记录保留历史背景，不能覆盖本节实测结果。

## 提交、基线与范围

- 工作目录：collector worktree；分支：feature/douyin-collector。开工 pwd / branch / status 正确且干净，快进合入最新 main **ab9d532a4ed8b8f5feb64badccd48470a85db330**。
- 实现与测试提交：**4bbab803d1bf4eca6b2c4d7e6763a741f90519d7**（fix(collector): decode protobuf payloads with independent compression headers）。
- 实现提交后工作区干净，交付前 git merge --no-edit main 返回 Already up to date，祖先检查通过。本文另作文档提交，其 SHA 由本窗口最终回复提供。
- 修改仅 collector 包及对应 collector 测试、本交接。没有改页面初始化、原生 signer、连接参数、Vue、会话管理、模型、语音、公共 DTO / REST / 依赖版本；没有修改旧项目或主工作树，没有批量删除、推送、PR 或联系其他窗口。

## 明确根因与证据

**失败发生在 DouyinProtocol.decode 的 PAYLOAD 编码判定阶段，早于 gzip 解压和 Response 解析。** 旧实现把 PushFrame.payload_encoding（字段 6）当作压缩方式，只允许空值 / gzip / identity。实际入站结构样本中字段 6 是 **pb**（wire type 2、长度 2），载荷是 gzip；pb 表示 Protobuf 序列化，与压缩是不同维度。旧逻辑因此对这种有效帧抛出异常，consume 汇成 MALFORMED，整批消息和 ACK 都不可达。

证据链：

1. 固定 revision 协议定义核对：PushFrame 5 为重复 PushHeader，6 为 payload_encoding，7 为 payload_type，8 为 payload；Response 1 为重复消息、5 为 internal_ext、9 为 need_ack。没有猜测或调整这些字段编号。
2. 修复前新增人工回归：手写 pb + compress_type=gzip 的外层字节前缀，旧实现报 Invalid protocol packet；仅头部 im-internal_ext 的 ACK 回归也失败。两项测试为 1 failure + 1 error。完整独立手写明文 pb 评论字节向量另行验证，避免仅由生产解码器和测试编码器共享假设。
3. 本轮唯一一次真实 receive 的结构样本确认 **PB + gzip**。首个完整帧 10536 字节，由 3 个 JDK 二进制回调分片组成；压缩载荷 10217 字节，解压后 53293 字节，Response 含 25 条消息。这里只保留长度和字段形状，没有保留真实字节、字段值、用户信息或评论正文。
4. 修复后 73 个完整帧均通过 PUSH / PAYLOAD / DECOMPRESS / RESPONSE / MESSAGE_LIST，0 分片失败、0 队列过期或满、0 解码或归一化 MALFORMED；实际派发评论 24 条。因此本次不是以 CONNECTED 或 HTTP 200 代替接收验收。
5. 上次 92 个错误包没有保存原帧，不能逐帧追溯这 92 次是否完全相同。本次已直接确认一个会导致旧实现整批拒绝的线上结构，并验证修复后有效接收；不对未保存的历史帧虚构额外证据。

## 修复与内部诊断

- 分离载荷序列化与压缩：明确接受 pb，读取白名单头 compress_type，支持 gzip / identity；保留旧 payload_encoding=gzip 和 gzip magic 兼容。未知编码、冲突压缩头、坏 gzip、解压超限仍拒绝，没有无限放宽限制。
- ACK：优先逐字节回显头部 im-internal_ext；无头部扩展时，仅 need_ack 为真才使用 Response.internal_ext。头部扩展自身可要求 ACK，不能只看 Response 标志。仍使用本帧 logId；缺 logId、扩展超 8192 字节或冲突头拒绝。ACK 在归一化/业务派发前发送；失败不伪计成功。真实 73 次 ACK 均使用头部来源；“无 need_ack 但有头部”的分支为本地覆盖，不额外声称其真实占比。
- Response 列表中单个错误 wire type / 坏 envelope / 坏具体消息单独记录，保留同批其他有效消息和有效 ACK。未知 method 计 UNKNOWN，未尝试将其视为结构损坏；未知 method 文本不记录。
- ProtoWire 保持原有 wire / UTF-8 / varint / 长度检查，为失败附加固定原因码和数字字段信息；重复标量仍按 wire 规范取最后一个值。没有引入通用生成式 Protobuf 或复制 proto。
- 内部 InboundDiagnostics 覆盖 ASSEMBLY、QUEUE、PUSH、PAYLOAD、DECOMPRESS、RESPONSE、ACK_META、MESSAGE_LIST、ENVELOPE、BODY、COMMON、ROOM、USER、GIFT、EVENT、NORMALIZE。只存固定枚举与长度/数量/字段号/wire type。
- 硬上限：128 个计数键、64 个结构摘要，每个阶段/原因最多 3 份，每份最多 12 个字段形状；成功摘要最多 32 份，为失败预留容量。不会保存异常对象/message/cause、原帧、hex、正文、昵称、UID、Cookie、签名 URL 或凭据。仅独立 probe 输出摘要，未扩展 REST DTO 或主页面。
- 保持 2 MiB 单帧及解压上限、4096 字段/对象、512 消息/Response、队列容量与 TTL、房间一致性、取消和退出保护。新增头部处理最多 64 项 / 总 64 KiB，ACK 扩展仍 8 KiB。
- probe 新增可选 BIGFISH_DOUYIN_PROBE_MAX_ATTEMPTS（1–3，默认 3）；本次显式 1，不自动反复探测。默认测试不联网。

## 来源与许可

仅核对公开字段和行为，解析/诊断仍为本项目独立 Java 实现；没有复制或执行旧 AI-Vtuber AGPL 脚本、第三方 generated 类或上游 proto，没有新增依赖。

| 参考 | 固定版本 / 许可 | 本轮用途 |
| --- | --- | --- |
| [jwwsjlm/douyinLive message_decode.go](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/message_decode.go)、[utils.go](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/utils/utils.go) | af34f7c2ea2c4df90c6f4659e967fb31815d9255；[MIT，Copyright 2025 今晚打打](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/LICENSE) | 核对头部 compress_type 及 im-internal_ext ACK 语义；未复制 Go 实现 |
| [douyinlive-proto new_douyin.proto](https://github.com/jwwsjlm/douyinlive-proto/blob/0d661be57c8f80947054b2ef0cd1ac2fdce3cc4b/protobuf/new_douyin.proto) | v0.1.3 = 0d661be57c8f80947054b2ef0cd1ac2fdce3cc4b；该文件 blob 8f336c61ccd9816eea76ff05b187c0da9f503fe3；[MIT，Copyright 2026 jwwsjlm](https://github.com/jwwsjlm/douyinlive-proto/blob/0d661be57c8f80947054b2ef0cd1ac2fdce3cc4b/LICENSE) | 只核对上列字段事实与消息映射，不纳入 schema / 生成类 |
| [Protobuf wire format 官方说明](https://protobuf.dev/programming-guides/encoding/) | 在线只读参考，2026-11-02 核对 | 字段 tag、wire type、重复标量语义 |

这些是非官方网页协议参考，不代表平台承诺的稳定开放 API；根许可证也不被解释为旧 AGPL 代码的替代授权。已有 native signer 的 MIT 声明与采用范围保持不变。

## 自动测试与本地替身证据

| 命令 / 阶段 | 结果 |
| --- | --- |
| 基线 ./scripts/maven.sh -B -ntp -pl backend '-Dtest=cn.bigfish.collector.*Test' test | 沙箱内 12 项因本机 bind 权限失败；按权限流程重跑 **83/83 通过**，没有跳过或弱化断言 |
| -Dtest=DouyinInboundStructureTest test（修复前） | 2 项：1 failure + 1 error，复现编码拒绝和缺 ACK |
| -Dtest=DouyinProtocolTest,DouyinInboundStructureTest test（修复后扩展） | **15/15 通过** |
| 全部采集测试（最终代码） | **90/90 通过**，6.292 秒，2026-11-02 00:18:39 +08:00 完成 |
| ./scripts/maven.sh -B -ntp test（最终代码） | **244 项：242 通过，2 项真实 NLS/Token 默认跳过，0 failure / error**；22.915 秒，00:19:25 +08:00 完成 |
| git diff --check / git diff --cached --check | 通过 |

回归覆盖：手写独立明文 pb 评论向量、pb + gzip 头、旧 gzip/无标记压缩、解压限额、非法长度/varint/UTF-8、合法未知字段、重复标量、未知类型、同批单条坏消息隔离、ACK 头优先/回退/冲突/上限、诊断采样及隐私。原生 signer + 实际回环 HTTP/WSS 链路加入 pb + gzip 分片及头部 ACK 断言，保留礼物 1/2/1 增量、同用户连击、重复终包、不同用户隔离、ENDED、幂等 stop 与资源释放断言。既有停止握手/接收/退避、重连、内部 roomId 变化测试全部保留。

全部本地消息均为人工数据。本地 ENDED、礼物和故障注入不是下述真实验证的替代证据。没有运行 DeepSeek/NLS/Token 真实调用。

## 本轮真实接收：独立 probe、自然消息

测试房间为用户指定网页号 440132407257，未操作主项目会话或选择其他房间。

| 分项 | 本轮实际结果 |
| --- | --- |
| 只读 resolve | LIVE，roomResolved=true；HTTP **2**，2453 ms，在途 0；00:19:44 +08:00 完成 |
| 一次 receive | **60 秒总观察预算**（含初始化/握手；停止清理后总耗时 60117 ms）；HTTP **2**、签名 **1**、WSS 尝试 **1**、CONNECTED **1**；00:21:06 +08:00 完成，probe / Maven 均成功 |
| 完整帧 / 阶段 | ASSEMBLY / QUEUE / PUSH / PAYLOAD / DECOMPRESS / RESPONSE / MESSAGE_LIST 各 **73** 次成功；无失败阶段，无摘要键溢出 |
| 消息包 | ENVELOPE **137**；未知类型 **44**，单独忽略；已识别评论 **25**、入场 **46**、点赞 **22** |
| 实际归一化并进入本地 sink | **COMMENT 24、ENTRANCE 46、LIKE 22**；重复评论去重 **1**；FOLLOW **0** |
| 礼物 | **GIFT 0、giftUnits 0：真实礼物未验证**；不能据此断言平台没有礼物或礼物覆盖完整 |
| 协议 | ACK **73**（ACK_HEADER），heartbeat **6**，MALFORMED **0**，reconnect **0**；lastError 为空 |
| 停止 | 停止前 CONNECTED、停止后 STOPPED；sourceThreads / dispatchThreads / queuedEvents / attachedTransports / httpInFlight / signingWorkers **全部 0**，独立进程退出 |
| 未发生的场景 | 真实关注、真实关播 ENDED、断线重连和长期运行未验证；资源计数仅指本采集器，不等于 JVM 所有线程 |

抖音请求账本合计 **HTTP 4、WSS 1**，一次 receive，没有额外第二轮接收、自动延长或主动互动。仅发送协议 ACK / 心跳，没有评论、点赞、关注或购买礼物。所有页面/业务帧只在内存处理，没有保存真实 HTML/完整帧或用户数据。真实收到了 22 条点赞事件，不代表采集器发送了点赞。

安全诊断日志（只包含固定标签、结构及计数，不提交日志）：
- /tmp/bigfish-decode-resolve.log
- /tmp/bigfish-decode-receive.log

本地回归日志：
- /tmp/bigfish-decode-baseline.log（沙箱失败）及 /tmp/bigfish-decode-baseline-approved.log
- /tmp/bigfish-decode-red.log、/tmp/bigfish-decode-expanded.log
- /tmp/bigfish-decode-collector.log、/tmp/bigfish-decode-full.log

## 主窗口审核、合并及重启

1. 主窗口审核实现 4bbab80 及本交接文档提交后，按既定流程合并 feature/douyin-collector；本窗口不更新 main、不推送。
2. 无公共接口、配置迁移或新依赖提案。原有游客工厂、页面初始化和原生 signer 沿用；新诊断只在采集内部 / 独立 probe。
3. 合并后由主窗口重跑后端回归并**重启实际运行的后端**（IDEA 需重启 Java 进程；JAR 需重新打包后运行新产物）。页面刷新不会替换旧 Java 解码类。开发启动用主窗口自身的 scripts/dev-backend.sh，保留各 worktree 端口和数据隔离。
4. 用户主会话及“真实消息 → 模型 → 语音 → 舞台”本轮未操作、未验收；不能把本地 sink 的通过写成主会话付费全链路通过。供应商状态、README / 公共能力文案如需调整，由主窗口统一处理；本分支未自行改 true 或 VERIFIED。
5. 后续自然出现礼物时再验真实数量/连击/用户隔离，并在获得授权后另验真实关播和断线恢复。本次不延长等待、不送礼造数据。当前交付已经获得真实有效评论，礼物仍明确未验证。

---

# 真实抖音房间初始化修复（2026-11-01）

本节是最新交接；下方首轮记录及 `phase3-collector.md` 保留历史背景。工作树：`bigfish-worktrees/collector`，分支：`feature/douyin-collector`。

## 基线、提交与范围

- 开工目录/分支正确，工作区干净；从 `d7b6fdc` 快进合入最新本地 main `5d93ed5f9db94781a92835c30a3ff71d47bfa71a`。
- 实现提交：**`2129a37fa2cb3a875710e60940cf6786aea90595`**，`fix(collector): accept current guest room hydration`。
- 实现提交后在干净工作区再次执行 `git merge --no-edit main`：Already up to date；祖先检查通过。未更新 main、未合并其他功能分支、未推送或创建 PR。
- 修改仅为 `collector/DouyinGuestSession.java`、`collector/DouyinRoomPage.java`，对应 `DouyinGuestSessionTest`、`DouyinRoomResolverTest`、`NativeCollectorPipelineTest` 及人工夹具 `collector/room-pace-bootstrap.html`。本文件记录交付。未修改公共 DTO、依赖、前端、会话管理、模型、语音或签名算法。

## 已定位并修复的三个原因

1. **页面下发与 UA 不兼容。** 原通用 Java UA 请求游客首页成功，但房间请求返回 HTTP 200、空正文、非 HTML 类型；调整 Origin/Referer 的只读对照仍为空。使用固定页面兼容 UA 后取得完整房间 HTML。生产改为带 `BigFishCollector/0.1` 标识的固定 Chrome/WebKit 兼容字符串，首页、房间、签名输入及 WSS 使用同一值。这是 HTTP 页面兼容标记，不是安装/运行 Chrome，不增加操作系统、屏幕或其他指纹字段；没有 UA 轮换、执行页面脚本或处理验证挑战。之前“通用 Java UA 可直接获取房间”的假设被真实响应否定。
2. **Pace 流的初始空状态。** 页面先发送 `roomStore.roomInfo={}`，再发送完整的 `roomInfo.roomId / room.id_str / room.status / anchor`。原实现遇到第一个空对象立即失败。现在仅跳过确切的空对象，仍必须找到有效完整房间；仅空状态、null、数组、残缺信息、冲突房间/状态均失败。没有取消网页号与内部房间校验。
3. **游客 ID 扫描被无关展示数据阻断。** 真实 LIVE 页面中展示数组超过旧遍历深度 24；房间元数据已经解析成功，`guestId()` 却提前抛出 `PAGE_STRUCTURE_CHANGED`。现在遍历预算与现有 JSON 深度预算统一为 64。保留页面 2 MiB 上限、超深拒绝、重复字段/访客 ID 冲突检查；仍只使用明确的 `user_unique_id`，不使用主播 ID 或固定替代值。

夹具只保留上述形状：空初始 store、完整 store、30 层无关展示数组；所有房间/主播/访客标识及文本均为人工数据。没有提交真实 HTML、Cookie、访客 ID、用户消息、签名 URL 或原始消息帧。

## 分类与回归

- 空 HTTP 200 响应证据不足，仍归 `PAGE_STRUCTURE_CHANGED`，不能据此断言房间号错误、已下播或登录失效。
- 明确 `room.status=4` 才是 ENDED；明确登录/验证/地区页面及对应 HTTP 状态继续按既有固定码处理，不回退其他身份。
- 回归覆盖空初始状态前后顺序、只有空状态、残缺/冲突房间、LIVE/ENDED、深层展示数据、缺失或冲突游客 ID、超深拒绝、登录与验证页、空房间响应不签名。
- 既有本机完整链路现在使用新流式夹具：游客首页 Cookie → 房间 → 原生 signer → 实际 JDK 回环 WSS → 评论/礼物/关注/入场 → ACK/心跳 → ENDED/资源释放。替身模拟“通用 UA 首页正常、房间为空”，没有外网访问。
- 保留原有房间变化、重连、取消、资源释放、去重/礼物增量及全部后端回归断言。

## 本地测试结果

| 命令/检查 | 结果 |
| --- | --- |
| `./scripts/maven.sh -B -ntp -pl backend '-Dtest=cn.bigfish.collector.*Test' test`（基线） | 78/78 通过 |
| `-Dtest=DouyinRoomResolverTest,NativeCollectorPipelineTest test`（新增回归、修复前） | 14 项，1 failure + 1 error，复现 UA 与初始空 store 问题 |
| 全部采集回归（前两处修复） | 80/80 通过 |
| `-Dtest=DouyinGuestSessionTest test`（深层回归、深度修复前） | 11 项，1 error，复现游客递归深度问题 |
| 全部采集回归（三处修复） | 82/82 通过 |
| `./scripts/maven.sh -B -ntp test`（最终代码与空响应补充回归） | **237 项：235 通过，2 项真实 NLS/Token 默认跳过，0 failure / error；其中 collector 83/83 通过** |
| `git diff --check`、`git diff --cached --check` | 通过 |

最终完整回归 2026-11-01 23:51:24 +08:00 结束，耗时 25.949 秒。仅使用本地替身和回环临时端口；没有调用 DeepSeek、NLS 或 Token 签发。没有删除文件、跳过既有保护断言或停止其他窗口进程。

本机测试日志：`/tmp/bigfish-collector-room-baseline.log`、`/tmp/bigfish-room-red-test.log`、`/tmp/bigfish-room-depth-red.log`、`/tmp/bigfish-room-final-collector-test.log`、`/tmp/bigfish-room-full-test.log`。

## 真实只读验证：分层结果

用户先提供 `549511642024`，随后告知该房间已结束，并授权改用 `440132407257`。所有请求来自独立游客上下文，没有复制主项目配置/数据库/密钥或浏览器登录 Cookie。

| 分项 | 实际证据 | 结论 |
| --- | --- | --- |
| 原房间 | 通用 UA 的 3 组对照均为首页正常、房间空响应；兼容 UA 取得 HTML，观察到完整房间 `status=4`，当时完整解析仍被前置空 store 阻断 | 与用户告知已结束一致；没有对原房间启动接收，不把空响应当作下播依据 |
| 新房间最终 resolve | 正式 `DouyinCollectorProbe`：`state=LIVE credentials=guest roomResolved=true`；HTTP 2，2307 ms，在途 0 | **房间与游客初始化通过**；没有 WSS 或模型/语音调用 |
| 新房间 receive | 60 秒观察预算；总耗时含停止清理 60123 ms；HTTP 2、签名 1、WSS 尝试 1、连接成功 1，重连 0 | **握手通过**，停止前 CONNECTED |
| 自然消息 | COMMENT 0、GIFT 0、FOLLOW 0、ENTRANCE 0、LIKE 0；ACK 0、心跳 6、MALFORMED 92 | **真实评论接收未通过，真实礼物未验证**；不能据此断言直播间没有发言或礼物 |
| 停止与资源 | STOPPED；sourceThreads / dispatchThreads / queuedEvents / attachedTransports / httpInFlight / signingWorkers 均 0，独立进程结束 | 本采集器资源释放通过；不把这些计数解释为整个 JVM 线程数 |

接收输出的固定最后错误为 `MALFORMED`。独立 probe 因没有有效评论而返回 2，Maven 返回 1 / BUILD FAILURE，这是接收验收未通过的真实结果，不是本地单元测试失败；没有把握手成功冒充端到端成功。

### 本轮真实请求账本

| 只读检查 | 房间 | HTTP 发起数 | WSS 尝试数 |
| --- | --- | ---: | ---: |
| 原请求结构检查 | 原房间 | 2 | 0 |
| 导航头对照 | 原房间 | 2 | 0 |
| 空响应头名称检查（不输出值） | 原房间 | 2 | 0 |
| 固定兼容 UA 对照 | 原房间 | 2 | 0 |
| 前两处修复后的正式 resolve（游客深度失败） | 新房间 | 2 | 0 |
| 房间与游客解析分步定位 | 新房间 | 2 | 0 |
| 游客遍历深度定位 | 新房间 | 2 | 0 |
| 最终正式 resolve | 新房间 | 2 | 0 |
| 一次 60 秒 receive | 新房间 | 2 | 1 |
| **合计** | | **18** | **1** |

临时诊断工具最初遇到 JDK 路径、编译和 source launcher 包访问问题，均在网络调用前退出，额外 HTTP/WSS 为 0。上表计数是发起数。所有结构检查只输出字段类型、固定分类、布尔值及计数；真实响应仅在内存中使用，没有保存页面或帧。

正式证据日志：`/tmp/bigfish-room-final-resolve.log`、`/tmp/bigfish-room-receive.log`。结构定位日志：`/tmp/bigfish-room-structure.log`、`/tmp/bigfish-room-navigation-structure.log`、`/tmp/bigfish-room-header-structure.log`、`/tmp/bigfish-room-compatible-structure.log`、`/tmp/bigfish-room-new-structure.log`、`/tmp/bigfish-room-guest-structure.log`。

## 集成与剩余事项

1. 主窗口审核并合入本地功能分支后重启后端，才能使用新 Java 解析实现。公共 DTO、OpenAPI、线上播放协议和依赖保持兼容，本轮无需新增公共接口。
2. **继续保留真实接收 UNVERIFIED。** 可以说明“本轮指定房间解析及握手已验证”，不能把供应商能力、CONNECTED 或本地链路测试升级为真实评论/礼物接收通过。公共能力文案及 README/provider 文档的统一更新交主窗口处理，本分支仅更新采集交接。
3. 下一项采集待办是 `MALFORMED`：需在另一次明确授权且在播的短测中，仅提取最小脱敏 wire 结构定位入站封装/压缩/字段解码差异，再补回归。本轮没有保存真实帧，因此不猜测具体协议根因，也没有额外启动第二轮接收或修改签名/协议算法。
4. 本轮没有验证真实礼物连击语义、长时间重连、主会话真实消息进入模型/TTS/舞台。页面兼容依赖平台当前结构，保留有界失败及固定错误分类；不得自动重试不同身份或绕过登录/验证限制。

再次只读 resolve 的命令（房间是否仍在播须当时确认；不是自动运行指令）：

```bash
env BIGFISH_DOUYIN_LIVE_PROBE=true BIGFISH_DOUYIN_ROOM=440132407257 \
  BIGFISH_DOUYIN_PROBE_ACTION=resolve BIGFISH_DOUYIN_CREDENTIAL_MODE=guest \
  ./scripts/dev-backend.sh -B -ntp \
  -Dspring-boot.run.main-class=cn.bigfish.collector.DouyinCollectorProbe
```

该脚本读取 collector 自身 `runtime/worktree.env`，独立 probe 不启动 Spring，不使用主项目数据库或 18080 服务，不触发付费调用。

---

以下为首轮历史交接，不能覆盖上方最新真实验证结果。


# Java 抖音消息采集器交接

任务/分支：抖音采集 / feature/douyin-collector。

交接日期：2026-10-30。工作目录仅 collector worktree；原项目只读参考，不修改主集成工作区文件。

## 提交与交付结论

| 提交 | 内容 |
|---|---|
| a3ac3acdcef2f185df39e0dc6d7819a36b0628f1 | 本任务基线 |
| 2e9801c25d37d58b0db4863aadbafa8bf355a80b | 有界 mock 回放、事件归一化、礼物增量与去重、首轮许可证调查 |
| adce44e6f056181716902c7bacf11f9a590e6ae0 | 独立有限字段 Protobuf/gzip 解析、ACK/心跳编码及合成 wire 测试 |
| 212037c7500e154cca53cca9fc447d9a0f0481e9 | 最终实现提交：JDK WebSocket、退避重连、停止竞态保护与会话隔离测试 |

本交接文件另作最后的文档提交；其 SHA 可由 `git log -1 --format=%H -- docs/handoffs/collector.md` 获取，代码验证对象是上述最终实现提交。所有提交仅在本地 feature/douyin-collector；未 merge、推送或创建 PR，未联系其他任务。

**可交付的是通过本地测试的独立采集组件，不是已完成线上验收的抖音接入。** 用户确认目前没有房间号，本轮未连接真实抖音直播间，也未执行旧签名脚本。真实评论、礼物、心跳、ACK、关播均未验证。

## 实际实现

- `MockCollector implements CollectorPort`（通过基类）：Supplier<InputStream> 提供人工 JSONL，每次 start 独立回放；mode 明确为 MOCK，文件 EOF 不伪装平台关播，需 ENDED 控制夹具或 stop 结束。
- `EventNormalizer`：schemaVersion=1，sessionId 原样保留，内部 roomId 不替换成网页号；支持 COMMENT/GIFT/FOLLOW/ENTRANCE/LIKE；payload 为白名单不可变 Map。金额字段缺失输出 null，明确的 0 保留，不换算收入。
- 礼物累计按 `(roomId,userId,giftId,comboId)` 转增量，零计数单礼物最低 1；去重消息 ID、终包重复累计与回退累计，互不合并不同用户/礼物/组。无 msgId 时使用有平台时间的稳定业务字段摘要；没有稳定时间则拒绝。
- `DouyinProtocol` / `ProtoWire`：独立编写 wire-format 子集解析；不复制 proto、上游代码或生成类。仅适配所需字段，支持 gzip/未压缩与观察到的两种礼物计数字段编码。未知消息忽略计数，单条坏消息不影响同包后续消息，坏外包不导致连接退出。分享不直接当作关注，只有 Social action=1 映射 FOLLOW，Control status=3 映射 ENDED。
- `JdkCollectorSocket`：真实 Java 21 HttpClient/WebSocket，分片拼接、有界输入队列、分片期限、大小检查。业务处理不进入 JDK 回调；协议线程负责 ACK、Ping 或二进制心跳，业务派发由独立线程执行，单个慢回调不阻塞网络。
- `DouyinCollector`：每次重连重新调用连接提供器；本会话归一化缓存跨重连保留；指数退避含随机扰动，短暂握手成功不重置退避。无入站活动触发重连。stop 关闭 socket、取消握手、打断退避、清空队列且幂等；关播停止重连并进入 ENDED，显式 stop 不会被迟到关播覆盖。
- `DouyinConnectionProvider`：房间解析/授权签名的注入边界，**没有内置实现**。可以报告已关播；同网页号解析到新内部 roomId 时结束旧会话。永久配置问题抛 UnavailableException 后进入 FAILED，不自动转 MOCK。
- `DouyinConnection`：目标只允许指定形式的抖音 WSS 主机和 push 路径、有限请求头；连接对象 toString 脱敏。无 Cookie/签名 URL/Key/原始帧的日志语句；诊断只保留固定错误类别、计数和耗时，不保留异常文本。
- 无 Spring 自动注册，不修改管理页、现有 API、contract、POM 或能力声明；没有调用 DeepSeek/TTS，也没有发送评论/购买礼物的实现。

修改范围严格限定为：`backend/src/main/java/cn/bigfish/collector/`、`backend/src/test/java/cn/bigfish/collector/`、`backend/src/test/resources/collector/`、`docs/handoffs/collector.md`。没有删除文件。

## 验证记录

| 命令/检查 | 结果 |
|---|---|
| `pwd`、`git branch --show-current`、`git status --short` | collector 路径、feature/douyin-collector，开工时工作区干净；恢复工作时保留全部已有修改 |
| `./scripts/maven.sh test` | 基线 5 项通过；首轮需要联网下载项目已固定依赖，已按沙箱权限流程执行 |
| `./scripts/maven.sh -B -ntp test` | 第一批 17 项通过；最后完整回归 **41 项，0 failure / 0 error / 0 skipped** |
| `./scripts/maven.sh -B -ntp -pl backend '-Dtest=cn.bigfish.collector.*Test' test` | 中间阶段 28 项通过；加入 loopback 测试后沙箱禁止 bind，随后完整回归在获准权限下全部通过 |
| Surefire XML 汇总及时间检查 | 7 个 suite 共 41 项；所有报告均晚于最后 Java 修改，确认额度中断前的测试已完成 |
| `git diff --check` / `git diff --cached --check` | 通过 |
| collector 源码日志扫描 | 无 System.out/err、printStackTrace 或 logger 输出；连接诊断不保存异常文本 |

41 项包括：EventNormalizerTest 7、MockCollectorTest 5、DouyinProtocolTest 8、DouyinCollectorTest 9、JdkCollectorSocketTest 4、CollectorLifecycleTest 3，以及既有 ManagementIntegrationTest 5。既有集成断言保留，仍验证 SCAFFOLD / douyinCollector=false 与配置落库行为。

新增生命周期测试曾因测试等待器重复调用带副作用的 start 失败，已修正等待逻辑，最终 41 项通过。沙箱内原有 Mockito 自附加 agent 和本机 bind 受限时均按权限流程重跑，没有跳过测试或弱化断言。

前端无修改，未运行 npm 安装/构建。没有启动持久开发服务：Spring 回归使用独立临时 SQLite，WebSocket 测试仅使用 127.0.0.1 临时端口；后续开发仍用 scripts/dev-backend.sh / scripts/dev-frontend.sh，已核实本工作树 runtime/worktree.env 的 18083/15176 配置。

### mock / 本机传输 / 真实验证区别

| 层级 | 已验证内容 | 能说明什么 |
|---|---|---|
| 人工 JSONL 和 wire 夹具 | schema、单礼物、累计增量、用户隔离、重复包、未知/损坏包、金额未知、过期与满队列 | 本地归一化和解析逻辑行为 |
| 内存传输故障注入 | 跨重连去重、退避上限、停止开关、闲置重连、永久错误、关播、旧会话丢弃、慢/异常 sink | 生命周期与故障处理行为 |
| 本机 RFC6455 对端 + 实际 JDK WebSocket | 分片、Ping/Pong、二进制 ACK、帧上限、队列上限、分片过期、取消握手 | 实际 JDK 传输可工作；**不证明抖音线上协议兼容** |
| 真实抖音 | **未执行，无真实收到的评论或礼物** | 无房间号且没有获准使用的签名提供器，不宣称完整链路完成 |

## 运行限制

- 默认输入/输出队列各 256 条，TTL 30 秒；消息/连击缓存各 8192 项、TTL 10 分钟。满队列丢弃新项并计数，不重试业务回调；ACK 表示协议接收而非 EventIngress/业务处理成功。去重超出 TTL/容量后不保证跨任意长时间重发幂等，业务层仍须独立校验/去重。
- 单帧和解压后默认上限 2 MiB；每个 protobuf 对象最多 4096 字段，每 Response 最多 512 消息，ACK 扩展最多 8192 字节。未知 wire group 不支持并按坏包处理；这不是通用 Protobuf 库。
- 心跳默认 10 秒，闲置 45 秒；退避上界依次 1、2、4…30 秒，每次使用上界的 75%–100% 随机延迟；握手最长约 12 秒，每次发送最多等 5 秒。未动态采纳 Response.heartbeatDuration，也未进行长时间线上稳定性测试。
- 房间页面解析、Cookie 获取、安全凭据生命周期、连接参数生成/签名、实际状态接口轮询均未实现；provider 必须每次重连刷新参数、响应线程中断并自设总超时。JDK 传输中的已知主机 allowlist 可能随平台变化，需要有依据地更新。
- 缺 userId/comboId 时不按昵称猜测连击身份，只能按 msgId 去重；无 msgId 的相同业务字段/平台时间存在碰撞边界。平台字段语义、计数及 Control/Social 常量都仍需真实样本验证。
- stop 清空未派发队列，但无法撤销已进入外部 sink 的一次调用。sink 必须按 contract 快速返回；后端必须按 sessionId 再拒绝旧会话。旧 worker 未结束时同一个 collector 拒绝 restart，避免无限创建线程；建议后端每个会话新建实例，并确保 callback/provider 可结束。
- 合成夹具放在 test resources，不会随生产 JAR 自动打包；管理 API/UI 的模拟模式展示和入口由后端/前端集成，不在本任务中实现。

## 依赖和许可证记录（2026-10-29）

本轮不修改公共 POM、不新增运行依赖、不复制第三方脚本、proto 文件或生成代码。以下是技术调查，不是对分发权的保证。

| 组件 | 版本/来源 | 许可证与本轮处理 |
|---|---|---|
| Java HTTP/WebSocket、gzip、SHA-256 | 项目 Java 21；测试主机 Oracle JDK 21.0.11 | 使用 JDK API，不把 JDK 二进制打进仓库；交付 JRE 的发行版与许可由集成决定 |
| Jackson | 现有 Boot BOM 2.19.4 | Apache-2.0；仅使用现有依赖读取合成夹具 |
| DouyinLiveWebFetcher | 2026-10-29 在线查阅 main（非固定发布版）；旧项目来源记录抓取于 2026-10-27 | 仓库标注 AGPL-3.0，README 另有非商业研究声明；不直接复用或转译签名、连接代码和 proto |
| 旧项目 douyin_protocol.py / sign.js | 只读旧项目 HEAD 0989f57191b180787e8dac66b5686106e134cf22，vendor/douyin_sign/SOURCE.md | 本地记录前者 AGPL-3.0-or-later、后者上游 AGPL-3.0；不复制、不执行 |
| mini-racer | 旧项目和上游固定 0.12.4 | Python/V8 路径，本轮未完成其完整许可证审计，本 Java 任务不引入；引擎许可不替代脚本许可 |
| protobuf-java / protoc（候选） | Java 4.31.1 / protoc 31.1，仅作候选调查，不是要求升级到此旧版本 | [BSD-3-Clause](https://github.com/protocolbuffers/protobuf/blob/v31.1/LICENSE)；若采用生成式解析，集成统一选支持版本、审查 schema 来源后更新 POM |
| GraalJS（候选） | 暂不选版本、不申请加入 POM | [引擎源代码 UPL-1.0](https://github.com/oracle/graaljs/blob/master/LICENSE)；具体 Maven 发行物/传递依赖另审。仅在有明确授权签名源码时评估 |

来源：[上游仓库和声明](https://github.com/saermart/DouyinLiveWebFetcher)、[上游运行依赖](https://github.com/saermart/DouyinLiveWebFetcher/blob/main/requirements.txt)、[JDK 21 WebSocket API](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/WebSocket.html)、[Protobuf 官方 wire format](https://protobuf.dev/programming-guides/encoding/)。

纯 Java 的网络、gzip、有限字段 wire 解析、确认、心跳、重连可行；签名不是 JDK 自带功能，当前没有获准复用且经真实验证的实现。Java 调 JS 技术上可行，但引入 GraalJS/Node 不消除签名脚本许可边界，也需要执行超时、内存限制和禁用宿主/文件/网络访问。不能把 AGPL 脚本移到子进程就宣称可以闭源分发。本轮保留连接参数提供接口，默认不连接真实平台，不自动回退到模拟。

## 协议研究范围

上游当前实现显示：网页房间号需要解析为内部 roomId，Cookie/浏览器信息参与连接；WebSocket 使用签名连接参数；gzip 后得到 Response，needAck 对应 logId/internalExt 的确认；另有心跳和关播控制消息。这里仅将必要字段号/消息行为作为兼容性调查事实，不复制上游算法表达；上游并非官方稳定协议。线上有效性须重新验证。[调查来源](https://github.com/saermart/DouyinLiveWebFetcher/blob/main/liveMan.py)

旧本地 proto 的礼物 repeat/group/combo 为 varint，上游当前 main 声明为数字字符串；解析应只对白名单计数字段支持两种 wire 编码，不能据此宣称任何线上版本均兼容。礼物金额不查旧项目价格表，不换算人民币/主播收入，缺字段时输出 null。[字段调查](https://github.com/saermart/DouyinLiveWebFetcher/blob/main/douyin.proto)

## 公共接口提案

1. 后端显式创建 MOCK / DOUYIN 会话，展示真实 mode；不可仅凭类存在把 bootstrap.douyinCollector 改成 true。
2. CollectorPort 的 Consumer sink 无法返回 EventIngress.Result；当前一次性派发，不重试，结果统计需由后端包装 EventIngress.accept。若需采集器统计 REJECTED_FULL，提议后续由集成调整接口。
3. 房间解析/签名实现与安全凭据提供方式由集成确定；本轮不增加 API 字段，不从旧 config.json/Cookie 文件读取凭据。
4. 本实现不需要新增 Maven 依赖。若后续决定采用 protobuf-java/protoc 或 GraalJS，需先锁定可使用的 schema/脚本来源与具体受支持版本，再由集成更新公共 POM；本轮没有待安装的依赖。

## 集成步骤（仅交集成窗口执行）

1. 审阅上述三个实现提交和最后文档提交，在集成窗口按既定流程合并 feature/douyin-collector；本窗口不执行 merge 或 cherry-pick。
2. 先以 MOCK 会话集成：显式构造 MockCollector，提供可信 InputStream supplier；sink 包装 EventIngress.accept，保持非阻塞，并统计返回值。不要把测试资源当成生产 JAR 自带资源。
3. 在后端/前端已有会话契约中展示真实 mode、state 与固定诊断码。ENDED 的会话停止流程由后端接管；不要以 WebSocket CONNECTED 推断平台事件验收成功。
4. 确认房间解析/签名来源与许可后实现 DouyinConnectionProvider；传入内部 roomId、内存连接参数/请求头和心跳形式，按重连刷新。没有可用 provider 时不要启用 DOUYIN 功能按钮或提升 bootstrap 能力声明。
5. 合并后运行完整后端回归及三模块联调。获得可用房间后另做只读验收，分别记录真实评论、真实礼物及数量/连击、断线重连、关播；未收到真实礼物就继续标记未验证。不得发评论或购买礼物。
