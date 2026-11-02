# 架构与第一阶段范围

状态：已集成 MOCK/DOUYIN 主会话、DeepSeek/NLS 与 Vue 一键互动；指定抖音房间的独立采集测试已通过游客解析、WebSocket 握手及真实评论/入场/点赞接收，修复后该次测试 MALFORMED 为 0。真实礼物和主会话真实弹幕到语音播放的完整链路仍待验收。开发分离、交付可合并；单 Java 进程，不引入微服务、Redis、消息中间件或配置中心。

## 数据通路

CollectorPort → EventIngress → 事件校验/去重 → 分类有界队列 → ConversationPort（评论）或礼物模板 → 语音队列 → SpeechPort → 同源音频资源 → /ws/stage → 唯一播放端 → PlaybackAck。

抖音连接由 SessionCollectors 按后端环境显式选择 guest/external，默认游客；external 注入现有 CredentialSource，不增加独立采集进程。两层诊断共用 sessionId：collector_inbound 记录过滤前 method、结构故障、去重、派发及会话累计值；gift_trace 记录后端任务校验、合并、模板、合成和播放结果。采集回调仍为 void Consumer，SINK_RETURNED 不等于业务接受；当前没有跨这两层的逐条关联 ID。礼物业务文件保存为可选的有界异步写入，不改变公共 DTO、点赞策略或消息优先级。

管理端 /admin：业务配置、本浏览器启动选项、打开舞台、停止互动、连接状态与诊断；不创建 AudioContext。
播放端 /stage：一键开始/停止互动、接入或恢复同一会话、音频、字幕和形象。页面分别控制同一个后端会话，只有有效租约的播放端能发声。

形象默认 SVG，可在本浏览器切换为固定 BigFish Live2D。BrowserAudio 实际音量通过已有组件参数驱动嘴型；渲染适配层负责眨眼、待机、物理与手动表情，不新增播放端、后端轮询或情绪协议。独立外部资源根目录由 `BIGFISH_AVATAR_ASSETS_DIR` 在启动时指定，只读同源 `/avatar-assets/` 提供受限文件；资源不属于运行数据、SQLite 或 JAR。当前没有目录扫描和模型上传，详见 [Live2D](live2d.md)。

## 模块

- contract：公共 Java DTO/接口，集成窗口独占。
- collector：Java 采集器与协议适配，采集窗口负责。输出按 event-schema 归一化的消息。
- event/scheduler：事件合法性、幂等去重、限频/合并、容量和 TTL，后端负责。
- conversation/speech：模型与语音适配，后端负责。依赖接口，不依赖采集器实现。
- playback：单一播放端租约、WebSocket、同源文件、播放超时，后端负责。
- configuration/persistence/management：配置、SQLite、会话管理与 REST，后端负责。
- frontend：管理与舞台，前端负责。

## 已实现 API

现有：GET /api/v1/bootstrap，GET /api/v1/config，PUT /api/v1/config，GET /actuator/health。
会话与播放接口：
- POST /api/v1/sessions：显式选择 mode=MOCK/DOUYIN、conversationMode=MOCK/DEEPSEEK、speechMode=LOCAL_TEST_TONE/ALIYUN_NLS；DOUYIN 从已保存配置取得网页房间号。能力开放不代表实际联网验证通过。
- POST /api/v1/sessions/{sessionId}/stop：幂等停止。
- GET /api/v1/runtime：会话、采集状态、队列深度、播放端、错误指标。
- POST /api/v1/events/simulate：只允许 MOCK 会话，通过标准校验和调度器。
- GET /api/v1/audio/{assetId}：受控同源音频读取，禁止任意路径读取。
- WS /ws/stage：见 playback-protocol.md。
- POST /api/v1/sessions/{sessionId}/replay：就绪播放端下回放人工采集事件，验证累计礼物转增量。
具体请求/响应见 OpenAPI 和公共 RuntimeView DTO。

## 配置策略

底层启动配置在 YAML/环境变量；业务配置在 SQLite，乐观版本锁。当前保存为完整 settings 对象，字段校验通过后原子更新；不可只改内存不落库。三项启动选项由前端仅存入同源 localStorage 的版本化白名单对象，不属于服务器配置，也不会自动启动。
引擎每个任务捕获 ConfigSnapshot，不改变已经提交的任务参数。房间和供应商选择在会话启动时确定，房间切换需新会话；凭据更改需要重启后端。API 返回保存版本与最近任务使用版本，二者不等同。DOUYIN runtime.roomId 为解析后绑定的内部号，与配置网页号不同。

## 调度初始值

礼物 60 > 评论 50 > 关注 10 > 入场 5；同级 FIFO，满时淘汰最低优先级中最旧消息。合成与待播放队列各 8 条。礼物先去重、连击转增量，再按用户/礼物短窗口合并。入场/关注 30 秒至多一条且忙时跳过。礼物不必等待模型。
评论 TTL 60 秒、礼物 120 秒、欢迎 40 秒为建议初值；实际参数由后端扩展配置提案决定。过期必须在出队及播放前检查。不要因 PriorityBlockingQueue 名称就认为它有容量上限。

## 生命周期与失败

应用启动和直播会话启动分离。会话状态 STOPPED → STARTING → RUNNING → STOPPING → STOPPED；异常可进入 FAILED。直播关播只结束会话，不退出 Spring Boot。
停止先关闭入口并取消模型/合成/播放，再停止采集并有界等待资源退出；旧采集 Handle 未释放时阻止再次启动。旧 sessionId 的音频及回执忽略。播放器断线独立于主会话，撤销旧播放代次后可手动恢复同会话；无 ready 播放端不发起新的模型/TTS工作。普通标签隐藏不主动断开，但冻结、租约过期、音频中断仍清理播放端。
模型需要整体截止时间（含 SSE keep-alive）；TTS 连接/读取/整体截止时间；有限重试、冷却与备用端。播放断线不得无限积压；自动回放必须防重复。实际阈值需故障注入测试。

## 迁移边界

旧 Luna 仅作只读行为参考，不复制 config.json、API Key、Cookie。现有采集签名适配标注 AGPL；第三方代码若复用必须说明许可证并由集成窗口决定。现有大肥鱼模型有使用限制，第一版前端可以先用原创简单占位动画，接口保持可换模型。

## 运行隔离

main：18080/15173；backend：18081/15174；frontend：18082/15175；collector：18083/15176。每个 worktree 自有 runtime、.cache；默认只监听 127.0.0.1。子任务不改用户网络代理或杀其他工作树的进程。
