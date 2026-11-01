# DeepSeek 接入与独立验收

维护更新：2026-11-01。用户已确认 DeepSeek/NLS/模拟事件基本联调成功，集成记录见 [第二轮交接](../handoffs/phase2-integration.md)；本窗口未独立追加真实供应商请求。第三轮保留现有 Spring AI 1.1.8 适配器，新增运行阶段诊断与持续故障恢复本地测试，详见 [运行稳定性](../runtime-stability.md)。默认 MOCK、无 Key 可启动；以下独立入口说明仍适用。

## 凭据与 IDEA

只读取当前服务进程环境中的 `BIGFISH_DEEPSEEK_API_KEY` 和 `BIGFISH_ALLOW_PAID`。必须 Key 非空、开关为 `true`，并显式选择 DEEPSEEK/独立验收，才允许请求。创建工厂本身不联网。固定请求 `https://api.deepseek.com`，禁止重定向，不接受页面提供的任意 endpoint。

IDEA 本机设置步骤：

1. 导入本 worktree 的 Maven 项目，使用 JDK 21。
2. Run → Edit Configurations → 新建 Application，Main class 选 `cn.bigfish.conversation.DeepSeekSmoke`，classpath 选 `bigfish-server`，工作目录选本 backend worktree。
3. 在该运行配置的 Environment variables 编辑器中本机填写 `BIGFISH_DEEPSEEK_API_KEY=<本机密钥>`、`BIGFISH_ALLOW_PAID=true`。独立入口严格使用小写 `true`。占位符不能原样作为 Key 使用。
4. 取消 Store as project file/共享运行配置；不要把密钥写入命令行、Git、runtime/worktree.env、SQLite、聊天或截图。不要启用 HTTP wire/header/body 日志。
5. Program arguments 先填 `--models`。成功后从返回列表选择 ID，再填 `--check --model <返回的模型ID>`；只有显式 `--generate` 才生成文本。

正常服务继续用 `./scripts/dev-backend.sh`，脚本会读取本工作树 runtime/worktree.env，使用隔离的端口、数据库和缓存。独立入口不启动 Spring、会话、播放端或 TTS，也不创建真实采集连接。

## 命令行验收

凭据由终端继承的环境或本机 IDE 运行配置提供，以下命令均不携带密钥。使用同一个工作树的现有启动脚本选择独立 main class：

```bash
# 仅 GET /models，检查鉴权并列出当前账号可见模型
./scripts/dev-backend.sh -B -ntp \
  -Dspring-boot.run.main-class=cn.bigfish.conversation.DeepSeekSmoke \
  -Dspring-boot.run.arguments=--models

# 仅 GET /models，检查所选 ID；替换占位符
./scripts/dev-backend.sh -B -ntp \
  -Dspring-boot.run.main-class=cn.bigfish.conversation.DeepSeekSmoke \
  '-Dspring-boot.run.arguments=--check --model <返回的模型ID>'

# 先检查模型，再最多 3 条固定短中文提示；可用 --requests 1 先验一条
./scripts/dev-backend.sh -B -ntp \
  -Dspring-boot.run.main-class=cn.bigfish.conversation.DeepSeekSmoke \
  '-Dspring-boot.run.arguments=--generate --model <返回的模型ID> --requests 3'
```

每条 `max_tokens=128`、整体期限 20 秒；独立入口关闭重试，最多 3 次实际生成 POST，任一失败即停止。每次运行先发 1 次 GET，GET 失败/模型不存在时不会 POST。自动化测试不会运行真实入口。不要在脚本外循环重试或反复点击生成。

输出只含时间、模型 ID、请求序号、结果类别、耗时、回复字符数；不打印原始响应、完整对话或凭据。`authentication=PASSED` 仅证明本次目录请求鉴权成功，`model=PASSED` 仅证明 ID 存在；`generation=PASSED` 只在完整生成成功后输出。GET 检测的 generation 始终为 `NOT_CHECKED`。

进程退出码：0 成功；2 参数错误；3 缺少开关/Key（零请求）；4 目录/模型检测失败；5 生成失败；6 验收流程异常。通过 Maven 运行时，非零子进程退出会显示 BUILD FAILURE，这是拒绝/失败的正常表现，不代表服务无法无 Key 启动。

## 模型发现与内部调用

从官方 `GET /models` 当前返回值发现模型，不将文档示例或旧配置默认值当作账号可用模型列表。目录最多 100 个唯一 ID，每个 ID 最多 100 字符；只接受 `A-Za-z0-9._:/-`。未知元数据忽略，目录格式不合法则失败。无隐式启动探测、定时轮询或目录缓存；保存/切换模型后可显式重验，检测结果应关联配置版本和检测时间。

