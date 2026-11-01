# 第六轮主集成：故障状态与恢复提示

日期：2026-11-01（Asia/Shanghai）。主目录 `bigfish-java`，分支 `main`。仅本地合并、修补与验证，没有推送 GitHub。

## 合并范围

基线为 `75dd3bcae38eb8d46f16bbab4c53e87e835d9bd2`。主仓库及前后端工作区均干净；两分支包含该基线，修改符合职责。依次执行 `git merge --no-ff`，均无冲突。

| 来源 | 分支 tip | main 合并提交 |
| --- | --- | --- |
| 后端 `feature/backend-core` | `e1db461`（实现 `0d950db`） | `a2b453f` |
| 前端 `feature/frontend-stage` | `d7435fd`（实现 `f656ba4`） | `d411029` |

未合并采集、发布或其他分支；未修改 Luna、用户数据库、环境变量或许可证。

## 整合后的行为

- 采集/运行/启动终止故障保留首次终止根因，清理和迟到回调不能覆盖；状态与诊断读取协调已发生的采集终态。读取不启动采集或探测供应商。
- 单任务失败与整体会话失败分开；主动取消不增加模型/合成失败计数。失败后的新任务仍可恢复，旧会话回调隔离继续生效。
- 舞台和管理页提供一致的中文故障摘要、四步状态与下一步操作。采集根因优先；播放就绪不能掩盖未恢复任务失败，同环节后续成功后转为折叠的历史记录。
- 错误详情、Token、队列、模拟工具保持默认折叠；未新增公共 DTO 字段、依赖或线上播放消息。

## 主窗口补充

1. 审核复现：停止请求超时后，后续已读取同会话 STOPPED，旧操作错误仍令首屏显示“停止结果尚未确认”。新增回归先失败，再修正 `session-health.ts`：已确认 STOPPED 优先于旧停止请求错误。失败会话的根因优先级与仍运行时的不确定提示保持不变。
2. 更新 OpenAPI 的现有诊断字段说明、运行诊断文档、README 入口、任务边界和 AGENTS。修正诊断文档中已过时的“只有内部 Java 方法、尚无 HTTP 接口”表述。
3. 两窗口提出的 `phase/recoveredAt` 等结构化字段仍为提案，未在此次合并扩展公共契约。有限轨迹被裁剪时，某些通用故障不能可靠定位；不承诺完整历史或全链路恢复。

## 自动验证

| 检查 | 实际结果 |
| --- | --- |
| 新 STOPPED 回归，修补前 | 1 项失败，复现旧停止提示未解除 |
| 前端 `npm test`，修补后 | 101/101 通过，0 失败/跳过 |
| 后端 `./scripts/maven.sh -B -ntp test`，JDK 21 | 232 项，230 通过、2 项真实 NLS/Token 默认跳过，0 失败/错误 |
| 前端 `npm run build` | vue-tsc / Vite 成功，37 模块 |
| `-Pbundle-ui -DskipTests package` | 在上述测试通过后打包成功；JAR 内 index 与本次 dist 一致；未包含开发状态夹具 |
| 独立实例 `node scripts/smoke.mjs` | 健康状态、能力、Token 状态、诊断、打包页面、配置保存/恢复和旧版本 409 通过 |
| OpenAPI JSON、本地引用及差异检查 | 通过 |

首次后端测试因沙箱 JVM attach/本机端口受限而失败；按权限流程在沙箱外重跑同一命令通过，没有修改或跳过失败断言。临时服务器首次启动受 Tomcat 临时目录权限限制，按权限流程重试成功。

本机日志：`/tmp/bigfish-phase6-main-frontend-test.log`、`/tmp/bigfish-phase6-main-frontend-build.log`、`/tmp/bigfish-phase6-main-backend-test-unrestricted.log`、`/tmp/bigfish-phase6-main-package.log`。

## 真实本机 HTTP/WebSocket 联调

使用本次 JAR、端口 `62801`、独立临时 SQLite/音频目录。进程环境仅包含必要系统项与测试参数，显式 `BIGFISH_ALLOW_PAID=false`，不携带云服务凭据。不操作用户 `18080/15173` 或 Luna `8081/8082/12345`。

1. 在明确 MOCK / MOCK / LOCAL_TEST_TONE 会话中，由受控 WebSocket 客户端发送一次 FAILED 播放回执。后端记录 `PLAYER_FAILED`，会话仍 RUNNING，未伪造播放成功。
2. Codex 内置浏览器打开打包舞台：未接入时说明播放端未就绪及最近播放失败；点击“开始互动”接入同一会话后，保持黄色“最近一次声音播放失败，互动仍在运行”，没有因 ready 而抹去错误。
3. 通过页面发送下一条模拟评论，真实后端 MOCK 回复、本地测试音、浏览器音频播放正常。页面显示字幕与播放状态，之后变为“互动已就绪，等待消息”；折叠诊断显示播放此前失败、之后有同环节成功记录。
4. API 核对前后 sessionId 相同，新任务与失败任务 ID 不同，记录 MODEL/SPEECH 成功及同一新任务的 STARTED / COMPLETED；播放成功时间晚于失败时间。
5. 页面点击停止后确认 STOPPED、播放器未 ready；关闭本轮浏览器标签并停止临时 Java PID `66795`。

临时注入脚本最初漏填礼物 `unitValue/valueUnit` 字段，校验器返回 400；补齐测试输入后重新创建测试会话并通过。此为测试脚本错误，未放宽生产事件校验。

本机证据：`/tmp/bigfish-phase6-injected-failure.json`、`/tmp/bigfish-phase6-recovery-evidence.json`；截图 `/tmp/bigfish-phase6-integrated-failure.png`、`/tmp/bigfish-phase6-integrated-recovered.png`。临时脚本、数据与截图均未提交。

## 继续开发与限制

- 两个功能窗口继续前必须在各自干净工作区合入最新 main，保留本次 STOPPED 回归及前几轮音频中断、根因优先和租约隔离修复。最终集成提交 SHA 由主窗口回复提供。
- 用户当前运行的 IDEA 后端没有重启；体验新 Java 代码需要重启后端。Vite 开发页刷新即可；使用 JAR 的场景需启动本次打包产物。
- 本轮只有本机 MOCK / 测试音及受控故障回执，不代表真实抖音接收、云端模型/NLS、RAM 签发或长时间稳定性验收。未单独验证 Safari、Chrome 或 OBS。
- `PAGE_STRUCTURE_CHANGED` 仍属于采集解析待办；真实接收未通过状态保持。没有自动联系其他窗口、上传远端或消耗真实服务额度。
