# 第四轮前端：消息来源选择与采集状态

日期：2026-11-01（Asia/Shanghai）。工作树 `bigfish-worktrees/frontend`，分支 `feature/frontend-stage`。

## 基线、范围与提交

- 开工目录/分支检查正确，工作区干净，起点 `f0b88eb1042ea7ff707b92fc5de9834f427fa6ea`。
- 本轮用户明确重新分配 Vue 职责，优先于第三轮语音分工。对“不合并 main”与“安全同步”询问后，用户明确允许**仅快进同步**。执行 `git merge --ff-only main` 到 `03e26b619715e68c9de00294d753eaa481dad31c`，未产生合并提交、未更新 main。
- 实现提交：**`9cc10a331fe26a20d62a053473e0efc481f17256`**（`feat(frontend): select collection source and show live collector state`）。本交接的文档提交 SHA 见最终回复，不在文件中自引用。
- 代码只改 `frontend/` 下 14 个文件；文档仅新增本文件。未改 Java、采集器、OpenAPI、公共协作文档、POM、数据库、根构建配置或 npm 依赖；未修改 `frontend/src/stage/` 播放算法/协议。没有推送、PR、联系其他窗口、reset、stash 或删除文件。

## 实现与文件范围

| 文件 | 内容 |
| --- | --- |
| `frontend/src/contracts.ts` | 同步已约定的 `MOCK / DOUYIN`，用于 runtime 与 collector.mode；明确已有采集状态枚举；前端启动选择类型只有现有三字段 |
| `frontend/src/runtime-api.ts` | `start(selection, signal?)` 发送所选三种模式；运行校验接受 DOUYIN；状态/操作支持取消；422 保留明确失败，不退回 MOCK |
| `frontend/src/api.ts` | bootstrap/config 读取支持调用方取消；原保存 CAS、409、超时和白名单字段逻辑保留 |
| `frontend/src/session-controls.ts` | 管理页和舞台共用的会话控制；能力检查、房间核对、三种选择、操作防护、轮询取消、版本隔离、诊断 sessionId/state 匹配 |
| `frontend/src/components/BackendRuntimePanel.vue` | 消息来源选择器、可用性原因、已保存与运行房间、真实采集状态/计数、安全错误码；保留回复/语音选择、Token 和诊断展示 |
| `frontend/src/components/AdminPage.vue` | 传递房间草稿/待核对状态；会话运行、启动中或状态未知时锁定房间输入及房间变更保存；保留其他配置编辑与409恢复 |
| `frontend/src/components/StagePage.vue` | 明确舞台标签指后端播放连接/浏览器演示，与消息来源分开；继续复用上述控制组件，音频代码未改 |
| `frontend/src/style.css` | 窄屏选择器换行、长会话标识与提示折行 |
| `frontend/tests/runtime-api.test.ts` | 扩展来源、独立回复/语音模式、取消、不回退、采集状态校验 |
| `frontend/tests/session-controls.test.ts` | 10项真实控制逻辑测试，依赖由假 HTTP 替换 |
| `frontend/tests/session-panel.test.ts` | 使用现有 Vue 编译器/SSR 渲染实际 SFC 的2项测试；断言禁用状态、来源、错误提示、Token/诊断保留与房间锁定事件 |
| `frontend/tests/session-fixtures.ts` | 人工接口数据，无真实响应/凭据 |
| `frontend/tests/session-fixture.html`、`frontend/tests/visual.html` | 不进入生产构建的交互/375px验收入口；夹具拦截所有接口请求，无 native fetch 回退 |

初始选择始终为 `MOCK + MOCK + LOCAL_TEST_TONE`。读取到已经运行的会话时展示并锁定后端实际模式；读取配置、保存房间、刷新页面、轮询均不会启动会话或主动调用云服务。

## 使用的现有接口与字段

- `GET /api/v1/bootstrap`：`capabilities.douyinCollector`。
- `GET /api/v1/providers`：`douyin.configured/enabled/state/reason`；保留 DeepSeek、NLS 与 `aliyunToken`。
- `GET /api/v1/config`：`version/settings.roomId`；管理页 `PUT /api/v1/config` 仍使用 `expectedVersion + settings`。启动逻辑不会保存配置。
- `POST /api/v1/sessions`：仅 `{ mode: 'MOCK' | 'DOUYIN', conversationMode: 'MOCK' | 'DEEPSEEK', speechMode: 'LOCAL_TEST_TONE' | 'ALIYUN_NLS' }`。不传房间或凭据，房间由后端已保存配置决定。
- `POST /api/v1/sessions/{sessionId}/stop`：运行、连接中、停止中或失败会话可发停止请求，结果文案不提前宣称资源清理完成。
- `GET /api/v1/runtime`：`sessionId/state/mode/roomId`、collector模式/状态/received/emitted/duplicates/rejected/lastError，以及现有队列、播放器、版本和失败指标。
- `GET /api/v1/runtime/diagnostics`：仅当 sessionId/state 与 runtime 一致时展示。
- 模拟评论/礼物与 replay 仍调用原有接口，但按钮与处理函数均要求 `runtime.state=RUNNING`、runtime/collector.mode **同时为 MOCK**、播放器 ready；DOUYIN 下三个按钮全部禁用。前端不是服务端安全边界。

### 能力与房间判定

