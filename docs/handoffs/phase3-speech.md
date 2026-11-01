# 第三轮语音：Token 签发、续期与恢复

日期：2026-11-01。代码及本地验证完成；本轮真实 CreateToken/合成请求均为 **0**。主项目既有静态 zhigui 真实成功结果保留；不能将其当成本轮 RAM 签发或全链路自动续期证据。

## 基线、范围和提交

- 工作目录：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/frontend`；分支 `feature/frontend-stage`；端口仍 18082/15175。
- 开工先执行 pwd、branch、status，工作区干净；合入 main，快进至 `e39ca19893cde16a27937eac30c415db11a726cb`。`git merge-base --is-ancestor c8be17c HEAD` 和 `git merge-base --is-ancestor main HEAD` 均为 0。
- 实现提交 `8cfd100186ddb8909455af710beeb6bad2d1ab04`：官方 CreateToken POST 签名客户端、有界 HTTP、脱敏错误与离线/本地请求测试。
- 实现提交 `2821855d59a8a385fbb356b97c283c7ae3af731e`：显式 STATIC/RAM 工厂、沿用刷新器补恢复能力、取消/退避/时钟/竞态测试、独立真实入口。
- 实现提交后工作区干净，再次 `git merge --no-edit main`，合入 main 的 `55a6fa04758b86be5550af5fd1c3d07cbc560b3c`；合并提交 `7c58a0af245d3cfd17c35b762eb35bad88987957`，只带入 main 的 README 更新，无冲突。两项祖先检查再次为 0，随后完整测试通过。
- 修正提交 `8e43022c01925c708f7bd012dc8dfcb5952ac363`：结合服务器 Date 和单调经过时间限制真实有效期，防止本机中等幅度慢钟延长 Token 使用；真实 ExpireTime 不改写。
- 本交接及供应商文档另作收尾提交，文档提交 SHA 在当前任务最终回复报告，避免把文档自己的 SHA 写入自身。
- 自行修改仅 speech 包、speech 测试、`docs/providers/aliyun-nls.md` 和本文件。未修改 configuration、RuntimeEngine、contract、Vue、POM、公共 DTO/API 或主工作树；未推送、建 PR、联系其他聊天。

## 实际实现

生产文件位于 `backend/src/main/java/cn/bigfish/speech/`：

| 文件 | 内容 |
|---|---|
| AlibabaNlsTokenClient.java（新增） | 固定上海官方 HTTPS CreateToken、POST 表单、真实 Token.Id/ExpireTime、有界异步请求、取消/关闭、错误码/时钟偏移识别；构造不调用云服务 |
| NlsRpcSignature.java（新增） | POP RPC 1.0、HMAC-SHA1、排序和 RFC3986 编码、UTC 时间、新 UUID nonce；签名只在请求体 |
| NlsTokenBody.java（新增） | 16 KiB 有界文档读取，取消关闭订阅，不暴露响应正文 |
| NlsTokenProvider.java（内部增量） | Token 增加包内服务器时间有效期上界，原公开构造签名保持兼容，未修改 contract 包 |
| NlsTokenProviders.java（新增） | 可注入环境读取函数的工厂和只读脱敏 Snapshot；默认 STATIC，显式 RAM；不存在跨模式/跨账户 fallback |
| RefreshingNlsTokenProvider.java（增量修改） | 保留原单飞结构；独立等待期限、最后等待者取消、指数退避、有限旧 Token 复用、单调有效期、旧/新回调保护、状态快照 |
| AlibabaNlsSpeech.java（小改） | 环境工厂委托 Token 工厂；每次重试发送前 acquire，避免冷却期间 Token 过期；PCM→WAV 路径未改 |
| SpeechFailure.java（新增枚举值） | 稳定 TOKEN_MODE_INVALID、TOKEN_AUTH_REJECTED、TOKEN_SIGNATURE_INVALID、TOKEN_CLOCK_SKEW、TOKEN_RESPONSE_INVALID、TOKEN_REQUEST_REJECTED、TOKEN_RATE_LIMITED、TOKEN_PROVIDER_UNAVAILABLE、TOKEN_TRANSPORT_FAILED |

新增测试：`NlsRpcSignatureTest`、`AlibabaNlsTokenClientTest`、`NlsTokenProvidersTest`、`NlsRefreshRecoveryTest`、`NlsTokenSpeechRecoveryTest`、`AlibabaNlsRamLiveTest`。原 `NlsTokenProviderTest` 仅将“拒绝当前 Token”状态断言从 MISSING 改成更明确的 REJECTED，其他原断言保留。

**已保留 c8be17c：** 请求 format=pcm → 完整接收 → 本地按实际长度封装 WAV → 严格检查。`PcmWav.java`、`PcmWavTest.java`、原 `AlibabaNlsSpeechTest` 及原静态 `AlibabaNlsLiveTest` 均未修改。不存在恢复供应商 WAV、删长度/格式/静音/时长检查或削弱解码回归。

## 刷新与恢复规则

- `NlsTokenProviders.create(enabled, environment, mapper)`：disabled 时连环境都不读取。默认 STATIC 只读取现有 NLS Token；RAM 只读取独立 AccessKey ID/Secret。构造、status、snapshot、配置加载不发请求；实际 synthesize 的 acquire 才能触发签发。
- 上海端点、RegionId 和版本固定；Token 客户端最多一个在途调用，一次调用一次 POST、不重试、不重定向；连接最多 2 秒，完整调用最多 3 秒，文档最多 16 KiB。无新 SDK/POM。
- 原刷新器单飞，16 等待者上限，一工作线程和一个排队位；提前 30 秒按需刷新，或本次请求期限加 5 秒余量已不足时刷新。空闲不会定时请求云服务。
- 等待者各有期限，首个短等待者不会取消其他长等待者；最后一个等待者取消/超时后终止本轮。失败从 200ms 指数退避至最多 30 秒，后续 acquire 才能重试；成功清零连续失败。
- 临时网络/超时/429/服务不可用错误可复用旧 Token，但必须未被拒绝且仍覆盖本次剩余期限 + 5 秒；报告 USING_VALID_CACHED 和失败码。鉴权、签名、时钟、格式错误不走该回退。
- ExpireTime 使用服务返回的真实 Unix 秒，不假设固定 TTL。缓存按墙上时钟、签发响应 Date 保守折算的上界与单调经过时间取较短有效期；已观察过期不能因回拨复活。服务器 Date 偏差超过 5 分钟报 TOKEN_CLOCK_SKEW；小于阈值的慢钟也不会延长服务器已确定的有效期。无 Date 时依赖正常同步的本机时钟；不调整系统时间。
- 旧 Token 被拒后，刷新返回相同值也不再发布；不同新 Token 不受旧请求迟到拒绝影响；旧轮次迟到完成不能覆盖新轮次。TTS 的 401/403 不重试该音频，下一个新任务可重新取 Token；成功音频不会重复发布。
- STATIC 保持原有未知到期状态和拒绝行为。RAM 不回退 STATIC；不支持 STS/角色元数据/自动凭据链，修改 RAM 凭据/模式需关闭旧实例并重建。

## 运行与测试结果

| 命令/检查 | 实际结果 |
|---|---|
| 开工及交付前 `git merge --no-edit main` | 首次快进、末次合并，无冲突；保留已有提交 |
| 两次 `git merge-base --is-ancestor c8be17c HEAD` / `main HEAD` | 都返回 0 |
| `./scripts/maven.sh -B -ntp -pl backend -DskipTests compile` | BUILD SUCCESS |
| `./scripts/maven.sh -B -ntp -pl backend '-Dtest=cn.bigfish.speech.*Test' test` | 初轮发现 401 空响应误报响应格式错误，已修复；随后 51 项，49 通过、2 live 跳过 |
| `./scripts/maven.sh -B -ntp test` | 交付前合入 main 后 164 项通过；补服务器时钟上界回归后最终 **165 项，163 通过，0 failures/errors，2 skipped，BUILD SUCCESS** |
| `git diff --cached --check` | 实现提交通过；收尾文档再次检查 |

最终完整测试中 speech 共 53 项（51 本地通过、2 真实默认跳过）；本轮新增 24 本地测试和 1 RAM live 测试。使用工作树 Maven 缓存、本机随机 HTTP 端口；由于沙箱限制本地监听/JVM attach，按既有授权在沙箱外运行测试，未关闭断言或修改依赖。未启动常驻服务；后续启动仍须用 scripts/dev-backend.sh / scripts/dev-frontend.sh。Vue 未改，本轮未跑前端构建。

具体证据：官方 GET 假凭据固定向量匹配、POST 向量由 Python stdlib 独立核算；本地请求验证签名/nonce/时间/无查询参数；响应解析覆盖缺字段、类型错误、重复字段、尾随内容、过期、超大、错误码、空 401、拒绝重定向；超时、持续分块、取消、关闭和下一次恢复通过。可控时钟覆盖提前刷新、退避、有效旧 Token 临时复用、过期停止、回拨不复活、单飞、最后等待者取消、旧轮次迟到、新旧 Token 拒绝竞争。

Token→PCM 本地联测为 **2 次签发 / 3 个音频任务**：第一条成功、第二条旧 Token 被拒、第三条新 Token 成功；第一条文件内容不变，没有重播，Java Sound 样本/时长一致并释放。另验证 429 冷却时已过期的 Token 不发送第二次。原 PCM→WAV、错误文档拒绝、严格校验、Java Sound、超时/文件释放回归全部通过。

## 主窗口需注入的参数与状态映射提案

当前 `ProviderServices` 仍显式构造 `StaticNlsTokenProvider`，本任务未改它，因此**当前主会话尚未自动切换为 RAM 续期**。主窗口需实施：

1. 沿用选择 ALIYUN_NLS + BIGFISH_ALLOW_PAID 的显式门控。解析 `BIGFISH_ALIYUN_NLS_TOKEN_MODE`，省略默认 STATIC；STATIC 就绪检查 AppKey+NLS Token，RAM 就绪检查 AppKey+独立 AccessKey ID/Secret。缺配置与签发未验证要区分，不能拿配置存在当认证成功。
2. 建立并持有 `NlsTokenProviders.Handle`，注入现有 `AlibabaNlsSpeech.create` 重载。先关闭旧实例再改变模式/凭据；保留现有 release、cancel、session/TTL/音频 25 秒限制和音色设置。
3. 管理状态只映射 Snapshot 的安全值：建议 mode、status、expiresAt、remainingSeconds、refreshResult、lastError（NLS_ 前缀）、lastAttemptAt/lastSuccessAt；可选重试等待和计数。不得新增 Token/AccessKey/签名正文/URL 字段，不序列化 Handle 或环境对象。
4. RAM 构造后的 MISSING + NOT_REQUESTED 表示“尚未获取 Token”，不是配置缺失；缺配置会在创建前/工厂报 MISSING_CREDENTIALS。SUCCESS 只证明签发成功，ACCEPTED 才表示该 Token 已被一次合成成功使用；两者都不是长期稳定性保证。USING_VALID_CACHED 展示“刷新失败，临时使用仍有效 Token”，不能隐藏 lastError。
5. 本任务未修改共享 contract/API/POM；上述管理字段及 ProviderServices 接线交主窗口决定。没有 SDK 依赖提案。将来 STS 必须另定 SecurityToken、凭据实际到期时间、刷新凭据策略，不能把 STS 当普通长期 RAM Key 自动尝试。

最小示例（后端安全注入；未更改公共接口）：

```java
// enabled 来自明确选择真实 NLS 且服务器允许调用。
NlsTokenProviders.Handle tokens = NlsTokenProviders.create(enabled, env::getProperty, mapper);
AlibabaNlsSpeech speech;
try {
    speech = AlibabaNlsSpeech.create(enabled, audioDirectory, mapper,
        env.getProperty("BIGFISH_ALIYUN_NLS_APP_KEY"), tokens);
} catch (RuntimeException failure) {
    tokens.close();
    throw failure;
}
NlsTokenProviders.Snapshot safeStatus = tokens.snapshot(); // 只读，不访问云服务。
// 原 RuntimeEngine 的 synthesize / cancel / release 流程继续使用；关闭 speech 会关闭 tokens/client。
```

注入变量：默认 STATIC 使用已有 `BIGFISH_ALIYUN_NLS_TOKEN`；显式 `BIGFISH_ALIYUN_NLS_TOKEN_MODE=RAM` 使用 `BIGFISH_ALIYUN_ACCESS_KEY_ID`、`BIGFISH_ALIYUN_ACCESS_KEY_SECRET`；两者均需要原 `BIGFISH_ALIYUN_NLS_APP_KEY`。工厂参数是 `Function<String,String>`，主窗口可传 `env::getProperty`，不需要在网页保存凭据。

## 真实验收条件与限制

本轮只检查本工作树进程中指定凭据是否存在，四项均 absent；没有打印值，没有读主项目 IDEA 配置、旧 Luna 或全量环境。用户主项目已有的静态 NLS Token 不代表本工作树存在 RAM AccessKey。缺少的条件为：本工作树后端独立注入的 RAM ID/Secret、允许上海 CreateToken 的身份权限、与身份匹配的 NLS AppKey。

已交付 `AlibabaNlsRamLiveTest`：必须显式 `-Dbigfish.nls.ramLive=true` 且环境模式 RAM，才进行 **最多一次 Token 签发和一条 zhigui 合成**；不重试、不循环等过期，输出只含脱敏元数据，最后单文件释放。默认跳过。配置和隐藏输入步骤见 [aliyun-nls.md](../providers/aliyun-nls.md#ram-独立真实验收默认关闭)。不要在聊天粘贴凭据，不自动创建权限/账户/密钥或购买服务。

真实签发、真实 RAM 身份到合成权限、主会话自动续期状态展示、浏览器播放链路与长期运行恢复仍待集成窗口验收。本轮本地证据不替代这些层；也没有否定或覆盖 c8be17c 已验证的静态 zhigui 真实链路。
