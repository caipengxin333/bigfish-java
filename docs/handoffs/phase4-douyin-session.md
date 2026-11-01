# 第四轮：真实抖音采集接入主会话

日期：2026-11-01（Asia/Shanghai）。工作树 `bigfish-worktrees/backend`，分支 `feature/backend-core`；后端端口 18081，前端端口 15174。仅创建本地提交，没有修改 main、推送 GitHub、创建 PR、联系其他窗口或停止主项目进程。

## 基线与提交

- 开工检查 pwd、分支、git status；工作区干净。按要求从 main 快进至 `03e26b619715e68c9de00294d753eaa481dad31c`。
- 实现及测试提交：`57adee7a18dc823b33f710e5bfb9fed2d6e609b8`（`feat: connect Douyin collection to managed runtime sessions`）。
- 代码提交后再次执行 `git merge --no-edit main`：`Already up to date.`；交付基线仍为上述 main。本文单独提交，文档提交 SHA 由最终回复报告，避免自引用。
- PCM→WAV、STATIC/RAM Token 工厂、运行诊断及全部既有回归保留。未改采集器、签名算法、语音实现、contract、Vue、OpenAPI 或依赖版本。

## 修改范围与行为

生产文件：

- `backend/src/main/java/cn/bigfish/configuration/SessionCollectors.java`：新增会话工厂，复用 MockCollector / DouyinCollector。DOUYIN 使用现有 `WebDouyinConnectionProvider.guest(DouyinSigner)` 重载，其游客路径与无参 `guest()` 相同；原生 signer 仅加绑定观察层，签名请求/结果、来源和签名算法原样委托。
- `configuration/CoreConfiguration.java`：注册工厂，无启动网络副作用。
- `configuration/ProviderServices.java`：增加接收只读 Douyin 状态的重载，保留旧 status() 调用；不触碰 DeepSeek/NLS 调用或 Token 接线。
- `management/RuntimeEngine.java`：保留 startMock 重载；增加模式选择、受会话 ID 限制的一次性内部房间绑定、采集失败清理。严格事件房间校验保留。
- `management/SessionService.java`：统一 MOCK/DOUYIN 生命周期，捕获启动时网页号、管理有界资源退出、固定错误码及真实来源状态。
- `management/ManagementController.java`：bootstrap 标记功能可启动；providers 接入本地会话状态，无新路由/DTO。

测试文件：`configuration/SessionCollectorsTest.java`、`core/DouyinSessionTest.java`、`core/DouyinSessionApiTest.java`；更新 `core/SessionApiIntegrationTest.java`、`cn/bigfish/ManagementIntegrationTest.java` 的两处历史禁用断言。没有删除测试或削弱停止/隔离保护；空房间仍返回 422，新增显式来源选择、无自动网络副作用等断言。

具体行为：

1. 只有显式 POST 会话 `mode=DOUYIN` 才启动游客解析/采集。保存配置、查询配置/runtime/providers/bootstrap、构造工厂均不连接抖音。MOCK 仍使用空模拟源，直到显式 replay/simulate，不自动生成观众消息。已发布请求仍要求 mode/conversationMode 字段，未改为省略 mode 即接受；默认 UI 选择应保持 MOCK。
2. conversationMode 与 speechMode 独立。`DOUYIN + MOCK + LOCAL_TEST_TONE` 不触发 DeepSeek/NLS，也不要求云 Key。DOUYIN 失败绝不自动换成 MOCK。
3. 网页房间号取启动时保存配置的快照。解析器在签名前给出的内部 roomId 绑定引擎，早于 WSS 消息进入；不从第一条消息学习房间，不删除房间校验。相同内部号可重连，不同内部号/旧 sessionId 不能重绑。采集器原有“同网页号换一场直播则 ENDED”行为保留。
4. 运行中保存新网页号只影响下次启动；现有采集器重连继续使用原网页号。`runtime.roomId` 在解析绑定前为空，绑定后为内部号，MOCK 为 `mock-room`。
5. 采集回调仅进现有 EventIngress，不等待模型或合成。每个 run 有独立 open fence 与计数；停止、旧会话和旧工厂回调都不能进入新会话。
6. CONNECTING/CONNECTED/RECONNECTING 原样体现在 runtime.collector.state。250ms 周期协调采集终态：ENDED → 主会话 STOPPED；FAILED → 主会话 FAILED。终态触发模型/TTS 取消、队列清空、音频回收和播放撤销，保留 collector 的 ENDED/FAILED 与固定错误码；显式用户停止再转 STOPPED。
7. 停止先关闭引擎入口/播放，再停止采集器；最多等待 250ms 观察退出，未退出的唯一旧 Handle 继续保留。新启动必须确认 source/dispatcher/队列/transport 全部释放，且游客 HTTP 在途、signer worker 都为 0，否则返回 409 `COLLECTOR_STOPPING`。不丢失旧 Handle、不叠加新采集线程、无无限等待。
8. DOUYIN 会话禁止 replay/simulate，返回 409 `MOCK_ONLY`，避免合成输入伪装成真实接收证据。

