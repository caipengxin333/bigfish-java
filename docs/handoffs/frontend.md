# Vue 前端与播放舞台交接

任务/分支：前端与舞台 / `feature/frontend-stage`
工作树：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/frontend`
状态：前端实现及本地验收完成；真实播放引擎、运行接口与 Live2D 联调待集成。
验收日期：2026-10-29 浏览器实测；2026-10-30 最终回归与提交。

## 基线与提交

- 基线：`a3ac3acdcef2f185df39e0dc6d7819a36b0628f1`
- 配置请求基础：`732d13e605d2d5fd897e8d8f20050f9940905f1b` — 响应校验、HTTP/409/超时错误分类、只发送契约字段、API 测试入口。
- 最终实现：`a6ce54941fdfc85810140a4881157a325941a013` — 管理页、独立舞台、mock transport、原创角色、回归测试与操作说明。
- 本交接文件在上述实现之后单独提交；文档提交 SHA 见当前窗口最终回复或 `git log -1`。
- 未合并其他分支、未推送、未创建 PR；不修改主集成工作区或旧 Luna。

## 实现内容

1. 管理配置：编辑所有现有 Settings 字段、字段范围限制、未保存提示、读取失败重试、保存时间及版本。草稿与已保存快照分离；409/超时/网络错误保留草稿，读取最新版本后明确选择保留整份草稿或采用服务端配置，不静默覆盖。
2. 保存与生效：真实模式显示保存版本，运行生效版本明确“不可用”；mock 启动演示时捕获保存版本，后续保存不会伪装为运行已应用。
3. 运行诊断：真实状态来源为 bootstrap，尚无 runtime 字段的采集、播放、队列和失败计数显示不可用；真实会话按钮禁用。Mock 提供启动、停止、队列拒绝原因演练，明确数据仅为本页演示。
4. 显式模式：默认真实接口，只有 `?transport=mock` 或页面模式切换才启用本地演示；页面始终显示模式，不自动回退、不声称抖音已连接或 TTS 已合成。
5. 舞台：同源 WebSocket `/ws/stage`，独立随机 playerId，租约握手/5 秒心跳/代次校验/到期失效，点击启用声音后才发送 PLAYER_READY。
6. 音频：受控同源 URL、流式大小限制、下载/解码时限、播放时限、可取消异步流程。STARTED 在音频源启动且音频时钟推进后发出，COMPLETED 由自然结束触发；取消不误报完成。字幕与实际播放绑定，RMS 驱动嘴型。
7. 防误播：一个当前任务，无浏览器待播队列；重复、旧 sessionId、过期任务拦截；断线、停止、取消、租约到期或音频失败立即停止声音、清字幕和嘴型；过期租约不能由迟到心跳复活；旧连接回调不能影响新连接。
8. Mock 舞台：Web Locks 独占租约，第二页面拒绝后保持静音；内存生成原创 4 秒 PCM 测试音；支持重发、过期、旧会话、下载/解码失败、超时、取消、停止、断线与续租中断。
9. 素材与适配：原创 SVG 鱼形角色，`stageAvatarKey` 可注入替代渲染组件；未复制旧 Live2D 模型、纹理、音频、配置或签名脚本。375px 布局可用。
10. 管理页无声：AudioContext 只存在于独立加载的 StagePage 构建包；没有供应商密钥字段或供应商 API 请求。

## 修改范围

仅 `frontend/`（不含 `src/contracts.ts`）与本文件。新增内容集中在 `src/components/`、`src/stage/`、`src/mock/`、`tests/` 和 `frontend/README.md`。package.json 仅新增 Node 内置测试命令，tsconfig 允许测试所需的 `.ts` 导入。

未修改 contracts.ts、公共 Java DTO、OpenAPI、事件/播放协议、后端源代码、启动脚本、依赖版本或 lockfile。runtime 数据、缓存、dist、node_modules 没有提交。

## 命令与结果

| 命令/检查 | 结果 |
|---|---|
| `pwd`、`git branch --show-current`、`git status --short` | 初始目录与分支正确、工作区干净；恢复任务时只存在本任务改动 |
| `cd frontend && npm ci --registry=https://registry.npmjs.org --cache ../.cache/npm` | 首次受沙箱 DNS 限制失败，授权联网后安装成功，47 个包；版本及 lockfile 未改变 |
| `cd frontend && npm test` | 最终 **28/28 通过**：HTTP 校验/冲突/超时、音频生命周期、租约/会话/去重/失效、旧连接回调、缓存容量、mock CAS 并发保存 |
| `cd frontend && npm run build` | **通过**，vue-tsc 与 Vite 均成功；主包约 83.97 kB、舞台包约 24.65 kB、CSS 6.58 kB（压缩前） |
| `./scripts/dev-backend.sh` | 首次下载与监听受沙箱限制；授权后在 **18082** 启动成功，使用当前工作树 runtime |
| `./scripts/dev-frontend.sh` | 监听受沙箱限制；授权后在 **15175** 启动成功，未连接主集成端口 |
| `git diff --check` | 通过 |
| 构建产物检查 `rg -l 'AudioContext' frontend/dist/assets/*.js` | 只匹配 StagePage 包，管理主包没有 AudioContext |
| 允许目录/凭据检查 | 仅允许文件；无供应商 Key、Cookie、数据库、音频或缓存进入提交 |