兼容原有工厂与 ConversationPort：

```java
DeepSeekConversation.create(boolean allowPaid, String key);
DeepSeekConversation.create(boolean allowPaid, String key, DeepSeekConversation.Policy policy);
DeepSeekConnectionProbe.create(boolean allowPaid, String key);
```

最小集成示意（在管理层已有线程/异步流程中调用，勿阻塞事件采集线程）：

```java
boolean enabled = "true".equals(System.getenv("BIGFISH_ALLOW_PAID"));
String key = System.getenv("BIGFISH_DEEPSEEK_API_KEY");
// 缺少条件时不创建真实适配器；保持 MOCK，并展示 PROVIDER_DISABLED。
try (var probe = DeepSeekConnectionProbe.create(enabled, key)) {
    var check = probe.check(requestId, snapshot.settings().model(), Duration.ofSeconds(10))
        .toCompletableFuture().get(11, TimeUnit.SECONDS);
    // check.authentication(), selectedModel(), generation(), availableModels(), diagnostic()
    // 不把 check 的 SUCCESS 当作真实生成成功。
}
try (var conversation = DeepSeekConversation.create(enabled, key)) {
    conversation.reply(taskId, commentEvent, snapshot, Duration.ofSeconds(20), completeText -> {
        // 入合成队列前由引擎再次检查 sessionId、taskId、播放器代次和任务 TTL。
    }).toCompletableFuture().get(21, TimeUnit.SECONDS);
    // conversation.diagnostic(taskId) 含 code / elapsedMs / attempts / httpStatus。
}
```

上例假设调用方已有 `requestId/taskId/snapshot/commentEvent`；业务中适配器可由会话持有并复用，不必每条重建工厂。目录探测和生成独立：普通 `reply` 不自动先发 GET，使用事件入队时的不可变快照。新任务使用新模型、人设和 maxOutputTokens，旧任务保留旧值。

`reply` 返回 CompletionStage，只有完整非空回答才调用 sink 一次；异步错误为 `DeepSeekFailure`，从 CompletionException/ExecutionException 中解包后取 `diagnostic()`，不要记录整个原始上游异常。`cancel(taskId)`、返回 future 的 `cancel(false)`、`close()` 都取消未完成请求并关闭底层 HTTP。取消的 future 抛 CancellationException；可从 `diagnostic(taskId)` 读 `CANCELLED`。近期生成诊断仅内存保留，最多 128 条/5 分钟。参数错误、BUSY、CLOSED 在返回的失败 future 中，可能没有近期记录且 attempts=0。

`probe.check(requestId, selectedModel, deadline)`：selectedModel=null 只列目录；网络/供应商失败返回 Check 中的诊断，参数/并发/已关闭错误返回失败 future。`probe.cancel(requestId)`、future.cancel、probe.close 会关闭请求；取消为 CancellationException。探测无近期诊断缓存，由管理层记录取消耗时。

## 有界执行与输出

- 生成最多同时 4 个请求、探测最多 2 个；满载直接 BUSY，无额外排队。每次 HTTP 尝试独占 JDK HttpClient，成功/错误/超时/取消都调用 shutdownNow。
- 连接超时 3 秒。生成默认相邻有效 SSE chunk 等待上限 5 秒，keep-alive 注释不算 chunk；整体期限由调用方传入，必须大于 0 且不超过 60 秒。现有引擎通常为 20 秒并受事件 TTL 限制。持续 token 也不能延长整体期限。
- 探测默认 5 秒内读完 JSON，整体期限必须不超过 15 秒（独立入口 10 秒）。整体期限包括连接和读取；生成还包括重试及退避。
- 单 SSE 事件/聚合 JSON codec 上限 65,536 字节；单次 HTTP 累计响应体上限 262,144 字节。生成最多 4,096 个 chunk、2,000 个 Java 字符；输入评论最多 2,000 字符、人设 8,000 字符，maxOutputTokens 1..2,048。
- 请求带 `reasoning_effort=none`；只收集 `delta.content`，绝不拼接 `reasoning_content`。只接受单候选的普通文本，不执行工具调用。不从回复中猜测或删除文本形式的思考标签。
- Spring AI 负责 SSE/[DONE] 解析；适配器要求正常结束且已收到 `finish_reason=stop`。EOF 前没有 stop、损坏 JSON、超长输出、length 截断均不发布半截回复。Spring AI 隐藏 [DONE]，故 stop 后的正常 EOF 也视为完整结束。
- 成功通过原子终态竞争后提交 sink；取消先赢则不会调用 sink。成功已提交后，cancel 不等待或撤回调用方回调。调用方必须保留会话/任务/播放器代次防线，sink 应快速入队。适配器不在外部回调或 future completion 时持有请求锁，避免引擎停止与完成互相等待。
- 迟到错误使用请求上下文中的脱敏处理，不安装全局 Reactor hook；该上下文键核对过当前固定 Reactor 3.7.16，升级依赖需重验取消日志。