## 实际验证

| 检查 | 结果 |
| --- | --- |
| 同步 main 后、改代码前 `./scripts/maven.sh -B -ntp test` | 198 项：196 通过，2 项收费实测默认跳过，0 失败/错误 |
| 首轮针对性测试 | 2 项新测试夹具失败：复用了被停止流程关闭的播放端，旧租约保护拒绝播放；补齐断线通知及新 HELLO/READY，未修改播放实现或移除断言 |
| 针对性测试复跑（命令见下） | 21 项全部通过 |
| 最终 `./scripts/maven.sh -B -ntp test` | **211 项：209 通过、2 项默认跳过，0 失败/错误**；19:12:00 完成 |
| 新增覆盖 | 工厂 2 项、会话 10 项、HTTP 接线 1 项，共 13 项新增通过 |
| 原有真实本机 WebSocket 测试 | StageWebSocketIntegrationTest 3/3 通过；只使用本地 MOCK/测试音，与云端接收验收分开 |
| `git diff --check` / 暂存 diff 检查 | 通过；修改范围符合本轮授权 |

针对性命令：

```sh
./scripts/maven.sh -B -ntp \
  -Dtest=SessionCollectorsTest,DouyinSessionTest,DouyinSessionApiTest,SessionApiIntegrationTest,ManagementIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

本轮测试在允许本机监听/JVM attach 的环境执行，没有把环境限制当代码故障，也没有删断言。全量包含配置持久化/CAS、SSE deadline、队列容量、阶段诊断、停止后不播、双播放端隔离、语音 WAV 与 Token 回归。新增异步链路测试用合成 COMMENT/GIFT，经真实 MockCollector dispatcher → DOUYIN 会话入口 → MOCK 回复/礼物模板 → 本地音频 → 播放回执；这是本地替身证据，不是自然抖音消息验收。

临时日志不提交：`/tmp/bigfish-phase4-baseline.log`、`/tmp/bigfish-phase4-focused.log`、`/tmp/bigfish-phase4-full.log`、`/tmp/bigfish-phase4-resolve.log`。

## 真实检查：未达到接收验收

2026-11-01 19:04:40，按授权对网页号 **549511642024** 运行独立、只读、guest resolve：

```sh
env BIGFISH_DOUYIN_LIVE_PROBE=true \
  BIGFISH_DOUYIN_ROOM=549511642024 \
  BIGFISH_DOUYIN_PROBE_ACTION=resolve \
  BIGFISH_DOUYIN_CREDENTIAL_MODE=guest \
  ./scripts/dev-backend.sh -B -ntp \
  -Dspring-boot.run.main-class=cn.bigfish.collector.DouyinCollectorProbe