本次未修改后端，未运行后端 Maven test；后端脚本只用于已有配置 API 联调。首次启动失败的日志保留在工具记录中，没有关闭 TLS 或改代理绕过。

## 真实服务验证与 mock 验证

**真实后端（18082，经 15175 代理）**：bootstrap 返回 SCAFFOLD/STOPPED；配置加载成功；页面 A 保存后页面 B 旧版本保存收到实际 HTTP 409，草稿保留；读取最新版本/保留草稿后可继续保存。验收仅改测试工作树配置名称，随后恢复“默认配置”（保存版本递增到 v3）。停止本次后端后读取配置与状态出现明确 HTTP 500 代理错误提示。真实 WebSocket 不可用时显示断开并保持静音。

**浏览器 mock + 真实 Web Audio**：点击启用前静音；4 秒测试音有实际音量值（观察到 42%），STARTED 与 COMPLETED 相隔 4 秒；重复/过期/旧会话任务无重播；下载失败、解码失败、12 秒下载超时均有提示；播放中断线/停止后立即归零嘴型和字幕；第二页面获得 PLAYER_ALREADY_ACTIVE 提示，启用按钮禁用。保存 v2 与模拟生效 v1 可同时显示；队列拒绝原因可见。

**移动布局**：`/tests/visual.html` 加载后复查，375px 管理页及两个舞台页面均为 clientWidth=375 / scrollWidth=375，无横向溢出；已做浏览器截图检查。该夹具不进入生产构建。

**自动化替身验证**：租约到期、错误代次心跳、旧连接回调、加载期间取消/过期、迟到音频完成、去重容量与停止会话容量、并发 mock 保存。音频单测替换 AudioContext，不能代替设备听音或真实 TTS 验收。

没有进行真实抖音采集、LLM、TTS、音频 HTTP 资源成功下载、Java WebSocket 租约引擎或 Live2D 验证；不能把 mock 结果当作这些能力已实现。

## 待集成确认的公共接口提案

1. 保留现有 bootstrap/config 契约。请集成窗口先确认 runtime/session/simulate 的正式 schema，尤其运行生效版本、会话 mode、各队列深度/容量、失败原因、播放端状态；当前前端没有自造这些 REST 字段。
2. 播放协议未给出完整握手消息示例。请明确：未获得会话前 PLAYER_HELLO 的 envelope.sessionId（当前为空字符串）；PLAYER_READY payload（当前使用协议已有的 playerId/leaseGeneration）；PLAYER_LEASE 的 envelope.sessionId 必须绑定会话；HEARTBEAT_ACK 必须携带 playerId/leaseGeneration，expiresAt 缺省时按文档续租 15 秒。当前实现是保守解释，真实服务上线前必须联合确认。
3. 请明确会话切换通过重新握手获得租约；当前在同一租约内拒绝不同 sessionId。已停止会话不能再次获得租约。断线/拒绝/停止后需用户手动连接与启用，服务端不能把已经 STARTED 的旧任务重派到新页面。
4. 请统一 errorCode 表。当前稳定本地码包括 AUDIO_DOWNLOAD_FAILED / AUDIO_DOWNLOAD_TIMEOUT / AUDIO_DECODE_FAILED / AUDIO_TOO_LARGE / AUDIO_TOO_LONG / PLAYBACK_BLOCKED / AUDIO_INTERRUPTED / PLAYBACK_TIMEOUT / TASK_EXPIRED / PLAYER_BUSY / NOT_READY / CANCELLED / SESSION_STOPPED / DISCONNECTED / USER_DISCONNECTED / PAGE_HIDDEN。没有新增 ACK 字段。
5. 当前前端保护上限：握手 8 秒，下载+解码 12 秒，音频 20 MiB / 120 秒，去重 2048 项且至少保留 15 分钟，停止会话 128 个；资产 ID 字符限制见 frontend/README.md。建议后端统一确认这些阈值及资源命名。
6. `npm ci` 的审计报告有 **3 个 high 告警**，涉及现有 Vue/server-renderer 与 Vite 依赖链；报告包含 Vite 文件读取与 server.fs.deny 绕过等条目。未自行运行 audit fix 或升级版本，请集成窗口统一评估版本调整并回归。当前没有新增依赖。

## 限制与集成步骤

- Mock 配置和租约依赖安全上下文中的 Web Locks；不支持则明确报错并保持静音。队列与运行指标演练不表示实现了后台调度。
- 当前隐藏舞台页面会主动静音断开，以避免后台定时器节流造成过期租约发声；OBS/后台播放尚未验收，不能宣传为可直接用于无人值守 OBS。恢复后需重新连接。需要支持后台时应与后端一起确定失效策略。
- 去重不跨刷新持久化；任务刷新后的幂等需后端保证。停止会话记录满 128 个后要求刷新。时间比较依赖同机/同步时钟；跨机器部署需评估时钟偏差。
- Live2D 只有渲染替换入口，未安装 SDK 或加载任何受限模型。更换组件只接受 mouthLevel/speaking/connected，不得自行播音。
- 本窗口未向其他 Codex 任务发送消息。由用户或集成窗口读取本文件，在主集成窗口合并本分支；不要重复 merge 和 cherry-pick 同一提交。
- 合并后先运行前端测试与构建，再按 frontend/README.md 复现配置冲突和 mock 故障；完成正式 schema 后联调真实 WebSocket、资源下载、租约拒绝、旧会话隔离与服务端播放超时。后端整体验证由集成窗口运行。
