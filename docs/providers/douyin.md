# 抖音采集适配器：第二轮使用说明

核查日期：2026-10-30。当前交付的是房间解析、连接 Provider、只读验收入口与本地替身验证。**尚无可交付的签名算法，也没有真实房间接收结果。** `SessionService` 接线由集成窗口负责，现有 `bootstrap.douyinCollector=false` 不变。

## 接入方式与适用范围

| 路线 | 账户、权限和使用场景 | 对本项目的意义 |
| --- | --- | --- |
| 官方直播互动工具 | 主播在 App/直播伴侣使用获准的互动工具；须申请能力、提案审核。文档列出评论、礼物能力，同时注明当前暂停新插件准入，且工具能力不能经开发者服务器联网 | 不能直接当作任意直播间的 Java 服务端采集 API；是否适合 AI 回复场景须先向平台确认 |
| 官方直播小玩法 | 已通过资质/提案的玩法及对应开播场景；FAQ 限制可配置弹幕指令，明确不提供主动拉取全量或指定用户评论 | 不等同于读取任意普通直播间全部评论；官方方案需另做获准的适配器 |
| 官方直播 SDK | 第三方 Android/iOS App 内看播、账号授权等；有应用资质/接入要求 | 不是 Java 后端通用弹幕 SDK |
| 网页端非官方协议（本适配器） | 用户提供当前直播网页、合法取得的网页会话凭据、经审查的签名实现；受地区、登录态、页面和私有协议变化影响 | 只能在条件具备后逐层实测，不能称为无需鉴权的稳定公开 API |

