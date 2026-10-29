# 阿里云 NLS 短文本语音（Java）

本轮交付是独立 Java `SpeechPort` 适配器，未替换主会话 Bean。默认应用仍使用 `LocalTestSpeech`；不能根据此类存在就把页面标成“已合成”或“真实语音已就绪”。无新增依赖，不使用 Edge-TTS，不复制旧项目模型、配置或密钥。

## 官方依据与固定选择

核对日期：2026-10-30。

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

音色必须明确选择且属于 `AlibabaNlsSpeech.VOICES`；`zh-CN-XiaoyiNeural` 是 Edge 标识，直接传入返回 `NLS_UNSUPPORTED_VOICE`，不会猜测映射或偷偷降级。固定参数为 WAV / 16000 Hz / PCM 16-bit 单声道，volume=50、speech_rate=0、pitch_rate=0。扩展公开设置交主窗口提案。

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

只有 HTTP 429/5xx、NLS JSON 40000005/50000000/50000001 可重试，总共最多 2 次；最少冷却 1 秒，遵守整数 Retry-After，预算不足不重试。无法识别的 Retry-After 按 120 秒冷却处理并停止本次重试。401/403、NLS 40000001、参数错误不重试；网络/读取/格式失败不自动重试。不存在失败自动回退成测试音或另一家供应商。

所有对外异常仅有 `SpeechFailure.Code` / `NLS_<CODE>`，没有原始 cause、响应正文、文本或凭据。状态可通过 `diagnostics()` 获取安全计数、在途数、未释放文件数、Token 状态、最后错误码；禁止把 TokenProvider、HTTP 调试头或请求正文写入日志。

| 错误类别 | 例子 | 集成处理建议 |
|---|---|---|
| 配置/凭据 | MISSING_CREDENTIALS、TOKEN_EXPIRED、TOKEN_REJECTED、UNSUPPORTED_VOICE | 明确提示并等待纠正，不能循环付费重试 |
| 资源/限流 | BUSY、COOLDOWN、RATE_LIMITED、FILE_LIMIT | 有限丢弃/等待下一个事件，检查文件释放 |
| 截止/取消 | DEADLINE_EXCEEDED、CONNECT_TIMEOUT、READ_TIMEOUT、CANCELLED、CLOSED | 丢弃任务，立即停止旧会话播放 |
| 服务/内容 | PROVIDER_UNAVAILABLE、INVALID_RESPONSE、INVALID_WAV、AUDIO_TOO_LARGE、AUDIO_TOO_LONG、AUDIO_SILENT | 记录脱敏错误码，不能发布播放任务 |

静态环境 Token 的初始状态是 **UNVERIFIED**，没有伪造的有效期；只有收到并验证成功 WAV 后才是 ACCEPTED，表示最近一次接受，不保证未来仍有效。HTTP 401/403 或 NLS Token 拒绝后标 REJECTED，后续请求不再发送；替换环境变量后重建适配器/重启。若后端知道确切签发到期时间，可用 `StaticNlsTokenProvider(token, expiresAt)` 提前本地拒绝过期。

`RefreshingNlsTokenProvider` 是**经过本地测试的刷新框架**：接收 `Function<Duration, CompletionStage<Token>>` 签发回调，Token 必须包含到期时间；提前 30 秒刷新，单飞、最多 16 等待者、默认 3 秒刷新期限和失败 200ms 冷却。等待者有自己的期限，单个取消不取消其他等待者。旧 Token 的迟到拒绝不能废掉新 Token。

**云端 CreateToken 回调未实现、未验证。** 若后续自动续期，需要独立后端 RAM AccessKey ID/Secret、最小 NLS 权限、安全保存/轮换决策，以及返回 ExpireTime 的实际签发调用。当前未引入 SDK，无依赖变更提案；不能照搬官方旧 Java SDK/JAXB 示例版本。集成选择 SDK 时需另行确定固定版本、Java 21 兼容性与许可证，再由主窗口修改 POM。

## 本机凭据配置与独立验证

当前开发进程没有 `BIGFISH_ALIYUN_NLS_APP_KEY` / `BIGFISH_ALIYUN_NLS_TOKEN`；未执行云端调用、未取得真实人声证据。请在阿里云控制台确认已开通 NLS TTS、上海项目 AppKey 及未过期的 NLS Token。不要把值发到聊天、写入仓库或粘贴进带字面量的命令历史。

在自己的 **zsh 终端**中运行，输入过程不回显：

```zsh
cd '/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/frontend'
read -rs 'BIGFISH_ALIYUN_NLS_APP_KEY?NLS AppKey（隐藏输入）: '
read -rs 'BIGFISH_ALIYUN_NLS_TOKEN?NLS Token（隐藏输入）: '
export BIGFISH_ALIYUN_NLS_APP_KEY BIGFISH_ALIYUN_NLS_TOKEN
source runtime/worktree.env
./scripts/maven.sh -B -ntp -pl backend -Dtest=AlibabaNlsLiveTest \
  -Dbigfish.nls.live=true "-Dbigfish.nls.audioDirectory=$BIGFISH_DATA_DIR/audio" test
unset BIGFISH_ALIYUN_NLS_APP_KEY BIGFISH_ALIYUN_NLS_TOKEN
```

该入口使用固定公开短句“你好，这是大肥鱼语音合成测试。”、xiaoyun、10 秒总期限，**一次 HTTP 尝试、无重试**。默认整个测试套件跳过它；只有 `-Dbigfish.nls.live=true` 才读取凭据并请求官方服务。一次验收最多手动运行 3 次，不做收费压测。

成功时打印请求次数、耗时、字节数、采样率、时长和 Java Sound 解码结果，不打印凭据或供应商原始正文。默认成功后 release 文件。如需人工试听，在同一条命令增加 `-Dbigfish.nls.keepSample=true`：仅保留当前这一条文件并输出其本机路径，音频不入 Git、不自动播放。试听确认中文人声/内容完整后记录结论；处理保留样本时只删除该次返回的明确文件。缺凭据、权限或 Token 失效时测试失败，不能将跳过/失败算作成功。

同一终端设置的变量不会自动注入已运行的桌面应用或其他终端；在上述终端运行独立验证即可。未来启动服务仍使用 `scripts/dev-backend.sh` / `scripts/dev-frontend.sh`，它们加载本工作树环境。当前未接主会话，启动脚本本身不会自动切换 TTS。

默认本地验证：`./scripts/maven.sh -B -ntp test`。本地 HTTP 服务只绑定 127.0.0.1 随机端口，不访问其他工作树实例。生成的测试 tone 只证明格式、时长与错误处理，不能替代真实合成/试听、浏览器播放链路或长期稳定性验收。