## 错误码与重试

Diagnostic 的 `httpStatus=0` 表示没有保留可用 HTTP 状态；`elapsedMs` 为本次操作耗时；`attempts` 为尝试次数。没有原始 message、URI、body 或 cause。错误分类如下：

| code | 含义 / 处理 |
| --- | --- |
| PROVIDER_DISABLED | Key/开关未就绪，不联网 |
| AUTHENTICATION_FAILED | HTTP 401；检查本机凭据 |
| INSUFFICIENT_BALANCE / PERMISSION_DENIED | HTTP 402 / 403；不自动充值或切换服务 |
| MODEL_NOT_FOUND | HTTP 404，或 400/422 的已知模型错误机器码，或目录中不存在所选 ID |
| INVALID_REQUEST | 参数不合法或其他 HTTP 400/422；不靠解析供应商自由文本猜原因 |
| RATE_LIMITED / SERVER_ERROR | HTTP 429 / 5xx；默认最多重试 1 次 |
| CONNECT_TIMEOUT / READ_TIMEOUT / OVERALL_TIMEOUT | 连接 / chunk 或 JSON 读取 / 所有尝试的总期限 |
| NETWORK_ERROR | 其他 I/O 失败；不自动重发可能已开始生成的请求 |
| CANCELLED | 显式取消/关闭；future 为取消状态 |
| INVALID_RESPONSE / INCOMPLETE_RESPONSE | 协议、类型、格式、大小异常 / 未完整结束的流 |
| OUTPUT_LIMIT / OUTPUT_TRUNCATED / CONTENT_FILTERED | 文本超上限 / finish_reason=length / content_filter；不播半截内容 |
| BUSY / CLOSED / CALLBACK_FAILED | 并发已满 / 适配器已关闭 / 调用方 sink 异常 |
| SUCCESS | 本次操作成功；是否真实生成仍需看操作种类 |

仅明确的 429/5xx 响应可重试，默认 250ms 后再试一次；所有尝试受同一个整体期限约束。鉴权、余额、权限、未知模型、网络/读取超时、损坏或半截流不重试。完整成功只发布一次。目录检查不重试。独立真实验收配置 maxAttempts=1。去除旧全局 5 秒冷却，失败结束后下一条请求可立即恢复；不会自动回退 MOCK 或更换付费模型。

## 已有会话 API 与集成边界

现有 `POST /api/v1/sessions` 已支持 `{ "mode": "MOCK", "conversationMode": "DEEPSEEK" }`，默认测试音仍是 `LOCAL_TEST_TONE`。使用此入口前先独立检查模型并通过现有配置 CAS 保存正确 ID；不发送 Key 字段。

```bash
curl --fail-with-body http://127.0.0.1:18081/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"mode":"MOCK","conversationMode":"DEEPSEEK"}'
```

会话启动并不代表生成通过。注入 mock COMMENT 前需要有效的 WebSocket PLAYER_READY；结束后调用现有 stop。礼物仍走模板。当前本地测试音不包含人声，不能据此声称真实 TTS 通过。独立验收不依赖前端/播放端，优先用于短请求验证。

第二轮集成已接入 `/api/v1/providers/deepseek/check` 和 RuntimeView 的 lastError/lastErrorAt；第三轮新增的阶段轨迹仅由 `RuntimeEngine.diagnostics()` 内部读取，新的公共字段提案见 [稳定性交接](../handoffs/phase3-stability.md)。本分支未新增公共 API、DTO 或依赖；读取诊断不发起供应商请求。

## 协议核对来源

查阅于 2026-10-30：[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[模型列表](https://api-docs.deepseek.com/api/list-models/)、[错误码](https://api-docs.deepseek.com/quick_start/error_codes/)、[keep-alive 行为](https://api-docs.deepseek.com/quick_start/rate_limit/)。同时核对本地固定 Spring AI/OpenAiApi 和 Reactor 源码。文档核对与本地替身测试均不等于账号真实联网验证。
