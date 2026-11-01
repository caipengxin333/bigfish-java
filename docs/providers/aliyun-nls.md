# 阿里云 NLS 短文本语音（Java）

Java `SpeechPort` 适配器已接入主会话，可显式选择 ALIYUN_NLS；默认仍为 LocalTestSpeech。2026-11-01 已验证 zhigui 真实短句、解码及 Safari 播放回执。无新增依赖，不使用 Edge-TTS，不复制旧项目模型、配置或密钥。

## 官方依据与固定选择

TTS 文档核对日期：2026-10-30；Token 签发文档重新核对：2026-11-01。

- [阿里云 NLS REST TTS](https://help.aliyun.com/zh/isi/user-guide/restful-api-3)：短文本最多 300 字符，超过可能截断；POST JSON 的 text 不做 URL 编码；成功响应可能标为 `audio/mpeg`，即使请求的是 WAV。
- [音色及采样率](https://help.aliyun.com/zh/isi/user-guide/overview-of-speech-synthesis)：本轮仅开放下表已核对的 16 kHz 音色子集，不代表当前项目账户已购买或开通所有音色。
- [获取 Access Token](https://help.aliyun.com/zh/isi/user-guide/obtain-an-access-token)：项目 AppKey 与访问 Token 是两种凭据；Token 签发需要后端 RAM 身份及服务权限。不是百炼 DashScope API Key。

生产入口固定为 `https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/tts`。只使用上海地域，不接受页面/配置传入任意 URL，不跟随重定向。POST UTF-8 JSON；Token 仅在 `X-NLS-Token` 请求头，AppKey 在 JSON。没有包含 Token 的查询参数。使用中国内地上海项目及其适用的 NLS Token；其他地域需要单独评估，不能直接更换 URL 试探。

| 标识 | 官方描述摘要 | 本轮选择 |
|---|---|---|
| xiaoyun | 标准女声、小云 | 默认建议 |
| xiaogang | 标准男声、小刚 | 可选 |
| zhixiaomei | 女声、数字人直播 | 可选 |
| zhigui | 女声、直播 | 可选 |
| aixia | 女声 | 可选 |

音色必须明确选择且属于 `AlibabaNlsSpeech.VOICES`；`zh-CN-XiaoyiNeural` 是 Edge 标识，直接传入返回 `NLS_UNSUPPORTED_VOICE`，不会猜测映射或偷偷降级。云端请求固定 format=pcm / 16000 Hz / PCM 16-bit 单声道，volume=50、speech_rate=0、pitch_rate=0；完整接收后由 PcmWav.fromPcm16Mono 按实际长度封装为标准 WAV，继续严格校验，SpeechPort 与浏览器仍接收 audio/wav。扩展公开设置交主窗口提案。

真实 zhigui 请求曾返回 HTTP 200，但 WAV 头声明 230400 字节 PCM，实际仅 68798 字节，导致 NLS_INVALID_WAV。`c8be17c` 改用上述 PCM 路径；不能删除 RIFF/chunk 长度校验或任意改写供应商错误头来掩盖问题。原 WAV 严格检查仍保留，响应中的 JSON/HTML/XML 错误文档不得作为音频发布。

只接受纯文本。空白、非法代理字符、控制字符（换行/制表符除外）、`<`/`>`（避免意外 SSML）本地拒绝。长度采用保守 UTF-16 计数，最多 300；补充平面字符计为 2。超限返回 `NLS_TEXT_TOO_LONG`，不截断、不拆分、不并发发整段长回复。

## 工厂、所有权与生命周期

```java
// 供主窗口接线：audioDirectory 必须与本实例 AudioAssets 的目录一致。
// enabled 必须来自明确选择 ALIYUN_NLS 且允许真实调用，不能直接传 true 作为默认 Bean。
AlibabaNlsSpeech speech = AlibabaNlsSpeech.create(enabled, audioDirectory, objectMapper);
SpeechPort.AudioAsset asset = null;
try {
    asset = speech.synthesize(taskId, shortText, "xiaoyun", Duration.ofSeconds(5))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    var content = audioAssets.load(asset); // 内存加载完再登记会话/任务。
    // 原有 sessionId、过期时间与播放租约检查继续生效。
} catch (Exception error) {
    speech.cancel(taskId); // 调用方 get 超时也必须取消供应商任务。
    throw error;
} finally {
    if (asset != null) speech.release(asset); // load 失败也释放；不可在 synthesize 返回前删除。
}
// 停止/旧会话/调用方超时：speech.cancel(taskId)
// 应用关闭：speech.close()
```

- 工厂 disabled 时返回 `NLS_DISABLED`，不读取环境；凭据缺失返回 `NLS_MISSING_CREDENTIALS`。集成应保留无凭据启动能力，只在明确选用且凭据就绪时创建真实适配器。
- 两个并发任务、无等待队列；重复在途 taskId 拒绝。返回 future 的 cancel 与 `cancel(taskId)` 都关闭该请求的 HTTP 客户端、订阅和等待，并回收该任务预留文件。不能把取消后的迟到结果发布为成功。
- 每个任务一个 UUID 文件，`CREATE_NEW`，不覆盖老任务。目录要求支持文件锁和 fileKey；每个目录只有一个适配器实例，跨实例冲突返回 `NLS_FILE_IO`。无递归清理/批量删除。
- 同时最多 8 个预留或成功未释放文件；每个不超过 2,000,000 字节。历史 `aliyun-nls-*.wav` 只计数、不自动删除，重启不能绕过磁盘上限。音频资源上限约 16 MB，不包括调用方自己放入的外部文件。
- `release(asset)` 仅删除此实例拥有的那个路径且校验文件身份，重复调用返回 false；不删除外部文件。`cancel(taskId)` 也可释放已成功但尚未读取的单个任务文件。
- `close()` 取消在途任务并关闭线程/TokenProvider/目录锁；成功文件仍由调用方显式 release。进程崩溃遗留文件需用户按明确路径逐个处理；不自动扫描删除。为试听保留的文件同样占额度。
- 文件头、RIFF 总长度、chunk 边界、PCM 参数、非空 data、全零静音均验证；禁止把 JSON/HTML 当成功音频。最长 60 秒，duration 根据 PCM 字节精确计算。完整结构/非静音不能证明是人声；真实人声仍需单独试听。

## 超时、失败与 Token

总 deadline 从 `synthesize` 入场计时，包含凭据等待、重试冷却、HTTP、验证和写入，不因数据持续抵达而延长；内部额外上限 120 秒不会延长调用方期限。连接最多 3 秒，响应体连续无数据最多 2 秒，等响应头也受总期限限制。没有无界队列、无界响应体或无界重试。

只有 HTTP 429/5xx、NLS JSON 40000005/50000000/50000001 可重试，总共最多 2 次；最少冷却 1 秒；整数 Retry-After 在 120 秒冷却上限内取较大值，预算不足不重试。超大或无法识别的 Retry-After 按 120 秒冷却处理并停止本次重试。401/403、NLS 40000001、参数错误不重试；网络/读取/格式失败不自动重试。不存在失败自动回退成测试音或另一家供应商。

所有对外异常仅有 `SpeechFailure.Code` / `NLS_<CODE>`，没有原始 cause、响应正文、文本或凭据。状态可通过 `diagnostics()` 获取安全计数、在途数、未释放文件数、Token 状态、最后错误码；禁止把 TokenProvider、HTTP 调试头或请求正文写入日志。

| 错误类别 | 例子 | 集成处理建议 |
|---|---|---|
| 配置/凭据 | MISSING_CREDENTIALS、TOKEN_EXPIRED、TOKEN_REJECTED、UNSUPPORTED_VOICE | 明确提示并等待纠正，不能循环付费重试 |
| 资源/限流 | BUSY、COOLDOWN、RATE_LIMITED、FILE_LIMIT | 有限丢弃/等待下一个事件，检查文件释放 |
| 截止/取消 | DEADLINE_EXCEEDED、CONNECT_TIMEOUT、READ_TIMEOUT、CANCELLED、CLOSED | 丢弃任务，立即停止旧会话播放 |
| 服务/内容 | PROVIDER_UNAVAILABLE、INVALID_RESPONSE、INVALID_WAV、AUDIO_TOO_LARGE、AUDIO_TOO_LONG、AUDIO_SILENT | 记录脱敏错误码，不能发布播放任务 |

静态环境 Token 的初始状态是 **UNVERIFIED**，没有伪造的有效期；只有收到并验证成功 WAV 后才是 ACCEPTED，表示最近一次接受，不保证未来仍有效。HTTP 401/403 或 NLS Token 拒绝后标 REJECTED，后续请求不再发送；替换环境变量后重建适配器/重启。若后端知道确切签发到期时间，可用 `StaticNlsTokenProvider(token, expiresAt)` 提前本地拒绝过期。

## 第三轮：Token 签发与模式工厂

2026-11-01 已实现 `AlibabaNlsTokenClient`，并接入原有 `RefreshingNlsTokenProvider`，**本地替身通过，真实 RAM 签发尚未验证**。不引入 SDK，不改 POM。原 zhigui 真实合成和 PCM→WAV 修复继续保留。

依据 [官方 OpenAPI Token 文档](https://help.aliyun.com/zh/isi/user-guide/use-http-or-https-to-obtain-an-access-token)：固定 HTTPS `nls-meta.cn-shanghai.aliyuncs.com/`、cn-shanghai、CreateToken / 2019-02-28。使用 POST 表单及 POP RPC v1 HMAC-SHA1：参数排序、RFC3986 编码、UTC 秒级时间戳、新 UUID nonce。签名在请求体，不生成带签名的请求 URL，不跟随任何重定向；请求体也不得记录。官方 GET 固定假凭据向量与独立计算的 POST 向量均有测试。成功只解析真实 `Token.Id` 与 Unix 秒级 `Token.ExpireTime`，不假设 24 小时有效。

签发客户端每次只发一次请求，不自动重试；最多 1 个在途请求，响应最多 16 KiB，连接最多 2 秒、包括完整响应体的总期限最多 3 秒；取消关闭订阅和该次 HttpClient。拒绝缺字段、重复字段、尾随 JSON、过期/异常期限与错误正文。签发失败与 NLS 合成失败分别有稳定代码：`TOKEN_AUTH_REJECTED`、`TOKEN_SIGNATURE_INVALID`、`TOKEN_CLOCK_SKEW`、`TOKEN_RESPONSE_INVALID`、`TOKEN_RATE_LIMITED`、`TOKEN_PROVIDER_UNAVAILABLE`、`TOKEN_REFRESH_TIMEOUT` 等，统一以 `NLS_` 前缀对外，不保留原始异常 cause 或供应商 Message。

以下环境名是**供主窗口接线的命名约定**。内部工厂已经支持，当前 `ProviderServices` 仍显式使用静态 Token；仅设置 RAM 环境变量不会自动改变当前主会话。

| 模式/变量 | 行为 |
|---|---|
| `BIGFISH_ALIYUN_NLS_TOKEN_MODE` | 省略/空值默认 STATIC；只接受 STATIC、RAM；未知值报 TOKEN_MODE_INVALID |
| STATIC：`BIGFISH_ALIYUN_NLS_TOKEN` | 完全保留原用户配置；未知到期时间保持 null，不签发、不刷新，不读取 RAM 凭据 |
| RAM：`BIGFISH_ALIYUN_ACCESS_KEY_ID` / `BIGFISH_ALIYUN_ACCESS_KEY_SECRET` | 独立后端 RAM 身份；缺失立即报 MISSING_CREDENTIALS，不回退到静态 Token 或其他账户 |
| 两种模式：`BIGFISH_ALIYUN_NLS_APP_KEY` | 继续用于语音合成；需与签发身份所授权的 NLS 项目匹配 |

本轮**不支持 STS SecurityToken、角色元数据或凭据链自动发现**，没有隐式账户选择。RAM AccessKey 的安全注入和权限由用户/管理员负责，适配器不创建账号、权限、密钥或购买服务；更换 RAM 凭据/模式须关闭旧实例并重建。不得复制主项目 IDEA 配置或把凭据填进网页。

后端最小调用方式（主窗口实施，示例不会自行改变公共 DTO）：

```java
// 仅在用户明确选 ALIYUN_NLS 且服务器允许真实调用后创建；默认模式仍 STATIC。
NlsTokenProviders.Handle tokenProvider = NlsTokenProviders.create(enabled, env::getProperty, mapper);
AlibabaNlsSpeech speech;
try {
    speech = AlibabaNlsSpeech.create(enabled, audioDirectory, mapper,
        env.getProperty("BIGFISH_ALIYUN_NLS_APP_KEY"), tokenProvider);
} catch (RuntimeException error) {
    tokenProvider.close();
    throw error;
}
// 首次 synthesize 才按需 issue；构造、snapshot/status 均不访问云服务。
NlsTokenProviders.Snapshot tokenState = tokenProvider.snapshot();
// 语音成功仍须 release；取消任务仍 cancel；speech.close() 同时关闭 TokenProvider/client。
```

`AlibabaNlsSpeech.create(enabled, directory, mapper)` 的环境工厂也已委托 `NlsTokenProviders`，默认 STATIC 兼容原行为；当前主会话使用的是另一注入重载，主窗口需要主动替换其 StaticNlsTokenProvider 接线。请只将 snapshot 的允许字段映射到将来的管理响应，不序列化 Token、Handle、HTTP 请求或环境映射。快照仅包含模式、状态、真实到期/剩余时间、刷新结果/稳定错误码、尝试/成功时间及有界计数；STATIC 的到期/剩余时间为 null。

### 刷新与恢复策略

- 沿用原刷新器；接近到期（提前 30 秒，或不足本次请求期限 + 5 秒余量）时，在下一次 acquire 中启动后台刷新。空闲时不签发；轮询不会触发云请求。
- 单飞、最多 16 个等待者、一个工作线程和最多一个有界排队位；每轮最多 3 秒，每个等待者有自己的更短期限。首个短等待者超时不会拖垮其他等待者；最后一个等待者取消/超时后中止签发。迟到回调按轮次身份丢弃。
- 失败从 200ms 开始指数退避，最多 30 秒。没有定时自动重试风暴，下一次请求在冷却结束后才可重新签发。成功清除连续失败计数。
- 只有网络、超时、429/服务暂不可用等明确临时错误可暂用旧 Token；旧 Token 必须未被拒绝，且剩余有效期严格大于本次剩余期限 + 5 秒。状态会显示 USING_VALID_CACHED 和刷新失败码。鉴权/签名/时钟/格式错误不采用该回退。
- 缓存同时使用真实 ExpireTime、签发响应 Date 保守折算的有效期上界与单调经过时间；取较短有效期。Date 上界包含请求耗时和秒级精度余量，不改写对外 ExpireTime。已观察过期不会因系统时钟回拨复活；服务器 Date 与本机偏差超过 5 分钟报 TOKEN_CLOCK_SKEW，不自动修改时钟。较小幅度的本机慢钟也不会延长服务器已确定的有效期；无 Date 时依赖本机正常时钟。运行主机仍应同步系统时间。
- 被合成服务拒绝的当前 Token 立即作废，即使刷新返回同一个值也不能重新发布；不同的新 Token 不会被旧请求的迟到拒绝作废。下次新任务获取新 Token；不会重播之前成功的音频。
- 原合成重试在每次发送前重新 acquire，避免冷却期间过期后继续发旧 Token；仍保留最多两次、共同总 deadline，401/403 不重试该音频。PCM/WAV/非静音/时长/释放检查不变。

### RAM 独立真实验收（默认关闭）

本工作树当前未注入 RAM 凭据，本轮官方签发和合成调用次数均为 0。主项目既有静态 zhigui 成功记录不等于 RAM 已成功，也不等于全链路自动续期已经接线。

用户在本工作树自己的 zsh 终端隐藏输入，不能将值放进聊天、命令字面量或 Git：

```zsh
cd '/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/frontend'
read -rs 'BIGFISH_ALIYUN_NLS_APP_KEY?NLS AppKey（隐藏输入）: '
read -rs 'BIGFISH_ALIYUN_ACCESS_KEY_ID?RAM AccessKey ID（隐藏输入）: '
read -rs 'BIGFISH_ALIYUN_ACCESS_KEY_SECRET?RAM AccessKey Secret（隐藏输入）: '
export BIGFISH_ALIYUN_NLS_APP_KEY BIGFISH_ALIYUN_ACCESS_KEY_ID BIGFISH_ALIYUN_ACCESS_KEY_SECRET
export BIGFISH_ALIYUN_NLS_TOKEN_MODE=RAM
source runtime/worktree.env
./scripts/maven.sh -B -ntp -pl backend -Dtest=AlibabaNlsRamLiveTest \
  -Dbigfish.nls.ramLive=true "-Dbigfish.nls.audioDirectory=$PWD/runtime/audio" test
unset BIGFISH_ALIYUN_ACCESS_KEY_ID BIGFISH_ALIYUN_ACCESS_KEY_SECRET BIGFISH_ALIYUN_NLS_TOKEN_MODE
```

这次验收**最多一次 Token 签发和一条 zhigui 短句合成**，失败不重试，不靠反复调用等待过期。输出仅有次数、到期时间、耗时、字节数、duration、Java Sound 解码结果；文件在 finally 单个释放，不自动播放。默认整个测试套件同时跳过此入口和原静态真实入口。缺凭据时不要开启，不把跳过算成功。续期/时钟/竞态已通过可控时钟与本地替身，不做收费长测。当前主会话仍需主窗口接线和最终实测。

## 本机凭据配置与独立验证

主项目已于 2026-11-01 用本机凭据完成 zhigui 真实验证，凭据未提交。其他工作树与进程仍需独立配置 `BIGFISH_ALIYUN_NLS_APP_KEY` / `BIGFISH_ALIYUN_NLS_TOKEN`；确认已开通 NLS TTS、上海项目 AppKey 及未过期的 NLS Token。不要把值发到聊天、写入仓库或粘贴进带字面量的命令历史。

在自己的 **zsh 终端**中运行，输入过程不回显：

```zsh
cd '/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/frontend'
read -rs 'BIGFISH_ALIYUN_NLS_APP_KEY?NLS AppKey（隐藏输入）: '
read -rs 'BIGFISH_ALIYUN_NLS_TOKEN?NLS Token（隐藏输入）: '
export BIGFISH_ALIYUN_NLS_APP_KEY BIGFISH_ALIYUN_NLS_TOKEN
source runtime/worktree.env
./scripts/maven.sh -B -ntp -pl backend -Dtest=AlibabaNlsLiveTest \
  -Dbigfish.nls.live=true "-Dbigfish.nls.audioDirectory=$PWD/runtime/audio" test
unset BIGFISH_ALIYUN_NLS_APP_KEY BIGFISH_ALIYUN_NLS_TOKEN
```

该入口使用固定公开短句“你好，这是大肥鱼语音合成测试。”、默认 xiaoyun、10 秒总期限，**一次 HTTP 尝试、无重试**。可加 `-Dbigfish.nls.voice=zhigui` 验证知柜，仍限制为已有音色白名单。默认整个测试套件跳过它；只有 `-Dbigfish.nls.live=true` 才读取凭据并请求官方服务。一次验收最多手动运行 3 次，不做收费压测。

成功时打印请求次数、耗时、字节数、采样率、时长和 Java Sound 解码结果，不打印凭据或供应商原始正文。默认成功后 release 文件。如需人工试听，在同一条命令增加 `-Dbigfish.nls.keepSample=true`：仅保留当前这一条文件并输出其本机路径，音频不入 Git、不自动播放。试听确认中文人声/内容完整后记录结论；处理保留样本时只删除该次返回的明确文件。缺凭据、权限或 Token 失效时测试失败，不能将跳过/失败算作成功。

同一终端设置的变量不会自动注入已运行的桌面应用或其他终端；在上述终端运行独立验证即可。启动服务仍可使用 `scripts/dev-backend.sh` / `scripts/dev-frontend.sh`，它们加载本工作树环境。主会话需在管理页显式选择阿里云 NLS，启动脚本本身不会自动切换 TTS。

默认本地验证：`./scripts/maven.sh -B -ntp test`。本地 HTTP 服务只绑定 127.0.0.1 随机端口，不访问其他工作树实例。生成的测试 tone 只证明格式、时长与错误处理，不能替代真实合成/试听、浏览器播放链路或长期稳定性验收。