依据：[官方互动工具接入指南](https://partner.open-douyin.com/docs/resource/zh-CN/live-interactive-tools/introduction/interactive-tools-guide)、[官方直播小玩法 FAQ](https://developer.open-douyin.com/docs/resource/zh-CN/interaction/introduction/zn/FAQ)、[官方直播 SDK 方案](https://open.douyin.com/platform/resource/docs/ability/douyin-live-sdk/solution/)。以上不同产品的授权范围不能互换，当前用户账户并未验证具备这些官方权限。

## 已实现的数据路径

`DouyinCollector → WebDouyinConnectionProvider → 当前凭据 → DouyinRoomResolver → DouyinSigner 扩展 → DouyinConnection → JDK WebSocket → Protobuf/gzip → 有界事件队列 → CollectorPort 回调`。

- Provider 每次 `resolve` 都重新取凭据、抓取房间页面、生成新的签名请求 nonce；不缓存签名 URL、Cookie 或房间状态。不存在自动 MOCK 回退。
- 解析器只请求规范化的 `https://live.douyin.com/<网页房间号>`，所有 HTTP 重定向一律拒绝，无全局 Cookie jar、磁盘缓存、浏览器脚本执行或任意 URL 抓取入口。
- HTTP 响应上限 2 MiB，包含整个响应体的总时限 10 秒；`stop` 中断等待后取消 HTTP future 并关闭其 HttpClient。
- 页面仅支持脚本内的 JSON、`RENDER_DATA` 百分号编码 JSON、`self.__pace_f.push` JSON 字符串中的 `roomStore.roomInfo`。读取房间 `id_str/roomId`、主播 `id_str/nickname`、`room.status`，校验重复结果和可用的 `anchor.web_rid`。不使用“页面任意 roomId 正则”获取推荐房间。
- 当前解析器约定 `room.status=2/4` 为直播/结束；不混用 `/web/enter` 的 `room_status`。这些页面形态与状态映射仅经人工夹具验证，**未用真实页面证实覆盖率**。关播页面缺内部 ID、状态未知、多个房间数据冲突均失败关闭，不能猜测。
- 签名结果须匹配本次 nonce、内部 roomId、浏览器访客 ID；签发时间不能早于请求或晚于当前时刻，声明有效期最多 60 秒，握手前须至少剩余 5 秒。此检查不能证明上游接受签名。
- WSS 仅接受现有允许的抖音 webcast 主机、443 与 `/webcast/im/push/v2/` 路径。握手被拒绝进入固定错误，避免凭据/签名失效无限重试。
- 传输故障使用第一轮的 1～30 秒指数退避与抖动；短暂握手不清空退避。用户 stop 后不再重连；关播控制或房间解析结束使 `state=ENDED`；同网页房间内部 roomId 改变结束旧会话。
- 礼物至少 1、同组累计转增量、重复包去重、不同用户隔离、异常包隔离保留原实现；采集/ACK/心跳不等待业务回调。

## 支持的输入

- 1～30 位数字网页房间号。
- `https://live.douyin.com/<数字>`，可有末尾 `/` 或显式 `:443`。
- 不接受 query、fragment、userinfo、编码路径、其他端口、HTTP、短链接 `v.douyin.com`、用户主页、分享文案或其他主机。请用户在自己的浏览器展开短链，提供上述规范地址；后端不代为追踪分享跳转。
- 网页号不等于内部 roomId；不能把历史内部 roomId 当作当前网页号。

## 准备项和内存凭据

由用户在本机进程环境中配置；不要把值发到聊天、命令参数、shell 历史、`runtime/worktree.env`、源码、SQLite 或前端。可通过本机已有的安全启动环境/凭据管理方式注入，本文不提供包含凭据值的命令。只检查下列变量的存在性，不列出全部环境。

| 环境变量 | 作用 |
| --- | --- |
| `BIGFISH_DOUYIN_LIVE_PROBE` | 必须显式为 `true` 才允许运行独立验收 |
| `BIGFISH_DOUYIN_ROOM` | 用户当前正在直播的规范链接/网页房间号 |
| `BIGFISH_DOUYIN_PROBE_ACTION` | 显式选择 `resolve`（只读取页面）或 `receive`（页面及 WSS） |
| `BIGFISH_DOUYIN_COOKIE` | 用户合法网页会话的 Cookie，仅传给固定抖音主机；不自动登录、绕过挑战或从旧项目读取 |
| `BIGFISH_DOUYIN_USER_AGENT` | 与该网页会话一致的 User-Agent |
| `BIGFISH_DOUYIN_USER_UNIQUE_ID` | 与该网页会话匹配的浏览器访客 `user_unique_id`；不是主播 ID，不从旧示例复制固定值 |
| `BIGFISH_DOUYIN_CREDENTIALS_EXPIRES_AT` | 本次凭据允许使用到的 ISO-8601 UTC 时间；这是本机使用期限，不保证服务端 Cookie 尚有效 |
| `BIGFISH_DOUYIN_PROBE_SECONDS` | 可选，默认 60 秒，范围 1～120 秒 |
| `BIGFISH_DOUYIN_SIGNER_REVIEWED` | `receive` 必须为 `true`，表示使用者已审查外部扩展及来源；不能据此跳过许可证/权限核查 |

为避免把匿名连接当作完整能力，本实现要求 Cookie、User-Agent、访客 ID 三者齐备，`resolve` 也沿用这个准备条件。它不声称任何 Cookie 都能收到礼物，不声称平台对所有房型有相同要求。环境变量只在进程启动时继承；需要轮换时重启进程，集成可以用 `CredentialSource` 每次返回新的内存凭据。

## 签名缺口与可执行选择

没有内置签名实现。当前 `WebDouyinConnectionProvider` 是完整的解析、刷新、验证与连接材料组装实现；剩余扩展负责生成**当前有效的 WSS 参数和签名**，包括需要的 SDK 版本、cursor/internal_ext、设备信息等。仓库没有复制上游旧的硬编码时间戳和访客 ID，也不会用缓存 URL 假装完成签名。

选择一：取得允许在本项目使用的独立 Java 签名实现及授权，固定源码 commit（40 位 SHA）或制品 SHA-256（64 位），实现 `DouyinSigner`。提供 `Provenance(source, revision, license)`、`sign(Request)`，用 `Request` 中的当前 roomId、userUniqueId、User-Agent 和时间重新构造目标。返回 `SignedTarget(endpoint, nonce, issuedAt, expiresAt, heartbeat)`。签名代码不得记录输入、不得把 Cookie 发往第三方，须响应中断；Provider 的等待上限 10 秒且最多一个签名 worker。该实现可直接注入，或放入经审查的本地 JAR，注册 `META-INF/services/cn.bigfish.collector.DouyinSigner`。

选择二：如果只能取得获准使用的 JavaScript 签名源码，先提交该源码固定 revision、许可证/授权链及拟用引擎的版本、许可证、传递依赖提案，等集成窗口决定。Java 调 JS 技术上可行，但引擎不能解决脚本许可问题；需要限制文件/网络/宿主访问、执行时间和内存。当前未选择/加入 GraalJS、Node 或任何 JS 执行依赖，不运行旧 AGPL 脚本。

选择三：改走官方获准的产品能力。先确认账户、直播类型和 AI 服务端用途是否允许，再由集成安排对应适配器；当前官方工具页面的暂停准入和联网限制意味着不能把此路径描述为立即可用。未购买签名服务、未向第三方提交 Cookie，也没有自动联系任何供应商。

`Provenance` 只强制声明来源与固定 revision，**并非自动许可证审查**。一个标注 MIT 的客户端也不能替其缺失的签名算法背书。不响应中断的扩展可能仍有残留虚拟线程；Provider 拒绝继续生成新签名 worker，验收输出 `signingWorkers>0` 并视为失败，不能强杀 Java 线程或报告已释放。

## 独立只读验收

在 collector 工作树执行，脚本加载本工作树隔离配置：

```bash
./scripts/dev-backend.sh -B -ntp -Dspring-boot.run.main-class=cn.bigfish.collector.DouyinCollectorProbe
```

该入口不启动 Spring/管理页，不连接模型/TTS。默认无显式开关时输出 `mode=DOUYIN state=FAILED code=PROBE_DISABLED` 并以 2 退出；Maven 会把进程退出 2 显示成 BUILD FAILURE，这是预期保护。缺房间时 `ROOM_REQUIRED`，不去寻找随机直播间。

完成本机准备后先选择 `resolve`，只抓取一次页面，输出状态与 `roomResolved`，不打印房间/主播信息。`receive` 需要唯一的、已审查的 ServiceLoader 签名扩展。例如为上面命令追加（路径是用户自行准备的制品占位符，仓库不提供该 JAR）：

```text
-Dspring-boot.run.additional-classpath-elements=/absolute/path/to/reviewed-signer.jar
```

该属性和 `main-class` 已核对本机 Spring Boot Maven Plugin 3.5.11 描述。不会扫描或执行旧项目脚本；没有扩展则 `SIGNER_UNAVAILABLE`，且不发房间请求。

每次 `receive` 最多 3 次实际 Provider 解析/连接尝试，默认 60 秒后 stop，最多等待 2 秒观察资源释放。只发送协议所需的 ACK/Ping/Pong/心跳，无发评论、点赞、关注或送礼接口。输出均为固定状态/计数：连接次数、评论事件数、礼物事件数、礼物增量总数、ACK/心跳/重复/异常/重连数和最后错误码。业务内容、用户标识、Cookie、签名 URL 和原始帧均不输出。

终止报告保留 stop 前 `terminal` 和 stop 后 `STOPPED`。资源报告包括本采集器 source/dispatch 线程、业务队列、附着传输句柄、HTTP 在途请求和签名 worker；不把这些数当作 JVM 全部线程数。JDK 网络传输 close 会取消握手、abort socket、清空入站队列并 shutdownNow 客户端。已有业务回调必须响应中断，无法强制终止不合作的调用者代码。

只有 `comments>0`、无最终 FAILED 且资源已释放，`receive` 才退出 0；仅握手或仅收到未知帧不算评论验收。`gifts=0` 必须报告真实礼物未验证。即使收到礼物，汇总计数也不足以单独证明线上连击字段语义，需要在授权条件下观察连续礼物、跨用户和去重行为；不主动送礼造数据。禁止写完整真实帧，后续仅可从获准数据制作最小、去标识夹具。

## 固定诊断码

| 代码 | 处理 |
| --- | --- |
| `UNSUPPORTED_ROOM_URL` | 使用受支持规范链接；无请求 |
| `CREDENTIALS_MISSING / INVALID / EXPIRED`（实际代码带 `CREDENTIALS_` 前缀） | 补齐或更新本机内存凭据；FAILED，不切 MOCK |
| `AUTH_REQUIRED` | HTTP 401/403 或已知登录页；用户在自己的浏览器检查权限/登录，不自动绕过 |
| `CHALLENGE_REQUIRED` | 识别到明确验证页；停止，不自动解验证 |
| `REGION_RESTRICTED` | 451 或明确地区限制页；停止，不改变地区绕过 |
| `PAGE_STRUCTURE_CHANGED` | 字段/状态/身份冲突、未知页面或非 HTML；请求授权的最小脱敏样本后适配 |
| `REDIRECT_REJECTED` | 不跟随任何 3xx；不向新目的地发送 Cookie |
| `RESPONSE_TOO_LARGE` | 超过 2 MiB；停止，避免无界内存 |
| `HTTP_TIMEOUT / HTTP_UNAVAILABLE` | 暂态故障，Collector 退避重试；独立入口仍受 3 次限制 |
| `RATE_LIMITED` | HTTP 429；停止，避免继续冲击平台 |
| `SIGNER_UNAVAILABLE / SIGNER_FAILED / SIGNER_TIMEOUT` | 缺少受审扩展、执行失败或超过 10 秒；FAILED |
| `SIGNATURE_INVALID / SIGNATURE_EXPIRED` | nonce/room/访客/目标不匹配、空签名、重复参数或时间不合格；FAILED |
| `HANDSHAKE_REJECTED` | WSS 未升级成功；固定码，不输出响应。可能是鉴权、签名或平台变化，不能单凭此码断言哪项失效 |
| `PROBE_DISABLED / ROOM_REQUIRED / PROBE_CONFIGURATION_INVALID / ATTEMPT_LIMIT` | 修正独立验收准备项；不反复自动运行 |

未知验证码/地区页面可能归为 `PAGE_STRUCTURE_CHANGED`；诊断不能冒充服务端明确结论。异常消息和 cause 不保存在本轮 `DouyinFailure` 中。

## 组件、来源和许可证

没有新增 Maven 依赖，没有修改公共 POM。

| 组件/研究对象 | 固定版本或 revision | 许可与采用情况 |
| --- | --- | --- |
| Java HTTP/WebSocket/gzip/并发 API | Java 21；本机测试 JDK 21.0.11 | 仅使用 JDK API，不分发 JDK；具体运行时发行许可由集成决定 |
| Jackson core/databind/annotations | 现有 Spring Boot 3.5.11 管理的 2.19.4 | Apache-2.0，沿用现有依赖解析 JSON；未升级 |
| 本项目 room/provider/wire 解析实现 | 本任务提交 | 独立编写；未复制 upstream 脚本或 proto，未使用第三方生成 schema |
| saermart/DouyinLiveWebFetcher | `40a904bdb0561a5a9c958a95634a29681cc4fbf4` | AGPL-3.0，README 另有研究/非商业声明；只读协议调查，不复制/执行/转译代码 |
| chuanyue98/douyin-live-toolkit | `4b4b7c1e09adf7e8a62b232f2768484836f070f7` | LICENSE 为 AGPL-3.0；未采用 |
| lulajax/DouyinLiveJava | `b812242787ae9b5f4bbbf4e2239e4eec43318119` | MIT 客户端；README 明确不含签名算法。未加入依赖，未订阅其外部签名服务 |
| JS 引擎/签名算法 | 未选定 | 无版本锁定与授权链，不加入构建；后续须具体提案 |

固定来源：[WebFetcher 源码](https://github.com/saermart/DouyinLiveWebFetcher/blob/40a904bdb0561a5a9c958a95634a29681cc4fbf4/liveMan.py)、[WebFetcher 许可](https://github.com/saermart/DouyinLiveWebFetcher/blob/40a904bdb0561a5a9c958a95634a29681cc4fbf4/LICENSE)、[toolkit 许可](https://github.com/chuanyue98/douyin-live-toolkit/blob/4b4b7c1e09adf7e8a62b232f2768484836f070f7/LICENSE)、[Java 客户端说明](https://github.com/lulajax/DouyinLiveJava/blob/b812242787ae9b5f4bbbf4e2239e4eec43318119/README.md)、[Java 客户端许可](https://github.com/lulajax/DouyinLiveJava/blob/b812242787ae9b5f4bbbf4e2239e4eec43318119/LICENSE)。

纯 Java 的页面请求/解析、JDK WebSocket、解压、有限字段解析、归一化、ACK/心跳和生命周期可实现且已做本地验证；**纯 Java 签名并没有因此完成**。Java 调 JS 同样缺获准源码和具体引擎方案。将 AGPL 代码移至子进程、服务或改名并不自动消除其许可问题。

## 集成最小调用

```java
WebDouyinConnectionProvider.CredentialSource credentials = () ->
    new DouyinCredentials(cookieFromBackendMemory, matchingUserAgent,
        matchingBrowserVisitorId, allowedUntil);
DouyinSigner signer = reviewedSigner; // 经单独审查的实际实现；仓库没有默认实现
var provider = new WebDouyinConnectionProvider(credentials, signer);
var collector = new DouyinCollector(provider); // 实现现有 CollectorPort
collector.start(canonicalWebRoomUrl, sessionId, boundedEngineIngress);
// 会话取消/关闭：collector.stop() 或 collector.close()；二者幂等。
```

`start` 异步失败通过 `state()==FAILED` 和 `diagnostics().lastError()` 读取，不能把 start 返回或 Bean 存在当作就绪。`DouyinRoomResolver.resolve(String, DouyinCredentials)` 可单独解析，抛固定 `DouyinFailure` 或中断。后续主会话应监听 ENDED/FAILED、停止旧 session 的队列/播放，保留网页号与内部 roomId 的区别，等待 `resources().released()` 及 Provider 资源清零后再启动新会话。不得将这些内存凭据/内部结果直接做成前端 DTO。