```

实际脱敏输出：

```text
httpRequests=2 httpInFlight=0 elapsedMs=1683
mode=DOUYIN state=FAILED code=PAGE_STRUCTURE_CHANGED
```

- 实际 HTTP **2 次**，WSS 握手 **0 次**，receive **未执行**，真实评论 **0 条**、真实礼物 **0 条**；**真实礼物未验证**。关注/入场也未验证。
- 当前解析器未识别页面结构，**无法确认当时仍在直播，也不能据此判断已下播或要求登录**。没有满足先 resolve 确认 LIVE 的条件，因此未继续最多 60 秒 receive，没有把失败后的空计数伪称“房间无消息”。
- 停止/退出：HTTP 在途为 0；没有创建 WSS、采集 source/dispatch 或播放任务；独立 probe 进程已退出（probe exit 2 导致 Maven 非零）。没有遗留本轮服务进程。
- DeepSeek/NLS/CreateToken 真实请求 **0 次**。未发评论、点赞或礼物，未复制主项目密钥、Cookie 或数据库；dev 脚本按本工作树 runtime/worktree.env 读取隔离目录。
- 下一步采集维护提案：在采集任务边界内排查当前 guest 页面解析的 `PAGE_STRUCTURE_CHANGED`，采用脱敏结构夹具补回归；本轮不越界修改采集解析器或 signer。修复后重新确认 LIVE，再单次最多 60 秒 receive，逐类记录自然事件和资源退出。不得把握手成功或本地测试成功写成真实接收通过。

## 主窗口接线与公共契约提案

本分支没有改 Java contract、TypeScript 或 OpenAPI。**合入 UI 前主窗口必须同步现有字段的新取值/描述**，否则当前前端只接受 MOCK 的响应校验可能拒绝 DOUYIN runtime。

| 现有字段 | 建议 UI 映射 / 含义 |
| --- | --- |
| StartSessionRequest.mode | 新增来源选择“模拟 / 真实抖音”，默认 MOCK；必须显式选 DOUYIN。conversationMode/speechMode 保持独立下拉选择 |
| bootstrap.capabilities.douyinCollector=true | 仅“功能已接线，可发起启动”，不是网络已连接或真实接收已验收 |
| providers.douyin.configured/enabled=true | 游客工厂与原生 signer 已可用、显式会话启动路径已启用；不表示在线验证成功，也不自动发请求。无需手填 Cookie/UA/访客 ID |
| providers.douyin.state=UNVERIFIED | 未进行接收，或当前活动会话尚未被引擎接受有效事件；只有握手成功仍为 UNVERIFIED。终态显示 FAILED/ENDED/STOPPED；有接受事件后可显示当前 CONNECTED/RECONNECTING 等状态 |
| providers.douyin.reason | `LIVE_RECEIVE_NOT_VERIFIED` 表示无接收证据；`LIVE_EVENT_OBSERVED` 表示此会话至少一个有效事件被接受，不代表评论/礼物全类别通过，更不代表音频播放完成。有历史采集错误时优先保留固定码，结合 runtime 状态/计数与任务诊断判断恢复 |
| runtime.mode / collector.mode | 实际 MOCK / DOUYIN；新增 DOUYIN 到 TypeScript 与 OpenAPI RuntimeView.mode 的允许值 |
| runtime.collector.state | STOPPED/CONNECTING/CONNECTED/RECONNECTING/ENDED/FAILED；主会话 RUNNING 只表示业务引擎可处理任务，不能替代采集 CONNECTED |
| runtime.roomId | 当前绑定的内部 roomId；解析前为空。不能拿 config.settings.roomId（网页号）与它比较并认定串房 |
| config.settings.roomId | 已保存的网页号；运行中编辑提示“停止后重新启动生效”，不要拿保存后的值冒充运行房间 |
| collector.received/emitted/duplicates/rejected | 当前采集 Run 的接收/派发/去重/引擎拒绝计数。received 在归一化开始时计数，可能含坏包/未知类型/重复；emitted 是 sink 调用次数，可能随后被引擎拒绝。不能把任一计数单独视为播放完成或礼物接收证明 |
| collector.lastError / runtime.lastError | 前者是现有固定细分码（如 PAGE_STRUCTURE_CHANGED、HTTP_UNAVAILABLE），后者采集故障为 COLLECTOR_FAILED；前者可能保留历史错误，当前是否恢复看 state 与新增事件/任务诊断 |
| /runtime/diagnostics | 继续区分 PLAYER_NOT_READY/MODEL_WAIT/SPEECH_WAIT/PLAYBACK_WAIT、取消/丢弃和 STARTED/COMPLETED；不要用采集 CONNECTED 代替声音完成 |

需要主窗口同步的现有文档/客户端项：

1. 删除 OpenAPI StartSessionRequest 中“DOUYIN 恒返回 422”旧描述，补充 422 `DOUYIN_ROOM_REQUIRED` / `DOUYIN_UNSUPPORTED_ROOM_URL`，409 `COLLECTOR_STOPPING` / `MOCK_ONLY`，503 `COLLECTOR_START_FAILED`。SESSION_ACTIVE、SESSION_MISMATCH、云端付费门禁错误继续保留。
2. RuntimeView.Collector 注释“最近 replay 计数”改为“当前/最近一次采集 Run 计数”；ProviderState 文档区分无需环境凭据的 guest 能力与模型/语音凭据状态。README/AGENTS 当前主会话禁用 DOUYIN 的历史说明由集成窗口更新。
3. 当前配置 REST schema 的 roomId 仍只允许数字。服务内解析支持规范 `https://live.douyin.com/<网页号>`，但公开 PUT 校验会拒绝 URL；前端可沿用现有字段，在保存前严格提取该 host/path 的网页号，不接受短链/任意跳转。若希望后端公开支持保存 URL，请主窗口统一调整 Java 验证、TS 和 OpenAPI，本分支未擅改。
4. 如 UI 必须同时显示“本次启动网页号 / 当前内部号 / 已保存下次网页号”，建议新增只读 `RuntimeView.requestedRoomId: string`，取 SessionService.Run.requestedRoom 的冻结值（MOCK 为 mock-room，未启动为空）；现有 roomId 继续为内部绑定号。本分支仅保留内部值，未新增公共字段。
5. 如需结构化展示线程退出或逐类真实接收验收，请另行统一添加 collector.resourcesReleased、receivedByType/lastReceivedAt；目前资源释放由内部启动门禁保证，逐类线上证据由 probe 报告，不能从总 received 推断礼物已验收。

