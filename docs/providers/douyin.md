# 抖音采集适配器：第三轮原生签名与验收说明

核查日期：2026-11-01。已内置 `NativeDouyinSigner`，以有 MIT 许可的原生算法为来源移植到 Java 21；公开签名向量和本地 HTTP/WebSocket → 事件接收链路已验证。**尚未验证抖音当前线上接受该签名及连接参数，也没有真实评论/礼物结果。** 用户暂无当前直播间，后续一起实测。`SessionService` 接线和状态文案由集成窗口负责，现有 `bootstrap.douyinCollector=false` 不变。

## 接入方式与适用范围

| 路线 | 账户、权限和使用场景 | 对本项目的意义 |
| --- | --- | --- |
| 官方直播互动工具 | 主播在 App/直播伴侣使用获准的互动工具；须申请能力、提案审核。文档列出评论、礼物能力，同时注明当前暂停新插件准入，且工具能力不能经开发者服务器联网 | 不能直接当作任意直播间的 Java 服务端采集 API；是否适合 AI 回复场景须先向平台确认 |
| 官方直播小玩法 | 已通过资质/提案的玩法及对应开播场景；FAQ 限制可配置弹幕指令，明确不提供主动拉取全量或指定用户评论 | 不等同于读取任意普通直播间全部评论；官方方案需另做获准的适配器 |
| 官方直播 SDK | 第三方 Android/iOS App 内看播、账号授权等；有应用资质/接入要求 | 不是 Java 后端通用弹幕 SDK |
| 网页端非官方协议（本适配器） | 用户提供当前直播网页、合法取得的网页会话凭据、经审查的签名实现；受地区、登录态、页面和私有协议变化影响 | 只能在条件具备后逐层实测，不能称为无需鉴权的稳定公开 API |

