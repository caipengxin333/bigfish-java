# 第十轮主集成：抖音凭据与礼物链路诊断

日期：2026-11-02（Asia/Shanghai）。主目录 `bigfish-java`，分支 `main`。

## 合并与边界

- 基线 `803532fa1f6a64bad038f5cd18c8ab322c53b03e`；主目录、后端和采集工作树开工时干净，两分支均包含该基线。
- 后端 tip `fb9cd375d04860a2216388adfea48cc45c119078`，实现 `a5bb696`；以 `--no-ff` 合入，合并提交 `01070eb`。
- 采集 tip `39064dbd5c3183b2ad5d29aa14aa9061015aa610`，实现 `e26dfa2`；以 `--no-ff` 合入，合并提交 `b4dab30`。无合并冲突，没有 cherry-pick/reset。
- 后端：默认 guest、显式 external 注入既有 Provider；本地凭据检查固定错误码；礼物业务节点和播放生命周期日志；可选有界异步文件保存。
- 采集：method 在过滤前计数；结构、归一化、去重、队列、ACK、sink 各阶段的脱敏日志与累计统计。保留原签名、解码字段、ACK 和异常隔离。
- 本轮没有修改前端、公共 DTO/API、依赖、数据库结构、优先级或点赞策略；LIKE_NO_SPEECH 保留。没有读取真实凭据、调用云服务、连接真实抖音、改动旧 Luna 或推送 GitHub。

## 集成修补

第一次沙箱内测试因 Mockito JVM attach / 本机网络限制失败；按权限流程在沙箱外重跑。

合并后的第一次完整测试有 1 项失败：CollectorCredentialsTest 原先要求工厂创建时 Provider 无任何调用，采集分支新加的 `credentialMode()` 只读元数据调用触发了此断言。主窗口将其改成**精确只允许一次 credentialMode 调用**，其余 Provider 调用仍不允许；保留 guest 不读外部秘密、工厂懒加载、失败配置不创建 Provider 的保护，没有删除或放宽联网保护。

更新 README、AGENTS、架构、任务边界和抖音接入说明，明确主会话可用 external，修正旧文档“内部诊断只在 probe 输出”的描述；为两层日志提供判断步骤。业务文件不是采集原始日志，SINK_RETURNED 不等于 ACCEPTED，LOCAL_VALID 不等于平台登录有效。

## 主窗口验证

| 验证 | 结果 |
| --- | --- |
| `./scripts/maven.sh -B -ntp test`（JDK 21） | **284 项：282 通过、2 项真实 NLS/Token 默认跳过，0 failure/error**；15:37:31 +08:00 完成，24.319 秒 |
| `cd frontend && npm run build` | SDK 构建、类型检查、Vite 构建通过；前端代码未改，未重复运行此前 112 项前端测试 |
| `./scripts/maven.sh -B -ntp -Pbundle-ui -DskipTests package` | 完整测试通过后跳过重复测试打包，15:37:47 +08:00 完成 |
| JAR 内容 | 含新增诊断/配置类；10 个前端产物与 dist 逐字节一致；不含外部 Core、模型、数据库或礼物 JSONL 日志 |
| `node scripts/smoke.mjs` | 独立 JAR 的健康、管理/舞台页面、供应商状态、配置持久化与版本冲突检查通过；显示名称恢复 |
| 本地 HTTP/WebSocket 礼物联调 | 缺 external 凭据明确 503；MOCK 仍可启动；连击回放、感谢模板、WAV 音频、测试回执和持久化通过 |
| `git diff --check` | 通过 |

日志：`/tmp/bigfish-phase10-main-tests.log`（沙箱失败）、`/tmp/bigfish-phase10-main-tests-unrestricted.log`（集成断言失败）、`/tmp/bigfish-phase10-main-tests-final.log`（最终通过）、`/tmp/bigfish-phase10-main-ui-build.log`、`/tmp/bigfish-phase10-main-package.log`、`/tmp/bigfish-phase10-main-smoke.log`、`/tmp/bigfish-phase10-gift-smoke-final.log`。

## 打包实例联调证据

- 使用独立端口 **18084**、忽略目录 `runtime/phase10-integration`，显式 `BIGFISH_ALLOW_PAID=false`、`BIGFISH_GIFT_TRACE_PERSIST=true`。external 四项使用缺失测试配置，不读取用户 Cookie。
- 应用启动正常，日志 `mode=EXTERNAL validation=CREDENTIALS_MISSING platform=UNVERIFIED`。显式启动 DOUYIN 返回 HTTP 503 / DOUYIN_CREDENTIALS_MISSING，随后 MOCK/MOCK/LOCAL_TEST_TONE 可正常启动。
- 会话 `53f38140-ce42-4504-9b4f-a775ada857f6`：回放 4 条人工采集输入，emitted=3、duplicates=1、rejected=0。两次礼物增量合并成一次“3 个小心心”模板任务，另生成一次模拟评论任务；音频 HTTP 200 且 RIFF WAV 头有效。
- 本地 WebSocket 测试客户端发送有效租约 STARTED/COMPLETED，业务 JSONL 保存 19 条礼物阶段：2 次 RECEIVED、1 次 MERGED_INTO（relatedTaskId 指向最终任务）、SPEECH_COMPLETED 和 1 次 COMPLETED。文件不含礼物名称/昵称/原始内容。这是测试客户端回执，**没有验证浏览器实际发声或真实礼物**。
- 临时脚本首次将行数误设为大于 20，实际完整轨迹 19 条；改为核对接收、合并及完成的精确次数后重跑通过。此修正仅在 `/tmp` 的验收脚本，未修改产品行为。
- 会话已停止，测试 JAR 已通过其原终端退出；未停止或改动用户 18080/15173 和 Luna。运行日志 `/tmp/bigfish-phase10-main-runtime.log`，测试数据仅在上述忽略目录中。

## 启用与下一轮

1. 用户重启主目录后端，才会运行本次代码；现有 JVM 不会自动更新。前端本轮未改。
2. `collector_inbound` 与 `gift_trace` 控制台诊断默认开启；需要礼物业务落盘时添加 `BIGFISH_GIFT_TRACE_PERSIST=true`。主实例默认路径是 `bigfish-java/runtime/diagnostics/gifts/`，自定义 DATA_DIR 时随之变化。4 × 1 MiB 固定槽轮转，拥塞/崩溃可能丢记录；它不包含采集端日志。
3. 默认仍 guest；要做外部会话对照，显式配置 `BIGFISH_DOUYIN_CREDENTIAL_MODE=external` 及同会话 Cookie、UA、数字访客 ID、本地截止时间四项，重启。配置及日志判读见 [抖音说明](../providers/douyin.md)。不得将真实值放进交接、Git 或聊天。
4. 真实小心心漏收根因**未确认、未宣称修复**。采集窗口本轮 60 秒游客观察未识别到礼物 method，不能证明平台不支持或必须登录。新版本需按同房间、同会话、明确时间窗口记录自然礼物，沿 method → 解析 → 派发 → 业务 → 合成/播放定位；不要求反复购买礼物。
5. 两层日志目前只有 sessionId 共同关联，采集帧序号与业务 taskId 没有逐条映射；collector 的 SINK_RETURNED 仅说明 void 回调正常返回。若后续必须逐条对应，再由主窗口统筹公共关联方案。
6. 后端、采集及未来前端窗口继续工作前，先确认自身工作树干净，再 `git merge --no-edit main`；读取本交接，保留本次工厂懒加载断言修补及此前 PCM/WAV、播放、ACK、Live2D 保护。主窗口没有替其他窗口修改工作树或推送远端。