## 可复制请求：真实来源 + MOCK 回复 + 本地测试音

先确认主窗口已更新前端响应校验，在 **18081 的独立配置**中保存网页号（不复制主项目数据库）。若使用命令行，可保留其他配置并按 CAS 更新；以下依赖 jq，冲突时重新读取版本，不能覆盖他人修改：

```sh
curl -fsS http://127.0.0.1:18081/api/v1/config \
  | jq '{expectedVersion: .version, settings: (.settings + {roomId: "549511642024"})}' \
  | curl -fsS -X PUT http://127.0.0.1:18081/api/v1/config \
      -H 'Content-Type: application/json' --data-binary @-
```

后端用 `BIGFISH_ALLOW_PAID=false ./scripts/dev-backend.sh` 启动。下面的请求会实际连接抖音，请仅在确认目标房间/授权后显式执行；本轮未自动执行此真实主会话请求：

```sh
curl -fsS -X POST http://127.0.0.1:18081/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"mode":"DOUYIN","conversationMode":"MOCK","speechMode":"LOCAL_TEST_TONE"}'
```

获得 sessionId 后，打开同工作树舞台启用声音，或让 WebSocket 客户端按现有协议完成 HELLO → PLAYER_LEASE → READY、心跳以及任务 STARTED/COMPLETED。观察自然评论进入 received/emitted、内部 roomId 非空、任务诊断推进至 PLAYBACK/COMPLETED；礼物走模板通道。LOCAL_TEST_TONE 是测试音，**不是自然人声朗读**。不要调用 replay/simulate 冒充真实消息。

```sh
curl -fsS http://127.0.0.1:18081/api/v1/runtime
curl -fsS http://127.0.0.1:18081/api/v1/runtime/diagnostics
curl -fsS http://127.0.0.1:18081/api/v1/providers
curl -fsS -X POST 'http://127.0.0.1:18081/api/v1/sessions/<返回的sessionId>/stop'
```

端到端真实接收、真实礼物、直播平台变化后的游客连接可用性和长期在线稳定性仍未验收。本轮完成的是主会话接线与本地生命周期验证；没有收费长测或静默回退。
