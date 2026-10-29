# 第二轮阿里云 NLS 语音交接

Java TTS 适配器与本地验证已完成并提交；真实云端合成/人声试听、主会话接线和浏览器联调尚未验证。

## 分支、基线与提交

- 工作树：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/frontend`
- 分支：`feature/frontend-stage`；沿用隔离端口 18082 / 15175。
- 开工时工作区干净，保留第一轮提交；按本轮授权 `git merge --no-edit main`，快进至基线 `a89c304ce16e8299e734938ab83f9780eba58fcf`（包含集成基线 `49a7865`），无冲突。
- `5a53dda31fb1389e82cb4395502d85c977dab36f`：Token 状态/单飞刷新框架、PCM WAV 严格校验及测试。
- `26e92ba19e7253abbe7a2ab4b1634ac309b8d1b5`：官方 REST 合成、请求/文件生命周期、故障与显式真实验证入口、供应商文档。
- 本交接文件另作收尾文档提交，其 SHA 在当前任务最终回复报告。仅本地提交；未合并其他功能分支、未推送、未建 PR、未更新 main。

## 实现与实际文件

新增生产文件均在 `backend/src/main/java/cn/bigfish/speech/`：

| 文件 | 实现 |
|---|---|
| AlibabaNlsSpeech.java | 实现现有 SpeechPort；固定上海官方 HTTPS POST、UTF-8 JSON、后端环境凭据、显式工厂/close、音色和 300 字符上限校验、总 deadline/取消、2 并发无队列、最多 2 尝试与冷却、安全诊断 |
| BoundedAudioBody.java | 有界响应体订阅，连续无数据 2 秒中止，取消即关闭订阅；流式数据不能延长总期限 |
| OwnedAudioFiles.java | UUID/CREATE_NEW、不覆盖旧文件、每目录独占锁、最多 8 文件、按任务拥有并显式 release，失败/取消只清理该任务的明确路径 |
| PcmWav.java | RIFF/chunk/PCM16kHz/16bit/单声道、完整长度、非空/非全零、2,000,000 字节/60 秒上限；按样本计算 duration |
| NlsTokenProvider.java | 后端专用 Token + 可选真实到期时间，toString 脱敏，状态枚举 |
| StaticNlsTokenProvider.java | 环境 Token 初始 UNVERIFIED；验证成功后 ACCEPTED；供应商拒绝后 REJECTED；已知过期本地拒绝 |
| RefreshingNlsTokenProvider.java | 注入签发回调的单飞刷新基础：提前 30 秒、3 秒期限、16 等待者、200ms 失败冷却、取消立即移除等待者、旧 Token 拒绝不污染新 Token |
| SpeechFailure.java | 稳定 NLS 错误码，异常不包含原始响应、文本、Token、AppKey 或 transport cause |

新增测试均在 `backend/src/test/java/cn/bigfish/speech/`：`AlibabaNlsSpeechTest`、`AlibabaNlsLiveTest`、`NlsTokenProviderTest`、`PcmWavTest`、`SpeechFixtures`。夹具是代码生成的本地 tone，没有真实凭据/供应商音频文件。

文档：本文件及 [阿里云使用说明](../providers/aliyun-nls.md)。

`LocalTestSpeech` 保持原样；Vue、contract、POM、CoreConfiguration、RuntimeEngine、AudioAssets、OpenAPI、播放协议均未修改。没有新增依赖，没有复制旧项目配置、模型或脚本。

## 验证命令与结果

执行日期 2026-10-30；Java 21。最终生产代码对应上述两笔提交。

| 命令 | 结果 |
|---|---|
| `pwd` / `git branch --show-current` / `git status --short` | 指定工作树及 feature/frontend-stage，开工无未提交改动 |
| `git merge --no-edit main` | 快进至 a89c304，无冲突，保留原提交 |
| `./scripts/maven.sh -B -ntp -pl backend -DskipTests compile` | BUILD SUCCESS |
| `./scripts/maven.sh -B -ntp -pl backend '-Dtest=cn.bigfish.speech.*Test' test` | 首轮 22 项通过；之后新增取消等待者回归与默认跳过的 live 测试 |
| `./scripts/maven.sh -B -ntp test` | **最终 91 项，90 通过，0 failures，0 errors，1 skipped；BUILD SUCCESS** |
| `git diff --cached --check` | 两笔实现提交均通过；收尾交接再次检查 |

环境说明：首次专项测试被沙箱 Maven 仓库 DNS 限制阻止；完整测试在沙箱内曾因 localhost bind / Mockito self-attach 被禁止而报错。获准在沙箱外原命令重跑后通过，没有更改 POM、关闭测试或削弱断言。最终完整结果包含原基线 67 项、23 项新本地测试及 1 项默认跳过的真实验证。

本地测试只绑定本机随机端口，不连接主工作树或其他窗口服务。未启动常驻 dev 服务；若后续启动，仍用 scripts/dev-backend.sh / scripts/dev-frontend.sh 读取本工作树 runtime/worktree.env。第二轮不修改 Vue，因此没有重新运行前端构建。

已验证的行为包括：

- POST 正文保留中文及 `+ & %`，Token 在请求头、URL 无查询凭据；默认音色/采样率/格式正确；Java Sound 解码帧数与 duration 一致。
- 401、403、JSON Token 拒绝不重试；后续静态 Token 请求本地失败；重建有效替身 Token 后下一次恢复。
- 429 一次重试后成功；503 最多两次；冷却生效；Retry-After 不延长总期限；302 不跟随。
- JSON 错误、无效/截断/空/全零/压缩/错误采样率/过长/过大 WAV 拒绝；错误音色、超长文本、非法输入在发送前拒绝。
- 响应头迟到、响应体停顿、持续分块均受 deadline/读取期限约束；主动 cancel、future cancel、close 后没有迟到可播文件；下一次请求可恢复。
- 并发/文件数量有限；目录独占；外部和历史文件保留；成功路径不会提前删除；release 幂等。
- 刷新单飞、到期前更新、等待者独立超时/取消、供应商错误脱敏、刷新超时取消、冷却后恢复、关闭完成等待者。

## 真实验证与限制

- 仅检查本轮指定变量是否存在：`BIGFISH_ALIYUN_NLS_APP_KEY` 和 `BIGFISH_ALIYUN_NLS_TOKEN` 均缺失；没有打印值，也没有读取旧配置。**实际阿里云请求 0 次**。
- 未取得真实人声 WAV、云端权限/音色可用性、云端延迟或实际费用证据。格式测试及本地 tone 不代表人声合成成功；完整 Java 测试里的现有 MOCK WebSocket 闭环也不代表本适配器已接入。
- 已交付静态 Token 请求实现与状态识别；真实静态 Token 验收待本机凭据就绪。`RefreshingNlsTokenProvider` 只完成刷新调度框架，**没有实现云端 CreateToken 签发/自动续期**。
- 官方 300 字符采用保守 UTF-16 计数；不分段、不截断，不支持 SSML。白名单是官方音色的有限子集；不保证当前账号已开通。
- 最长 60 秒是适配器安全上限，超过现有引擎 30 秒完成等待；主窗口必须按下述提案统一短句/时长政策，不能直接替换 Bean 就宣称播放链路完成。
- 成功样本必须释放。目录历史孤儿只计额度、不自动删除；崩溃后最多占满额度，需按明确路径逐个处理。文件系统需支持文件锁及 fileKey。
- 长期运行/20 分钟沉默场景、真实 Token 轮换、跨模块 sessionId/舞台租约/停止后闭嘴最终联调待主窗口验收。本轮不自动开展收费长测。

[使用说明](../providers/aliyun-nls.md#本机凭据配置与独立验证) 提供 zsh 隐藏输入凭据步骤，以及显式命令：`AlibabaNlsLiveTest` 只有 `-Dbigfish.nls.live=true` 才发官方请求，每次只合成一条固定公开短句、一次请求、无重试、10 秒期限。正常默认跳过；缺凭据时显式执行会报错。一次验收最多手动运行 3 次。可显式 keepSample 保留本机音频供试听，默认读取验证后释放，音频不入 Git。

## 给主集成窗口的提案（本任务未修改公共文件）

1. **模式/凭据/音色**：按 phase2-plan 增加独立 speechMode 和服务器显式真实调用许可；选择 NLS 且后端 AppKey/Token 就绪才建真实实例。默认无 Key 可启动 MOCK。UI 展示选定模式、UNVERIFIED/REJECTED 等就绪状态；ACCEPTED 仅代表最近一次成功。不能把供应商凭据写入配置 DTO/SQLite 业务配置/前端响应。NLS 默认建议 xiaoyun；拒绝现有 Edge 标识 zh-CN-XiaoyiNeural；可选 xiaogang、zhixiaomei、zhigui、aixia。
2. **文件生命周期**：建议主窗口在 `SpeechPort` 增加 `default boolean release(AudioAsset asset) { return false; }`，与本适配器已有签名兼容、LocalTestSpeech 无须删除共享测试音。RuntimeEngine 对合成结果在 `AudioAssets.load` 的 finally 调用 release；所有 get 超时/停止/旧 session 分支调用 cancel。也可由集成使用等效释放回调，但不能遗忘。未自行改 contract。
3. **创建/销毁**：CoreConfiguration 按选定模式创建并持有单实例，目录与 AudioAssets 完全一致；应用关闭调用 close。不能每条消息新建适配器，也不能让多个实例抢同一目录。业务层保持依赖 SpeechPort，避免把供应商配置散落到管理页。
4. **时限**：首轮真实联调建议短回复约 80 字符以内、播放时长不超过 25 秒；合成上限暂以 `min(10 秒, 任务剩余 TTL)` 作为待实测起点，不能无界等待。STARTED 保持 5 秒，COMPLETED 按实际 duration 加有限余量（如 5 秒），并受剩余 TTL 和 30 秒上限限制；过长或 TTL 不足的任务明确失败。若后续要播放 25 秒以上，统一修改引擎/播放协调/前端限制后再验收。当前适配器严格尊重传入的 5 秒，未自行改任何共享期限。
5. **安全错误展示**：引擎可解包 SpeechFailure，只传播稳定 code 和耗时；不要打印原始 HTTP 请求/Token/响应正文。区分配置错误、限流、过期、超时、内容校验失败，不能失败时自动改播测试音。
6. **自动续期依赖**：实际 CreateToken 回调需要可信后端 RAM AccessKey ID/Secret、最小 NLS 权限、到期时间、安全保存和轮换方案。目前无新增 SDK/POM 需求；若选 SDK，另定固定版本/Java 21 兼容性/许可证，由主窗口统一引入。不能将本地刷新框架说成已接云端自动续期。

## 最小调用示例

以下供集成窗口落地，示例本身不改变公共接口或默认模式：

```java
// enabled = 明确选择 NLS 且允许真实调用；audioDirectory = 本实例配置 dataDir/audio。
AlibabaNlsSpeech speech = AlibabaNlsSpeech.create(enabled, audioDirectory, objectMapper);
SpeechPort.AudioAsset asset = null;
try {
    asset = speech.synthesize(taskId, text, "xiaoyun", Duration.ofSeconds(5))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    AudioAssets.Content content = audioAssets.load(asset);
    // 按现有 sessionId、TTL、队列和租约规则登记 content。
} catch (Exception error) {
    speech.cancel(taskId);
    throw error;
} finally {
    if (asset != null) speech.release(asset);
}
// 停止/旧会话调用 speech.cancel(taskId)，应用关闭调用 speech.close()。
```

如需已知到期时间或刷新回调，另有后端重载：
`AlibabaNlsSpeech.create(boolean enabled, Path audioDirectory, ObjectMapper mapper, String appKey, NlsTokenProvider tokens)`。
此重载仍固定官方 HTTPS；适配器拥有并关闭注入的 TokenProvider。生产接口不暴露 endpoint。完整供应商参数、状态、限制和本机验证命令见 [aliyun-nls.md](../providers/aliyun-nls.md)。