依据：[官方互动工具接入指南](https://partner.open-douyin.com/docs/resource/zh-CN/live-interactive-tools/introduction/interactive-tools-guide)、[官方直播小玩法 FAQ](https://developer.open-douyin.com/docs/resource/zh-CN/interaction/introduction/zn/FAQ)、[官方直播 SDK 方案](https://open.douyin.com/platform/resource/docs/ability/douyin-live-sdk/solution/)。以上不同产品的授权范围不能互换，当前用户账户并未验证具备这些官方权限。

## 已实现的数据路径

`DouyinCollector → WebDouyinConnectionProvider → 当前凭据 → DouyinRoomResolver → NativeDouyinSigner → DouyinConnection → JDK WebSocket → Protobuf/gzip → 有界事件队列 → CollectorPort 回调`。

- Provider 每次 `resolve` 都重新取凭据、抓取房间页面、生成新的签名请求 nonce；不缓存签名 URL、Cookie 或房间状态。不存在自动 MOCK 回退。
- 解析器只请求规范化的 `https://live.douyin.com/<网页房间号>`，所有 HTTP 重定向一律拒绝，无全局 Cookie jar、磁盘缓存、浏览器脚本执行或任意 URL 抓取入口。
- HTTP 响应上限 2 MiB，包含整个响应体的总时限 10 秒；`stop` 中断等待后取消 HTTP future 并关闭其 HttpClient。
- 页面仅支持脚本内的 JSON、`RENDER_DATA` 百分号编码 JSON、`self.__pace_f.push` JSON 字符串中的 `roomStore.roomInfo`。读取房间 `id_str/roomId`、主播 `id_str/nickname`、`room.status`，校验重复结果和可用的 `anchor.web_rid`。不使用“页面任意 roomId 正则”获取推荐房间。
- 当前解析器约定 `room.status=2/4` 为直播/结束；不混用 `/web/enter` 的 `room_status`。这些页面形态与状态映射仅经人工夹具验证，**未用真实页面证实覆盖率**。关播页面缺内部 ID、状态未知、多个房间数据冲突均失败关闭，不能猜测。
- 签名结果须匹配本次 nonce、内部 roomId、浏览器访客 ID；签发时间不能早于请求或晚于当前时刻，声明有效期最多 60 秒，握手前须至少剩余 5 秒。此检查不能证明上游接受签名。
- WSS 仅接受现有允许的抖音 webcast 主机、443 与 `/webcast/im/push/v2/` 路径。握手被拒绝进入固定错误，避免凭据/签名失效无限重试。
- 传输故障使用第一轮的 1～30 秒指数退避与抖动；短暂握手不清空退避。用户 stop 后不再重连；关播控制或房间解析结束使 `state=ENDED`；同网页房间内部 roomId 改变时，先结束旧会话，不为新房间签名或连接。
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
| `BIGFISH_DOUYIN_SIGNER_REVIEWED` | `receive` 必须为 `true`，表示使用者已审查实际签名实现及来源；不能据此跳过许可证/权限核查 |

为避免把匿名连接当作完整能力，本实现要求 Cookie、User-Agent、访客 ID 三者齐备，`resolve` 也沿用这个准备条件。它不声称任何 Cookie 都能收到礼物，不声称平台对所有房型有相同要求。环境变量只在进程启动时继承；需要轮换时重启进程，集成可以用 `CredentialSource` 每次返回新的内存凭据。

## 原生签名来源、实现与在线边界

采用 [jwwsjlm/douyinLive 原生核心](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/internal/webcastsign/native.go)，固定 revision `af34f7c2ea2c4df90c6f4659e967fb31815d9255`；[许可证为 MIT](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/LICENSE)，版权 `(c) 2025 今晚打打`。仅移植原生签名核心和 `websocket_params.go` 的签名字段/参数组装，不采用上游 JS 回退、HTTP A-Bogus、浏览器画像伪装、Go 服务或 proto。完整 MIT 声明保存在 Java 核心源文件和会打包的 SPI 描述文件注释中。上游 JS 的来源不因根 MIT 标签就自动澄清；没有复制或执行旧 AI-Vtuber 的 AGPL `sign.js`。三个具体候选的审计在 [phase3 handoff](../handoffs/phase3-collector.md)。

`NativeWebcastSignature` 对规范输入进行 MD5、协议所需的单字节 RC4 和自定义 Base64 编码；这些是协议兼容步骤，不用于存储凭据的加密。每个 signer 有同步保护的 6 位序号及 `SecureRandom`，每次请求重新计算。两组上游公开测试向量匹配，覆盖零序号/回卷；不是用常量充当返回签名。

`NativeDouyinSigner` 从本次 roomId、浏览器访客 ID、UA 与当前时间构造 WSS 查询串，13 个字段按固定顺序参与签名；SDK 版本为 `1.0.15`。签名值中的 `+` 等字符按 URL 编码，避免被解码为空格。返回的 30 秒有效期是本地新鲜度预算，并非平台签发的过期时间。重连重新取页面、凭据、nonce 和时间并签名，不重复使用缓存 URL。整个实现仅用已有 Java 21 标准库，无新增 POM 依赖、Node/浏览器/Go 辅助服务。

**当前连接引导的实测缺口**：上游会尝试 IM fetch 获取服务端 cursor/push server，也保留了历史 cursor/wrds_v 回退。本实现未实现 HTTP A-Bogus/IM fetch，不复制历史 cursor/wrds_v；初次 WSS 使用空 cursor、由当前 roomId/访客/时间生成的 internal_ext，以及已允许的 `webcast100-ws-web-lf.douyin.com` 主机。没有凭空填写屏幕尺寸或浏览器平台。平台是否接受这种初始请求、该边缘节点及参数组合，必须用当前合法会话实测。若拒绝，不自动轮换主机、绕过挑战或降级成旧签名；先按固定错误停止并重新评估 bootstrap。**原生算法兼容测试通过不等于线上已可用。**

`DouyinSigners.load()` 通过已注册的 `META-INF/services/cn.bigfish.collector.DouyinSigner` 加载唯一实现，默认就是 `NativeDouyinSigner`，无需外置 signer JAR。ServiceLoader 无实现、多个候选、坏类名或构造失败均转为固定错误；多候选时不执行构造函数。如需替换，集成必须显式注入选定实现或调整发行物中的唯一注册，不能简单叠加第二个 SPI JAR。

`Provenance` 只强制声明来源与固定 revision，并非自动许可证审查。自定义扩展仍须响应中断、禁止记录输入及联网转交凭据。Provider 最多一个签名 worker、等待不超过 10 秒；不合作扩展的残留线程会通过 `signingWorkers>0` 暴露并使验收失败。

## 独立只读验收

在 collector 工作树执行，脚本加载本工作树隔离配置：

```bash
./scripts/dev-backend.sh -B -ntp -Dspring-boot.run.main-class=cn.bigfish.collector.DouyinCollectorProbe
```

该入口不启动 Spring/管理页，不连接模型/TTS。默认无显式开关时输出 `mode=DOUYIN state=FAILED code=PROBE_DISABLED` 并以 2 退出；Maven 会把进程退出 2 显示成 BUILD FAILURE，这是预期保护。缺房间时 `ROOM_REQUIRED`，不去寻找随机直播间。

完成本机准备后先选择 `resolve`，只抓取一次页面，输出状态与 `roomResolved`，不打印房间/主播信息。`receive` 使用内置且唯一的原生 ServiceLoader 实现，仍需显式 `BIGFISH_DOUYIN_SIGNER_REVIEWED=true`。未启用开关、缺房间/凭据、SPI 配置异常均在联网前终止。没有额外 JAR、脚本或浏览器安装步骤。

每次 `receive` 最多 3 次实际 Provider 解析/连接尝试，默认 60 秒后 stop，最多等待 2 秒观察资源释放。只发送协议所需的 ACK/Ping/Pong/心跳，无发评论、点赞、关注或送礼接口。输出均为固定状态/计数：实际 HTTP 请求数、签名次数、WSS 握手尝试数、连接数、总耗时、评论/礼物/关注/进场/点赞数、礼物增量总数、ACK/心跳/重复/异常/重连数和最后错误码。HTTP 计数是发起请求数，不是服务端确认处理数；resolve 与 receive 的每次运行单独汇总。业务内容、用户标识、Cookie、签名 URL 和原始帧均不输出。

终止报告保留 stop 前 `terminal` 和 stop 后 `STOPPED`。资源报告包括本采集器 source/dispatch 线程、业务队列、附着传输句柄、HTTP 在途请求和签名 worker；不把这些数当作 JVM 全部线程数。JDK 网络传输 close 会取消握手、abort socket、清空入站队列并 shutdownNow 客户端。已有业务回调必须响应中断，无法强制终止不合作的调用者代码。

只有 `comments>0`、无最终 FAILED 且资源已释放，`receive` 才退出 0；仅握手或仅收到未知帧不算评论验收。`gifts=0` 必须报告真实礼物未验证。即使收到礼物，汇总计数也不足以单独证明线上连击字段语义，需要在授权条件下观察连续礼物、跨用户和去重行为；不主动送礼造数据。禁止写完整真实帧，后续仅可从获准数据制作最小、去标识夹具。

## 固定诊断码

| 代码 | 处理 |
| --- | --- |
| `UNSUPPORTED_ROOM_URL` | 使用受支持规范链接；无请求 |
| `CREDENTIALS_MISSING / INVALID / EXPIRED`（实际代码带 `CREDENTIALS_` 前缀） | 补齐或更新本机内存凭据；FAILED，不切 MOCK |
| `AUTH_REQUIRED` | HTTP 401/403 或已知登录页；用户在自己的浏览器检查权限/登录，不自动绕过 |
| `CHALLENGE_REQUIRED` | 识别到明确验证页（包括 HTTP 403 的已知验证标题）；停止，不自动解验证 |
| `REGION_RESTRICTED` | 451 或明确地区限制页；停止，不改变地区绕过 |
| `PAGE_STRUCTURE_CHANGED` | 字段/状态/身份冲突、未知页面或非 HTML；请求授权的最小脱敏样本后适配 |
| `REDIRECT_REJECTED` | 不跟随任何 3xx；不向新目的地发送 Cookie |
| `RESPONSE_TOO_LARGE` | 超过 2 MiB；停止，避免无界内存 |
| `HTTP_TIMEOUT / HTTP_UNAVAILABLE` | 暂态故障，Collector 退避重试；独立入口仍受 3 次限制 |
| `RATE_LIMITED` | HTTP 429；停止，避免继续冲击平台 |
| `SIGNER_UNAVAILABLE / SIGNER_CONFIGURATION_INVALID / SIGNER_FAILED / SIGNER_TIMEOUT` | 缺少实现或显式审查开关、SPI 配置错误、执行失败或超过 10 秒；FAILED |
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
| 原生 Webcast 签名核心及有序字段映射 | `jwwsjlm/douyinLive@af34f7c2ea2c4df90c6f4659e967fb31815d9255` | MIT，仅移植上述有限范围，带源码及打包版权声明；不运行上游 JS |
| 外部 JS 引擎/浏览器/签名服务 | 无 | 未引入，不需要本地常驻辅助进程 |

Java 调 JS 在技术上可行，但本轮已有可移植原生核心，不引入该路线。进程包装、改名或独立服务不自动消除 AGPL 或限制分发资源的许可问题。

## 集成最小调用

```java
WebDouyinConnectionProvider.CredentialSource credentials = () ->
    new DouyinCredentials(cookieFromBackendMemory, matchingUserAgent,
        matchingBrowserVisitorId, allowedUntil);
DouyinSigner signer = new NativeDouyinSigner(); // 或 DouyinSigners.load()，内置唯一 SPI 实现
var provider = new WebDouyinConnectionProvider(credentials, signer);
var collector = new DouyinCollector(provider); // 实现现有 CollectorPort
collector.start(canonicalWebRoomUrl, sessionId, boundedEngineIngress);
// 会话取消/关闭：collector.stop() 或 collector.close()；二者幂等。
```

`start` 异步失败通过 `state()==FAILED` 和 `diagnostics().lastError()` 读取，不能把 start 返回或 Bean 存在当作就绪。`DouyinRoomResolver.resolve(String, DouyinCredentials)` 可单独解析，抛固定 `DouyinFailure` 或中断。后续主会话应监听 ENDED/FAILED、停止旧 session 的队列/播放，保留网页号与内部 roomId 的区别，等待 `resources().released()` 及 Provider 资源清零后再启动新会话。不得将这些内存凭据/内部结果直接做成前端 DTO。

本轮本地验证包含：实际 Java HTTP 获取合成页面 → 原生签名 → 实际 JDK 回环 WebSocket → gzip/Protobuf → 本地事件接收器。产生 1 条 COMMENT、3 条 GIFT（增量 1/2/1）、1 条 ENTRANCE、1 条 FOLLOW；重复终包不重发，异常包后继续收取，ACK/心跳正常，关播 ENDED 后释放。此测试通过包内 seam 把传输目标替换为本机回环，不是抖音服务器对签名的验证，不能充当真实接收记录。
