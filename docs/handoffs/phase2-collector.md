# 第二轮抖音采集交接

日期：2026-10-30（Asia/Shanghai）。分支：`feature/douyin-collector`。
工作树：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/collector`。

本轮已交付房间解析、具体连接 Provider、只读验收入口和本地验证；**没有完成真实抖音接收验收，也没有获得可纳入项目的签名算法**。主会话接线仍由集成窗口处理。

## 基线与本地提交

开工检查目录/分支正确且工作区干净，未丢弃已有文件。按本轮明确授权执行 `git merge --no-edit main`，从第一轮 `db2668ca70dbe510f937f8314a3214827b3b04e6` 快进，无冲突。主工作树和旧项目文件均未修改，未合并其他功能分支，未推送或创建 PR。

| 类型 | SHA | 内容 |
| --- | --- | --- |
| 第二轮基线 | `a89c304ce16e8299e734938ab83f9780eba58fcf` | 包含主集成行为基线 `49a7865` 和第二轮任务说明 |
| 实现一 | `fb11522288019631f2b6f7868c4d6ea69087c260` | 房间输入/HTML 解析、凭据校验、有界 HTTP、取消、诊断、资源观察、握手失败分类与替身测试 |
| 实现二 | `39e3ad66e43b80b4d175b90764769cef4c32d24c` | 每次重连刷新 Provider、签名扩展与有界等待、只读 CLI、刷新/过期/停止测试 |

本文件及 `docs/providers/douyin.md` 作为后续文档提交；文档提交 SHA 见最终回复或本分支 `git log`，不制造自引用 SHA。

## 实际改动

所有修改限定于本轮许可的 collector 实现、collector 测试/夹具和两份文档；无公共 POM、contract、OpenAPI、管理接口、SessionService、Vue 或启动脚本改动。

- 新增 `DouyinRoomInput`：数字网页号或严格规范的 `live.douyin.com` HTTPS URL；拒绝任意主机、userinfo、query、fragment、编码路径、非 443 端口及短链。
- 新增 `DouyinCredentials / DouyinRoom / DouyinFailure`：内存凭据、房间和主播信息、固定诊断码。敏感对象 `toString` 脱敏，不保留上游异常文本或 cause。
- 新增 `DouyinRoomResolver / DouyinRoomPage`：只抓规范主机，禁止跟随重定向；页面上限 2 MiB，总请求时限 10 秒（含响应体），可中断取消。限定解析 roomStore 数据，不混入推荐房间，检查内部 ID/网页号/状态冲突。支持普通 JSON、RENDER_DATA 与 pace JSON 字符串，不执行 JavaScript。
- 新增 `WebDouyinConnectionProvider`：每次 resolve 取当前凭据、重新解析当前房间并调用签名扩展；nonce、签发时间/有效期、内部 ID、访客 ID、WSS 目的地和参数重复检查；不保存 URL，不转 MOCK。
- 新增 `DouyinSigner`：仅为单独审查的签名实现提供 Java 扩展点，声明来源、固定 revision 和许可证。**生产签名实现数量为零**。签名等待最多 10 秒，一个 worker，无排队；超时永久失败，不合作线程会显式暴露并阻止新增 worker。
- 更新 `DouyinCollector / AbstractCollector / CollectorDiagnostics / JdkCollectorSocket`：接受受支持网页 URL；固定错误与永久/暂态区分；握手拒绝不无限重试；停止与队列投递同步；新增连接计数和 `CollectorResources`。
- 新增 `DouyinCollectorProbe`：默认关闭、resolve/receive 显式选择、receive 最多 3 次解析/连接尝试，默认 60 秒/上限 120 秒；输出固定状态、评论/礼物事件及礼物增量汇总计数、ACK/心跳、异常/去重和资源数。不启动模型、TTS、Spring 管理页，不实现发送互动接口。
- 新增两个 `room-*.html` 人工夹具、3 个测试类；扩充 JDK WebSocket 测试。夹具不是真实抓包，不含真实 UID、昵称或 Cookie。

## 测试命令与结果

| 命令/检查 | 实际结果 |
| --- | --- |
| `pwd`、`git branch --show-current`、`git status --short` | 目录/分支正确，开工无未提交修改 |
| `git merge --no-edit main` | 快进至 `a89c304`，无冲突 |
| 基线 `./scripts/maven.sh -B -ntp test` | 67 项通过，0 失败/错误/跳过 |
| 初次 `./scripts/maven.sh -B -ntp -pl backend -DskipTests compile` | 发现计数枚举限定名错误，已修复；后续全量编译/测试通过 |
| `./scripts/maven.sh -B -ntp -pl backend '-Dtest=cn.bigfish.collector.*Test' test` | 当时采集器 56 项通过；后续新增签名超时和握手拒绝用例并纳入全量回归 |
| 最终 `./scripts/maven.sh -B -ntp test` | **89 项，0 失败、0 错误、0 跳过**；2026-10-30 02:19:15 完成，BUILD SUCCESS；含 collector 58 项及原其他后端 31 项 |
| Surefire XML 汇总 | 16 个 suite 合计 89，与最终运行一致 |
| `./scripts/dev-backend.sh -B -ntp -Dspring-boot.run.main-class=cn.bigfish.collector.DouyinCollectorProbe` | 默认禁用检查实际运行，输出 `mode=DOUYIN state=FAILED code=PROBE_DISABLED`，子进程退出 2（Maven 显示 BUILD FAILURE），符合预期，未请求抖音 |
| 启动依赖说明 | 第一次沙箱运行无法解析现有 Boot 插件依赖；按权限流程重跑，下载公共 POM 已固定的插件依赖后完成上述入口检查。未添加/升级 POM 依赖 |
| `git diff --check`、两次实现提交前 `git diff --cached --check` | 通过 |
| 本任务环境变量存在性检查 | LIVE_PROBE、ROOM、COOKIE、USER_AGENT、USER_UNIQUE_ID、CREDENTIALS_EXPIRES_AT、SIGNER_REVIEWED 七个指定变量均 absent；未打印值或枚举全部环境 |

测试使用本机回环 HTTP/WebSocket 替身，绑定临时端口；完整后端测试使用本工作树自己的测试数据。权限提升仅用于回环、Mockito 自附加、已固定构建插件下载及本分支 Git 元数据写入。没有连接或停止其他窗口服务，没有批量删除。

## 验收层次：逐项区分

| 项目 | 本地结果 | 真实服务结果 |
| --- | --- | --- |
| 房间解析 | 实现并通过限定结构夹具、ID 精度/主播状态/冲突/编码/URL/HTTP 状态测试 | **未验证真实页面**；没有当前直播 URL |
| 连接 Provider | 已有具体实现；凭据、页面、nonce 与签名每次刷新；签名/凭据过期拒绝 | 未建立真实 WSS |
| 签名提供器 | 扩展接口、来源声明、绑定与时限验证完成；测试 signer 明确为合成数据且未注册 SPI | **实际签名算法未交付**；缺合法可用来源 |
| 评论 | Mock/合成帧解析与事件 schema 回归通过 | **未接收、未验证** |
| 礼物 | 单个至少 1、累计转增量、重复包不重发、不同用户隔离回归通过 | **未接收、未验证**，未主动购买礼物 |
| 解压/ACK/心跳 | 原协议测试和真实 JDK 对本地 RFC6455 peer 通过 | 未确认与当前抖音线上帧一致 |
| 重连 | 有退避，保留去重/连击状态；实际 Provider 每次取新凭据/页面/签名；stop 后无重连 | 仅受控替身，未做线上断网恢复 |
| 关播/换内部 ID | 页面 ENDED、控制包 ENDED、同网页号新内部 ID 结束旧会话、停止竞态通过 | 未观察真实关播 |
| 取消与资源 | 待完成 HTTP、握手、重连等待、签名、派发可取消；测试断言 source/dispatch/业务队列/传输句柄归零，HTTP inFlight=0；不合作签名 worker 单独计数，释放测试锁后归零 | 真实运行后的资源统计未执行 |
| 主会话接线 | 无改动，最小工厂示例已提供 | **由集成窗口负责，尚未完成** |

用户此前明确“目前没有房间号”，本轮不反复请求、不寻找随机主播。真实请求、评论、礼物验证数均为零；默认关闭入口检查不算供应商成功冒烟。没有把 HTTP/WSS 握手视为业务接收成功。

## 来源、许可和依赖结论

完整链接/版本表见 [供应商说明](../providers/douyin.md)。本轮固定了三个研究对象：WebFetcher `40a904b…`（AGPL-3.0）、toolkit `4b4b7c1…`（AGPL-3.0）、DouyinLiveJava `b812242…`（MIT 客户端但明确不含签名算法）。均未复制或运行其签名/协议源码，未购买签名 SaaS，也未提交凭据到第三方。

官方互动工具、直播小玩法和移动端看播 SDK 的范围已分别核查。工具页面当前暂停新插件准入并限制服务端联网；玩法不开放主动拉取全量评论；不能将其泛称为任意直播间公开采集 API。现有纯 Java 请求、解析、传输和生命周期可行，签名仍缺获准实现。Java 调 JS 可行性不等于脚本可分发；当前没有获准源码及引擎版本提案。

**本轮新增依赖提案：无。** 使用现有 Java 21 与 Jackson 2.19.4（Apache-2.0，Boot 管理）；不请求公共 POM 变更。若用户选定授权 JavaScript 签名源码，再另列具体引擎版本、许可证、传递依赖和沙箱方案，交主窗口处理，不能先装 GraalJS/Node 再补手续。

## 剩余限制和交由用户/集成决策的选项

1. 真实验收准备：用户提供当前直播网页，在本机安全进程环境注入凭据及有效期；详见供应商说明的变量表。不要把 Cookie、URL 签名或密钥发到任务聊天。
2. 签名来源选择：获取许可明确的独立 Java 实现并实现 `DouyinSigner`；或先取得授权 JS 源码再提引擎方案；或确认官方产品权限/用途后另做官方适配器。没有自动采用 AGPL、转译旧脚本、缓存签名 URL 或创建空成功 Provider。
3. 页面解析覆盖率未知：当前 `roomStore.roomInfo`、`room.status=2/4` 与字段位置是待实测的兼容假设。未识别挑战/地区页面可能只报 `PAGE_STRUCTURE_CHANGED`；不能断言固定诊断码完全识别所有上游限制。缺房间信息的关播页面也不猜测 ENDED。
4. 签名 metadata 校验无法证明真实签名正确；合格扩展还须构造当前 SDK/cursor/internal_ext 等参数，遵守超时/中断、不记录输入和不向第三方发送会话凭据。故意忽略中断的扩展无法被 Java 安全强杀，残留数必须非零报告并视为失败。
5. 业务回调同样必须响应中断；采集源与业务派发隔离，但不能强制终止不合作的用户回调。资源快照只描述本采集器，不是 JVM 全部线程/套接字审计。
6. 长期稳定性、当前线上消息字段、自然礼物连击增量和去重、线上关播都未验证。真实数据只能在授权下制作最小脱敏夹具，不能提交完整帧或真实用户信息。

## 公共接线提案与最小调用

由集成窗口决定是否增加可用性显示/安全错误码、网页号与内部 roomId 的会话关联，并修改 SessionService/RuntimeEngine；本任务没有修改公共契约。

```java
WebDouyinConnectionProvider.CredentialSource credentials = () ->
    new DouyinCredentials(cookieInBackendMemory, matchingUserAgent,
        matchingBrowserVisitorId, allowedUntil);