- 必须有 `providers.douyin.enabled=true` 和 `bootstrap.capabilities.douyinCollector=true` 才能开放 DOUYIN。`UNVERIFIED` 本身不阻止选择，显示尚未完成真实接收验证。
- 若 enabled=true 但 configured=false、bootstrap能力缺失/false，或 provider.state 明确为 DISABLED/UNAVAILABLE/MISSING，则提示能力矛盾并禁用新启动；不伪造可用，也不自动改为 MOCK。运行中 runtime.mode 与 collector.mode 不一致亦明确提示。
- DOUYIN 启动前再次读取状态/能力/已保存配置。管理页若房间草稿不同、为空、未加载、正在保存或保存结果待核对，则阻止启动。舞台展示明确的已保存房间，提示其他页面未保存输入不会使用。
- 读取失败时清除过期显示并锁定控制；恢复只恢复读取，不重试启动。操作错误保留可见，不被下次成功轮询立刻冲掉。
- 每轮读取与操作有递增请求代次和 AbortController；旧轮询成功/失败、卸载后回调均不再修改新状态。操作开始取消前一轮读取，诊断在会话切换/操作时清空。

### 状态展示边界

采集状态分别显示连接中、已连接、重连中、直播已结束、失败和停止。连接状态与接收分开：`received=0/emitted=0` 明确尚无事件；received>0 但 emitted=0 只说明收到采集数据；emitted>0 只显示派发事件数，不声称已收到某一种弹幕/礼物或已经播放。会话 RUNNING、后端 HTTP 可达、舞台 WebSocket 已连接均不等于真实接收成功。错误展示固定安全码；非码形式的采集错误不直接反射到页面。

## 实际验证

在本工作树 `frontend/` 执行：

| 命令/检查 | 结果 |
| --- | --- |
| `npm test` | **51/51 通过，0失败、0跳过**；原37项保留，新增14项 |
| `npm run build` | vue-tsc 与 Vite 生产构建通过，33模块；未新增依赖 |
| `git diff --check` | 通过 |
| 与基线比较 backend、scripts、POM、OpenAPI、播放协议、stage算法、package/lock | 无修改 |
| `git merge-base --is-ancestor main HEAD`（代码交付时） | 0；本地 main 仍为 `03e26b6` |
| `./scripts/dev-frontend.sh` | 使用本工作树 15175；沙箱监听被拒后，获准以同一脚本启动；验收后只终止该临时进程，15175已无监听 |
| 浏览器 `/tests/visual.html` | 新管理与舞台控制夹具，以及原mock管理/双舞台，375px内容宽度均为375px，无横向溢出 |
| 浏览器 `/tests/session-fixture.html` | 默认MOCK；切DOUYIN并修改房间后启动禁用；注入409后草稿保留；读取/核对/保存后仍STOPPED；显式启动后来源及房间锁定、模拟按钮禁用；RECONNECTING显示“重连中/尚未收到事件”；停止后解锁 |

首次新增控制测试因对 module namespace 直接 structuredClone 导致9项夹具初始化失败；改为克隆普通数据对象后全部通过，未删除断言。首次构建命令误在仓库根运行，随后在 frontend 目录执行通过。开发脚本首次启动的 EPERM 为监听沙箱限制，不计成功证据。

临时浏览器画面位于 `/tmp/bigfish-phase4-frontend-fixture.png`，未提交；它是人工夹具画面，不是真实抖音证据。最后一轮改动将“采集数据包”文案准确改为“采集接收计数”，测试和构建已重新通过。

**真实联调：未完成，等待后端联调。** 本轮未启动后端、真实抖音采集、付费模型或语音；没有访问18080/15173主窗口服务。51项测试和浏览器检查均为本地替身/协议测试，不能计作真实抖音成功。

## 主窗口/后端待办

1. 当前同步基线仍由 SessionService 对 DOUYIN 返回422；providers enabled=false、bootstrap douyinCollector=false 且原因 LIVE_RECEIVE_NOT_VERIFIED，三者目前一致。后端完成接线后，应一起更新能力标志与实际会话实现，保证 enabled 的含义是允许显式启动。无需因 UNVERIFIED 隐藏已经具备的启动能力。
2. 服务端必须显式阻止模拟事件与 replay 混入 DOUYIN。当前基线 `simulate` 直接进入 engine、`replay` 只检查会话和播放器；因基线只允许MOCK尚未发生跨模式混入，但放开DOUYIN时必须补上模式保护并测试，不能依赖本页禁用按钮。
3. 请由主窗口同步 OpenAPI 中 RuntimeView.mode/collector.mode 的 DOUYIN 枚举及旧“DOUYIN返回422”说明，核对 provider、bootstrap、runtime 对齐。本窗口仅按用户明确约定扩展前端镜像，没有修改公共文档或Java DTO。
4. 前端无法从现有聚合计数确认事件是评论、礼物、关注还是入场；需要按类型展示接收证据时，请另行约定脱敏统计字段。本轮只展示现有计数，不虚构内容或新增API。
5. 房间由后端启动时的配置快照决定；前端启动前重读不能消除其他页面恰在GET与POST之间修改配置的竞态。请后端保证原子快照、返回实际运行roomId并维持会话隔离。若要严格绑定用户看到的配置版本，需要主窗口另行约定，前端本轮没有偷偷扩展启动请求。
6. 后端完成后在独立实例验证：已保存房间 → DOUYIN → 连接/重连/结束/失败 → 实际事件计数；再验证旧会话轮询/回执隔离。真实验收由主窗口显式操作，默认 MOCK 回复和本地测试音；付费服务应另行明确选择。需继续保留现有 NLS Token、运行诊断、音频就绪与真实播放回执回归。

本轮没有新增接口或字段的实施依赖；以上公共契约同步与后续统计建议均交主窗口审核，不自动联系其他窗口。
