# 第五轮前端：管理配置与舞台一键互动

日期：2026-11-01（Asia/Shanghai）。工作树 `bigfish-worktrees/frontend`，分支 `feature/frontend-stage`。

## 基线、范围与提交

- 开工确认目录和分支正确，工作区干净；基线为上一轮交付 **`7dcb013416ea8749dcf9a8230228302917ba52cc`**，全部保留。
- 开工及交付检查 `git merge-base --is-ancestor main HEAD` 均返回 0。本地 main 为 `03e26b619715e68c9de00294d753eaa481dad31c`，已经包含，无需同步、未执行合并、未更新 main。
- 实现提交：**`64c7f04422019d8a4068ef50b3cdff36ce3c8f35`**，`feat(frontend): coordinate one-click stage interaction and playback recovery`。本交接文档提交 SHA 见最终回复，不在文件中自引用。
- 实现仅涉及 `frontend/` 下 20 个文件；文档仅新增本文件。用户本轮明确的 Vue 职责优先于历史第三轮语音分工。
- 未改 backend、Java 语音、采集器、签名、公共 OpenAPI/播放协议消息格式、数据库、根构建配置或依赖；未修改其他交接文件。未 reset、stash、删除文件、合入其他分支、推送、创建 PR 或联系其他窗口。

## 实现内容与文件范围

| 文件 | 改动 |
| --- | --- |
| `frontend/src/components/AdminPage.vue` | 主流程改为保存配置、打开舞台；房间草稿未保存/保存结果待核对时阻止主入口跳转并提示；保留配置 CAS、409恢复、运行中房间锁定；浏览器演示调试入口默认折叠 |
| `frontend/src/components/BackendRuntimePanel.vue` | 三项启动选择集中于管理页；舞台只展示后端实际状态；移除普通启动会话按钮，管理页保留次要停止入口；Token、模拟注入、队列和详细任务记录默认折叠；可共享舞台控制实例，避免重复轮询 |
| `frontend/src/components/RuntimePanel.vue` | 浏览器演示说明同步为舞台一键互动流程 |
| `frontend/src/components/StagePage.vue` | 一键开始/停止、同会话恢复播放、声音授权补救；后端实际选项与本地偏好分开；保存房间与运行房间有明确标签；连接/声音/阶段提示，手工操作和回执调试默认折叠 |
| `frontend/src/launch-options.ts` | 带命名空间和版本的三枚举存储、严格白名单、异常提示、跨页面 storage 同步；不保存配置、房间或任何凭据 |
| `frontend/src/session-controls.ts` | 在已有轮询代次/取消框架内增加 prepareSession、stopSession、verifySession；最新状态/能力/配置检查，已有会话接入，超时/SESSION_ACTIVE 查询恢复，禁止盲目重发 POST |
| `frontend/src/api.ts`、`runtime-api.ts` | RequestError 附带已识别的安全错误码，供 SESSION_ACTIVE 分支使用；不反射未知错误体 |
| `frontend/src/stage/interaction.ts` | 协调用户点击音频解锁、会话准备、固定目标会话连接、停止与恢复；操作代次和取消屏蔽旧回调 |
| `frontend/src/stage/controller.ts` | 分离音频 unlock 与租约确认后的 READY；解锁有单飞、5秒等待上限、取消代次；目标 sessionId 不符即断开；失效租约和晚到解锁不能宣告 ready |
| `frontend/src/stage/audio.ts` | 用实际 AudioContext 时钟、独立计时器和 onended 判定播放，STARTED 不再仅依赖动画帧；动画暂停不伪造回执；保留下载/解码/时长/取消保护 |
| `frontend/src/stage/lifecycle.ts` | hidden 不主动断开；可见时核对租约和会话；freeze/pagehide 静音清理，恢复不自动启动或重连 |
| `frontend/src/stage/protocol.ts` | 仅本地提示原因和文案调整，无线上消息字段或消息类型变更 |
| `frontend/tests/launch-options.test.ts`、`interaction.test.ts`、`lifecycle.test.ts` | 新增存储、协调流程与页面生命周期测试 |
| `frontend/tests/audio.test.ts`、`controller.test.ts`、`session-controls.test.ts`、`session-panel.test.ts` | 扩展回执/租约/取消/超时/旧会话测试，更新实际 SFC 断言；保留上一轮来源与配置保护 |

### 本浏览器启动选项

存储键为 `bigfish.browser.launch-options.v1`，值只含以下白名单结构：

```json
{"version":1,"options":{"mode":"MOCK","conversationMode":"MOCK","speechMode":"LOCAL_TEST_TONE"}}
```

