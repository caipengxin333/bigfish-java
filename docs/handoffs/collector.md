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
