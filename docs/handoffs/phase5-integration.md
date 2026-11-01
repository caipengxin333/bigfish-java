# 第四/五轮主集成：抖音会话接线与一键互动

日期：2026-11-01（Asia/Shanghai）。主目录 `bigfish-java`，分支 `main`。本轮合并本地分支并验证，没有推送 GitHub。

## 合并范围

基线为 `03e26b619715e68c9de00294d753eaa481dad31c`。合并前 main、backend、frontend 工作区均干净；两个分支包含同一 main 基线，职责范围符合任务，没有修改对方实现目录。

| 来源 | 审核并合入的分支 tip | main 合并提交 |
| --- | --- | --- |
| `feature/backend-core` | `c3ffe6e`，包含第四轮 `57adee7/94c0fef` 与第五轮 `009d6cc/3728d59` | `c81d6bd` |
| `feature/frontend-stage` | `8ebe61e`，包含第四轮 `9cc10a3/7dcb013` 与第五轮 `64c7f04` | `2b030f5` |

均使用 `git merge --no-ff`，无冲突。未合并采集器之外的新任务、GitHub 发布分支或许可证分支；未改变项目许可证。

## 合并后的行为

- 管理页保存业务配置、选择本浏览器启动选项、打开舞台并查看状态。舞台一次点击“开始互动”协调音频解锁、创建/接入会话和播放租约；已有会话不重复创建，声音受阻时有独立补救。
- 三项启动选项仅存同源浏览器白名单，业务配置仍经 SQLite/CAS 保存。加载、保存和查询不连接抖音、不创建会话。
- 显式 DOUYIN 已接入主会话，使用原生 signer 与游客工厂；内部房间绑定、独立回复/语音模式、MOCK 注入隔离、采集终态与资源退出保护保留。
- 后端修复旧传输关闭前独占保护、复用 ID 的旧回调隔离、关闭异常与取消异常的清理、部分启动失败的资源释放；REST/线上播放消息格式未新增。
- 普通标签隐藏不主动断开；冻结、关闭、断线或租约失效后清理本页播放。“恢复播放”只恢复同一仍在运行的会话，不重启采集或补播旧任务。

## 主窗口修补

1. 同步 OpenAPI 中 RuntimeView/collector 的 DOUYIN 枚举、启动错误、网页号/内部号、能力开放/接收证据、播放租约与计数含义。没有新增 DTO 字段，只修正 RuntimeView 注释。
2. 更新 README、前端说明、架构、播放协议、抖音说明、AGENTS 和职责文档，删除已失效的“主会话禁用 DOUYIN”“hidden 主动断开”“三步启动”描述。历史 handoff 原样保留。
3. 修复恢复可见时音频已暂停但服务端仍为 ready 的情况：协议没有 NOT_READY，`StageController.checkActivity()` 先结束当前任务再关闭传输，服务端随之撤销播放资格，避免继续生成无法播放的任务。无在播任务和有在播任务各加一项回归，验证迟到结束回调/心跳不会复活播放端。
4. 冒烟脚本改为验证“DOUYIN 能力开放且尚未验证”，仍不调用真实抖音，仅操作临时实例的显示名称并恢复。
5. 浏览器联调发现第二端被拒/仅断开后仍残留“正在连接”通用文案，修正为按真实连接状态展示，避免与租约拒绝/断开提示矛盾；随后重跑前端测试、构建与打包。

**前端窗口继续前必须同步 main，保留第 3 项修复及测试；后端/前端都不能为兼容旧基线恢复禁用能力或旧播放流程。**

## 主分支验证

| 检查 | 结果 |
| --- | --- |
| 后端 `./scripts/maven.sh test`，显式 JDK 21 | 221 项，219 通过，2 项真实 NLS/Token 测试默认跳过，0 失败/错误 |
| 前端合并后初测 | 69/69 通过 |
| 主窗口音频中断补丁后 `npm test` | 71/71 通过，0 失败/跳过 |
| `npm run build` | TypeScript/Vite 通过，36 模块 |
| `-Pbundle-ui -DskipTests package`（测试通过后） | 成功；JAR 的 index 引用最新构建入口，包含新 StagePage 及 SessionCollectors |
| `node scripts/smoke.mjs`，独立实例 | health、能力/Token、有限诊断、打包 admin/stage、配置保存/恢复与旧版本 409 全部通过 |
| OpenAPI JSON 与所有本地 `$ref`、差异检查 | 通过 |

首次后端测试受沙箱 JVM attach/本机端口限制失败；未改测试断言，获准运行同一命令后通过。本机服务及冒烟访问也在出现 EPERM 后按权限流程重试。此类环境失败不算功能测试通过。

日志位于本机 `/tmp/bigfish-phase5-main-test-unrestricted.log`、`/tmp/bigfish-phase5-main-frontend-test.log`、`/tmp/bigfish-phase5-main-frontend-build.log`、`/tmp/bigfish-phase5-main-package.log`，未提交。

## 打包版本浏览器联调

使用独立 JAR 实例，端口 `62791`，临时数据库/音频目录；启动环境只保留必要系统变量，显式 `BIGFISH_ALLOW_PAID=false`，不继承任何云凭据。主项目 `18080/15173` 与用户配置未操作。

Codex 内置浏览器实际 HTTP/WebSocket 验证：

1. 管理页无普通启动会话入口；默认模拟采集/MOCK 回复/本地测试音，抖音选项已开放但明确未验证。
2. 舞台一次点击开始，取得同一后端会话、有效播放租约与声音 ready，不需三次点击。
3. 人工采集回放累计礼物 1→3→3：接收 4、派发 3、去重 1、拒绝 0；评论和合并礼物两个任务各产生真实 STARTED/COMPLETED 回执。
4. 第二舞台接入已有会话时收到 PLAYER_ALREADY_ACTIVE、保持静音；没有新建会话。原端跨多个心跳窗口继续持有租约。
5. 仅断开原端后会话保持 RUNNING；点击恢复仍为原 sessionId，取得新租约；新模拟礼物再次产生 STARTED/COMPLETED。
6. 停止互动后页面显示后端已确认 STOPPED，本页静音且租约未就绪。随后关闭所有本轮标签，向本轮 JAR PID 60316 发送 TERM，释放临时服务。

验收截图仅在本机：`/tmp/bigfish-phase5-integrated-stage.png`（就绪）、`/tmp/bigfish-phase5-integrated-stopped.png`（停止）。本轮测试是本机 MOCK + 测试音，不是自然人声或真实抖音接收证据。

## 待办与实际限制

- 第四轮真实 guest resolve 返回 PAGE_STRUCTURE_CHANGED：2 次 HTTP、0 次 WSS、真实评论/礼物 0。此问题仍归采集器解析任务；本轮没有真实抖音、DeepSeek、NLS/CreateToken 调用。
- 本轮未单独在 Safari、Chrome、OBS 验收，未做长期后台或系统休眠测试。浏览器冻结无法承诺连续发声；音频暂停恢复通过可控回归覆盖。
- 启动 POST 的结果若长期不明确，前端只查询、不盲目重发。现有协议无幂等操作标识，严格的取消启动/结果追踪仍属后续契约提案。
- 网页配置与内部房间号应分别理解；本轮没有新增 requestedRoomId、逐类采集统计或 releasing DTO 字段。

最终集成修补提交 SHA 由主窗口回复提供，避免本文自引用。发布 GitHub 仍由用户指定发布窗口负责。