允许 mode 为 MOCK/DOUYIN，conversationMode 为 MOCK/DEEPSEEK，speechMode 为 LOCAL_TEST_TONE/ALIYUN_NLS。首次使用默认如上；额外属性会被丢弃，畸形数据、未知版本和存储不可用有提示。三项选择改变时自动保存于本浏览器，和后端业务配置的“保存配置”明确区分。

管理页加载/刷新/保存、storage 事件、舞台加载均不会启动会话。舞台无第二套选择器；已有活动会话显示后端实际选项，本地偏好不同会提示，不改配、不重启。transport=mock 是浏览器内演示，仍和连接真实后端时的 MOCK 消息来源区分。

### 开始、停止与恢复约束

1. 用户点击的同步调用链先执行 AudioContext.resume，再发 HTTP。音频解锁和网络准备可并行，但只有声音可用、租约有效且 sessionId 与目标一致时才发送原 PLAYER_READY。
2. 一次读取最新 runtime/providers/bootstrap/config/diagnostics 后：已有 RUNNING 会话直接接入；STARTING 显示尚在启动并等待用户后续恢复；STOPPING 不创建；无活动会话才按三项选项创建。保留 DOUYIN 能力、已保存房间、回复/语音服务可用性检查，不偷偷保存配置或回退 MOCK。
3. 连续点击受操作门控。SESSION_ACTIVE、启动超时/网络失败后只查询状态，不自动重发 POST；发现活动会话就使用其真实选项。
4. 声音被阻止时保留已确认会话和租约，显示“点击启用声音”；补救只尝试解锁，不再创建会话。租约属于其他会话或其他播放页占用时不 READY、不抢占、不播放。
5. 停止先断开本页和停止音频、取消下载/待播，再请求后端停止。只有 STOPPED 响应才显示已确认；超时/错误明确“本页已静音；后端停止未确认”。旧启动/连接/解锁回调不能重新启用播放。若后端已换会话，不停止新会话。
6. 本地断线保留目标 sessionId；恢复只接回仍在 RUNNING 的同一会话，停止/变化则拒绝，不自动新建。所有生产 WebSocket 连接都带本地预期 sessionId 校验，线上协议未新增字段。
7. 普通隐藏标签页保持原心跳与租约；动画可暂停。页面关闭/离开、freeze、真正断线、租约到期、停止仍清理。恢复可见时先检查租约，再核对会话；失效时静音且需手动恢复，不补播过期任务。浏览器冻结/系统休眠不能保证连续播放。

## 使用的现有接口

- `GET /api/v1/runtime`、`/providers`、`/bootstrap`、`/config`、`/runtime/diagnostics`。
- `POST /api/v1/sessions` 仍只有 `mode`、`conversationMode`、`speechMode`；没有房间、凭据或自动保存参数。
- `POST /api/v1/sessions/{sessionId}/stop`；管理页原配置 PUT/CAS 与高级调试模拟接口保留。
- `/ws/stage` 保留现有 PLAYER_HELLO、PLAYER_LEASE、PLAYER_READY、PLAYER_HEARTBEAT、PLAYBACK_ACK 等消息格式。STARTED 来自音频实际时钟推进，COMPLETED 来自实际结束；旧任务、重复任务、过期任务仍受原校验。
- 状态分开显示后端互动会话、采集连接/计数、本页播放端和模型/合成/播放处理阶段；RUNNING/租约 ready 不等于收到真实弹幕。DOUYIN 仍禁用模拟评论、礼物及回放。

## 验证结果

### 自动测试与构建

在本工作树执行 `npm --prefix frontend test`（等价于 frontend 目录的 `npm test`）和 frontend 目录的 `npm run build`：

| 检查 | 结果 |
| --- | --- |
| `npm test` | **69/69 通过，0失败、0跳过**，上一轮51项保留并增加18项 |
| `npm run build` | vue-tsc、Vite 生产构建通过，36模块 |
| `git diff --check` / 暂存差异检查 | 通过 |
| 基线比较 backend、OpenAPI、公共播放协议、event-schema、POM、scripts、npm package/lock | 无修改 |
| 本地 main 祖先检查 | 返回0，无需合并 |

新增覆盖包括：默认与白名单存储/跨页面同步、音频先于网络、现有会话不同选项接入、声音阻止补救、连续点击、启动超时及 SESSION_ACTIVE 查询不重试、停止竞态与失败、恢复不创建、目标租约不符、第二页拒绝、晚到解锁/过期租约、标签切换/freeze/清理，以及无 rAF 时真实音频时钟产生 STARTED。原 DOUYIN 可用性、房间未保存、409/读取失败、模拟隔离、重连状态、旧轮询及播放安全测试继续通过。

开发中一轮 SSR 测试因新增模块的相对路径解析失败；改为相对被测 SFC URL 解析后通过，未删除保护断言。最终管理演示折叠改动后重新运行测试和构建，均通过。

