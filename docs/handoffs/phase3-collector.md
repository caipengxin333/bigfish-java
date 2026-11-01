# 第三轮抖音采集交接

日期：2026-11-01；工作树 `bigfish-worktrees/collector`；分支 `feature/douyin-collector`。
开工工作区干净，已按授权从 `105304d` 快进合入 main `e39ca19893cde16a27937eac30c415db11a726cb`。基线回归 140 项：139 通过、1 项真实 NLS 测试按默认条件跳过、0 失败/错误。

交付前再次在干净工作区执行 `git merge --no-edit main`，合入 `55a6fa04758b86be5550af5fd1c3d07cbc560b3c`（主窗口 README 更新）；本分支合并提交 `e54610e97aa5ccc18b28f5390215f12898a97a28`。`git merge-base --is-ancestor main HEAD` 返回 0。同步后完整回归 149 项：148 通过、1 跳过、0 失败/错误。未修改 main、未推送；与最新 main 的差异仅在本任务授权目录和唯一 SPI 文件。

用户本轮确认“目前还没有直播号，先写好代码，后面一起测试”。本轮不会访问随机直播间；真实接收证据待后续提供，不把本地测试写成真实成功。

## 签名来源收敛与依赖提案

| 候选 | 固定 revision / 许可 | 当前可核实行为、依赖及对接方式 | 本轮选择 |
| --- | --- | --- | --- |
| [jwwsjlm/douyinLive](https://github.com/jwwsjlm/douyinLive/tree/af34f7c2ea2c4df90c6f4659e967fb31815d9255) 的 `internal/webcastsign/native.go` | `af34f7c2ea2c4df90c6f4659e967fb31815d9255`，MIT；原生核心首次提交 `067121dcb04a7bac1de9e1f1d09e2f1a12545f11`（2026-08-17） | 当前 HEAD 日期 2026-10-23；核心有两组明确字节级测试向量和计数/并发测试。上游默认优先原生签名，握手拒绝后另有 JS 回退；README 明确不保证礼物完整性。仅移植原生核心和签名输入字段到 Java 21，保留 MIT 声明；不使用其 JS 回退、浏览器画像伪装、HTTP A-Bogus、proto 或 Go 服务。 | **采用有限范围的原生核心**；当前在线有效性仍待真实房间验收 |
| [meijicai/DouyinLiveWebFetcher-java](https://github.com/meijicai/DouyinLiveWebFetcher-java/tree/9c14acc506a2fd777d067579f90670e9a9027e97) | `9c14acc506a2fd777d067579f90670e9a9027e97`（2026-10-22）；无根 LICENSE，THIRD_PARTY_NOTICES 引用上述 Go MIT 来源，DISCLAIMER 说明并非额外许可 | Java 17/Maven，声称原生 WS/A-Bogus 签名，有两组相同向量测试；没有本任务的在线接收证据。不能仅凭第三方 notice 推定其全部 Java 源码获准复用。 | 不复制此 Java 移植；直接以有明确 MIT 声明的 Go 原生核心为来源 |
| [scx567888/live-room-watcher](https://github.com/scx567888/live-room-watcher/tree/aa62f538c7b186a17ff5fc6c33ed1d289823355e) | `aa62f538c7b186a17ff5fc6c33ed1d289823355e`（2026-08-11）；LICENSE MIT，README 另写不得商用，需澄清 | 0.5.3；Playwright 1.55.0 启动 Chromium，打开网页并等待网页创建 WebSocket；可把所获短期地址适配当前 Provider，但引入浏览器运行时。完整项目还含 GraalJS 25.0.0、protobuf 4.32.1 等依赖。 | 不采用，不安装或启动浏览器辅助进程 |

实际使用范围的许可：[Go LICENSE](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/LICENSE)、[原生核心](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/internal/webcastsign/native.go)、[签名输入字段](https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/websocket_params.go)。上游仓库的 JS 回退来源并不由根 MIT 标签自动澄清，本轮完全排除，未运行其 JS 差分测试；只使用已公布的确定性向量进行本地验证。

依赖提案：本方案仅需现有 Java 21 标准库，**无新增 POM 依赖、无 Node/浏览器/Go 后台服务**。已新增唯一允许的 SPI 描述文件 `backend/src/main/resources/META-INF/services/cn.bigfish.collector.DouyinSigner`，注册实际 `NativeDouyinSigner`。主会话仍由主窗口接线，不能据此把供应商状态直接改为已验证。

官方能力核查（2026-11-01）：[互动工具指南](https://partner.open-douyin.com/docs/resource/zh-CN/live-interactive-tools/introduction/interactive-tools-guide) 仍注明暂停新插件准入，需平台审核且对服务端联网有约束；[直播玩法 FAQ](https://developer.open-douyin.com/docs/resource/zh-CN/interaction/introduction/zn/FAQ) 不开放主动获取任意全量评论。当前用户未提供官方应用权限。此原生签名方案是非官方网页协议兼容实现，不是官方稳定开放 API，也不用于绕过登录、验证码或其他限制。

## 实现、测试与交付结果

实现提交：`f02caaaa15758f48b89b343e0ad009a5aaa6b74b`（`feat(collector): add MIT-derived native Douyin signer and verify lifecycle`）。文档提交不在自身记录自身 SHA，最终回复报告分支 tip。

### 实际修改

- `NativeWebcastSignature`：MIT 原生核心的 Java 移植，MD5/单字节 RC4/自定义 Base64，安全随机源与受同步保护的序号；精确匹配两个上游公开向量。没有使用固定签名当作成功结果。
- `NativeDouyinSigner`：有序的 13 字段签名输入，SDK `1.0.15`，当前房间/访客/UA 与当前时间构造 WSS 目标。每次重新签名、nonce 绑定、本地 30 秒新鲜度预算、正确转义 `+` 等字符；响应中断，拒绝非法/陈旧请求。
- `DouyinSigners.load()` 和唯一 SPI 注册：加载真实默认实现；多个候选在执行构造函数前拒绝，配置/构造异常去除原因正文。完整 MIT 声明随该资源打包，不能在发行时丢弃。
- Provider/Collector：内部 roomId 改变先结束旧会话，再避免不必要的签名和握手；保留已有 lambda 提供器兼容，新增的是 **collector 包内连接接口** 的默认双参数重载，未改 `contract.CollectorPort` 或任何公共 DTO。
- 诊断：新增 `SIGNER_CONFIGURATION_INVALID`、握手尝试及房间变化计数、HTTP 发起数和签名次数；HTTP 403 的明确验证标题归 `CHALLENGE_REQUIRED`。Cookie 本机期限、401/403、页面变形、签名本机期限、握手拒绝仍分别使用固定码，不依据拒绝响应臆断一定是 Cookie 或签名问题。
- Probe：使用实际默认 SPI；保留显式开关、三次尝试上限和 60 秒默认时限；增加 FOLLOW/ENTRANCE/LIKE、HTTP/签名/握手次数及耗时，仍不输出业务内容或标识。
- 新增签名向量/SPI 配置/本地完整链路测试；重连刷新测试已接到真实原生 signer。增加礼物终包夹具字段，全部人工构造，无真实昵称/UID/原始帧。

### 验收矩阵

| 分项 | 本轮证据 | 真实平台结果 |
| --- | --- | --- |
| 签名 | 两个精确公开向量、6 位序号回卷、实际目标字段摘要、`+` 转义、过期/中断、实际 Provider/SPI 通过 | 已实现；线上是否接受 **未验证** |
| 房间解析 | 当前规范 URL → JDK HTTP 回环合成页面 → 内部 roomId；原页面三类形态、结束/冲突/状态测试通过 | 当前真实页面覆盖率 **未验证** |
| COMMENT | 本地链路 1 条，sessionId/schemaVersion 正确，进入事件接收器 | **未验证（0 条）** |
| GIFT | 本地 3 次增量 `1/2/1`，合计 4；同用户累计 0→3、同组重复终包/不同 msgId 终包、另一个用户同礼物同组隔离 | **未验证（0 条）**，不得声称礼物链路完成 |
| FOLLOW / ENTRANCE | 本地各 1 条，与 COMMENT/GIFT 同一真实 JDK 传输链路 | **未验证（各 0 条）** |
| ACK / 心跳 | 回环 peer 实际收到 ACK/Ping，既有 Pong/分片/压缩测试通过 | **未验证** |
| 重连 | 实际原生 signer 重签，重新读凭据及页面；同会话连击状态保持；退避上限/抖动/stop 取消测试通过 | **未验证** |
| 关播/换播 | 本地控制包 ENDED；解析返回已关播 ENDED；内部 roomId 改变不做第二次签名/握手 | **未验证** |
| 非法包/慢回调 | 非法帧之后仍正常投递；已有队列容量/过期、慢业务回调不阻塞 ACK/心跳测试通过 | 仅本地验证 |
| 释放/取消 | HTTP 等待、签名、握手、接收、退避取消；stop 幂等； source/dispatch/队列/transport、HTTP 在途和签名 worker 清零。拒绝不合作扩展积累线程 | 仅本地验证，不代表 JVM 所有线程数为零 |

本地组合用例 `NativeCollectorPipelineTest` 使用实际 JDK HTTP/WebSocket，但只在测试 seam 将地址替换成 127.0.0.1；每次用例 **1 次 HTTP、1 次签名、1 次 WS 握手、6 个业务事件、3 个重复抑制、1 个异常包**，正常关播且不重连。2026-11-01 16:56 回归中的用例耗时 0.035 秒。不能将它报告为真实抖音端到端通过。

### 已运行命令与结果

均在 collector 工作树执行，测试默认不访问外部服务。

| 命令/检查 | 实际结果 |
| --- | --- |
| `pwd`、`git branch --show-current`、`git status --short` | 指定路径、`feature/douyin-collector`，开工干净 |
| `git merge --no-edit main`（开工） | 从 `105304d` 快进至 `e39ca19893cde16a27937eac30c415db11a726cb` |
| `./scripts/maven.sh -B -ntp test`（基线） | 140 项，139 通过、1 跳过，0 失败/错误 |
| `./scripts/maven.sh -B -ntp -pl backend -DskipTests compile` | BUILD SUCCESS |
| `./scripts/maven.sh -B -ntp test`（本轮代码，16:53:47） | 149 项，148 通过、1 跳过，0 失败/错误；20.572 秒 |
| `./scripts/maven.sh -B -ntp test`（原生重连测试后，16:56:09） | 149 项，148 通过、1 跳过，0 失败/错误；15.340 秒；其中 collector 67/67 通过 |
| `./scripts/maven.sh -B -ntp test`（交付同步后，16:59:18） | BUILD SUCCESS，149 项，148 通过、1 跳过，0 失败/错误；16.901 秒；collector 67/67 通过，本地组合用例 0.036 秒 |
| `./scripts/dev-backend.sh -B -ntp -Dspring-boot.run.main-class=cn.bigfish.collector.DouyinCollectorProbe` | 默认保护输出 `PROBE_DISABLED`，Java 退出 2，Maven 因此退出 1 / BUILD FAILURE（预期）；总启动检查 5.058 秒 |
| `git diff --check` | 通过 |

首次新增测试编译中曾误用 DTO 方法 `version()`；已修正为既有 `schemaVersion()`，未改公共 DTO，上述后续回归均通过。唯一跳过项是原有 `AlibabaNlsLiveTest`；没有调用真实 DeepSeek/TTS，没有产生服务费用。

平台请求与观察：**真实抖音页面 HTTP 0 次，WSS 0 次，真实接收观察 0 秒**。Probe 启动测试只验证默认关闭，未启用 live probe。指定变量只做存在性检查：LIVE_PROBE、ROOM、COOKIE、USER_AGENT、USER_UNIQUE_ID、CREDENTIALS_EXPIRES_AT、SIGNER_REVIEWED 均未注入；没有读取/输出变量值、旧配置或主项目密钥。GitHub/官方文档的只读来源研究不是抖音接收证据。

### 交给主窗口的工厂与参数

不新增 POM 依赖；只需随发行物包含本轮唯一 SPI 资源。直接注入的最小示例：

```java
var signer = new NativeDouyinSigner(); // 或 DouyinSigners.load()，唯一内置实现
WebDouyinConnectionProvider.CredentialSource credentials = () ->
    new DouyinCredentials(cookieFromBackendMemory, matchingUserAgent,
        matchingBrowserVisitorId, allowedUntil);
var provider = new WebDouyinConnectionProvider(credentials, signer);
var collector = new DouyinCollector(provider);
collector.start(currentCanonicalWebRoom, sessionId, boundedEventIngress);
// 取消/关闭旧会话：collector.stop(); 幂等，随后观察 resources() 与 Provider 在途数。
```

`CredentialSource` 每次重连调用，凭据只来自后端本地内存，不从页面/前端 DTO 暴露。环境版本的轮换需重启进程；主会话可以提供可轮换内存源。Provider 无固定房间、Cookie 或缓存签名。异步状态读取 `state()` / `diagnostics().lastError()`；等待 `resources().released()`、`provider.inFlightRequests()==0`、`provider.signingWorkers()==0` 后再开新会话。

共享接线提案（本窗口未改）：主会话的 `SIGNER_NOT_IMPLEMENTED` 文案已不再准确，建议调整为“原生签名已实现，当前平台接收尚未验证”；继续禁用或明确标注待验收，不把 `douyinCollector` 改为 true。受审实现来源可用 `signer.provenance()` 在后端校验；它是声明而不是自动许可证验证。UI/API/配置工厂、SessionService、公共错误 DTO 由主窗口统一处理。未变更任何 POM/公共配置/contract/Vue。

### 尚缺条件和后续只读验收

1. 用户提供今天仍在播的规范链接，Cookie/匹配 UA/浏览器访客 ID/允许使用期限通过本机安全环境注入，不能发在聊天。
2. 当前原生 signer 初始请求使用空 cursor、新生成 internal_ext 和固定允许的 lf WSS 主机。没有实现上游的 HTTP A-Bogus/IM fetch 引导，没有复制历史 cursor/wrds_v，也未伪造屏幕/系统画像。是否必须先拿平台 cursor/push server 是明确的线上未知；失败时停止并重新评估，不自动轮换或绕过限制。
3. 先显式 `resolve` 一次，再 `receive` 60 秒（不超过 120 秒，最多 3 次尝试）；所需环境和命令见 [provider 文档](../providers/douyin.md)。至少实际 COMMENT 才能通过评论验收；FOLLOW/ENTRANCE/GIFT 分别计数、分别报告。无自然礼物不延长等待、不主动送礼。
4. 若出现登录/验证码/地区/限流，返回固定码交用户处理；握手被拒绝不能仅凭状态推断是 Cookie 或签名过期。取得授权的最小去标识样本后再适配结构，不能提交完整真实帧。
5. 当前只能交付“实现和本地验证完成、线上待联调”，不能将来源声明、算法向量、HTTP 200、WSS 握手或本地替身当成真实端到端验收。