DouyinSigner signer = reviewedSigner; // 必须是有实际算法、来源已审查的实现
WebDouyinConnectionProvider provider = new WebDouyinConnectionProvider(credentials, signer);
DouyinCollector collector = new DouyinCollector(provider);
collector.start(canonicalWebRoomUrl, sessionId, boundedEngineIngress);
// 停止/关会话：collector.stop() / collector.close()；操作幂等。
// 诊断：collector.state(), collector.diagnostics().lastError()
// 释放确认：collector.resources().released(), provider.inFlightRequests(), provider.signingWorkers()
```

- 保留每个新会话的 sessionId；事件 roomId 为解析出的内部 ID，不能用网页号错误过滤。不要把这个差异默默改入公共 DTO。
- 不以 `start` 返回、Bean 存在、签名扩展文件存在或握手成功宣称就绪。无有效签名/凭据时保持不可用；不得把 `bootstrap.douyinCollector` 提前改为 true。
- 监听 Collector 的 ENDED/FAILED，取消旧会话引擎、播放与队列；同网页号换内部 ID 必须重新开始会话，不能沿用旧 session。stop 后确认资源释放再复用实例。
- 合并本分支后先运行完整后端回归，再按供应商说明显式启用独立 `resolve`/`receive`。最多三次短接入尝试，结果分别记录真实评论与真实礼物；没有自然礼物则写未验证，不主动制造事件。