### 本机真实 HTTP/WebSocket 与浏览器验证

使用本工作树 `BIGFISH_ALLOW_PAID=false ./scripts/dev-backend.sh` 和 `./scripts/dev-frontend.sh`，端口分别为 **18082 / 15175**，脚本读取隔离环境。只运行 MOCK + MOCK + LOCAL_TEST_TONE；未调用真实抖音、DeepSeek、阿里云签发或语音服务。

在 Codex 内置浏览器的真实页面验证（不是人工 HTTP 夹具）：

- 管理页无普通“启动会话”入口，三项默认 MOCK/MOCK/本地测试音；后端不可用选项禁用，原业务配置 v3 未修改。
- 舞台一次点击“开始互动”完成后端会话、WebSocket 租约和声音 ready，无需再点连接或启用声音。
- 高级区发送一条模拟评论，后端产生本地测试音任务；浏览器记录真实 STARTED（20:33:13）、COMPLETED（20:33:14），字幕随播放出现，音量恢复为0，后端模型/合成/播放诊断更新。
- 第二舞台页接入同一活动会话时收到 PLAYER_ALREADY_ACTIVE，保持静音，没有再创建会话；打开第二页后首个页面租约跨多个心跳窗口继续续期。
- 仅断开本页播放后，后端仍为 RUNNING；“恢复播放”接回同一 sessionId 并取得新租约，没有重启后端会话。
- “停止互动”本页静音且后端确认 STOPPED。最终界面另开一次 MOCK 会话复验一键 ready 后也明确停止；所有本轮创建的会话都已停止。
- 临时截图 `/tmp/bigfish-phase5-stage.png` 为真实本机 MOCK 舞台 ready 画面，未入仓库，不是真实抖音或阿里云语音证据。

验收后只终止本轮启动的两个开发服务；lsof 检查18082/15175均无监听。未停止或操作主项目18080/15173及其他窗口服务。

### 人工夹具与未验证项

- 既有 `/tests/session-fixture.html` 拦截请求：选择 DOUYIN、修改房间草稿后，主“打开舞台”入口明确阻止并要求先保存/核对。实际舞台收到 storage 事件仅更新摘要，后端保持 STOPPED；未点击真实 DOUYIN 启动。检查后恢复本浏览器 MOCK 默认选项，未保存真实后端配置。
- `/tests/visual.html` 的5个375px窗口（来源管理夹具、舞台状态夹具、mock管理、两个mock舞台）内容宽度均为375px，无横向溢出。
- 浏览器阻止 resume、POST超时、停止异步竞争、租约过期和 freeze 使用可控替身/时钟测试，不能当作真实浏览器策略或操作系统休眠验收。
- **未单独运行 Safari 应用、Chrome 应用；未验证长期后台、OBS、移动系统冻结/休眠行为。** 内置浏览器结果不能直接替代这些验收。
- **真实抖音联调未完成**；当前基线仍未开放主会话 DOUYIN。本轮本机真实接口验证仅证明 MOCK 互动/播放流程，不证明真实采集或付费服务成功。

## 主窗口待办、限制与接口提案

1. 本轮无需新增后端接口或公共字段即可接入。请在主窗口合入后验证真实 DOUYIN 接线及原 DeepSeek/NLS/Token 集成，本轮不代替第三轮语音云验收。
2. 启动 POST 的结果若始终不明确且查询仍无活动会话，本页面后续开始操作保持只查询、不重发；此时停止也不谎称后端最终停止。现有接口无法证明已取消的 HTTP 请求不会在服务端稍后创建会话。若需要完全闭合此类不确定结果，建议未来由主窗口约定启动请求幂等标识/操作结果查询；本轮没有擅自增加字段。用户应先核对后端，不以刷新页面作为自动重试机制。
3. localStorage 只在同源同浏览器内共享。其他页面未保存的业务配置草稿不跨页传递；主打开入口会拦截本页未保存房间，直接打开舞台明确使用后端已保存值。GET配置与POST启动间其他页面改配的原子性仍由后端负责。
4. 请主窗口更新公共 README/播放协议中的旧“三步启动”与“hidden主动断开”操作描述，与本轮本地生命周期保持一致；线上消息格式无需变化。本窗口遵守职责范围，未修改这些公共文件。
5. 沿用第四轮待办：后端开放 DOUYIN 时应统一 providers/bootstrap/runtime 能力语义，并在服务端阻止模拟事件/回放混入真实会话。前端禁用不是服务端权限边界。
6. 建议主窗口在 Safari、Chrome 各做点击授权被拒后补救、双页租约、切后台多个租约窗口、睡眠恢复、停止时网络中断的独立验收；保持严格到期/会话校验，不用假回执维持在线。

交由主集成窗口审核合并和最终联调；没有自动联系其他窗口。
