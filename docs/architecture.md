# 架构与第一阶段范围

状态：框架基线，业务引擎待各任务实现。开发分离、交付可合并；单 Java 进程，不引入微服务、Redis、消息中间件或配置中心。

## 数据通路

CollectorPort → EventIngress → 事件校验/去重 → 分类有界队列 → ConversationPort（评论）或礼物模板 → 语音队列 → SpeechPort → 同源音频资源 → /ws/stage → 唯一播放端 → PlaybackAck。

管理端 /admin：配置、开始/停止、连接状态、队列与错误诊断。
播放端 /stage：只负责音频、字幕和形象；管理页不能同时播音。

## 模块

- contract：公共 Java DTO/接口，集成窗口独占。
- collector：Java 采集器与协议适配，采集窗口负责。输出按 event-schema 归一化的消息。
- event/scheduler：事件合法性、幂等去重、限频/合并、容量和 TTL，后端负责。
- conversation/speech：模型与语音适配，后端负责。依赖接口，不依赖采集器实现。
- playback：单一播放端租约、WebSocket、同源文件、播放超时，后端负责。
- configuration/persistence/management：配置、SQLite、会话管理与 REST，后端负责。
- frontend：管理与舞台，前端负责。

## 骨架 API 与计划 API

现有：GET /api/v1/bootstrap，GET /api/v1/config，PUT /api/v1/config，GET /actuator/health。
计划接口（尚未实现、不能在 UI 中假装成功）：
- POST /api/v1/sessions：创建会话；默认 mock，真实接入需要显式 mode=DOUYIN。
- POST /api/v1/sessions/{sessionId}/stop：幂等停止。
- GET /api/v1/runtime：会话、采集状态、队列深度、播放端、错误指标。
- POST /api/v1/events/simulate：只允许 MOCK 会话，通过标准校验和调度器。
- GET /api/v1/audio/{assetId}：受控同源音频读取，禁止任意路径读取。
- WS /ws/stage：见 playback-protocol.md。
后端实现前由集成窗口确认这些端点的具体 schema 并更新 OpenAPI。

## 配置策略

底层启动配置在 YAML/环境变量；业务配置在 SQLite，乐观版本锁。当前保存为完整 settings 对象，字段校验通过后原子更新；不可只改内存不落库。
未来引擎每个任务捕获 ConfigSnapshot，不允许运行中改变已经提交的任务参数。提示词/限频/音色下一任务生效；房间切换需新会话；模型凭据/连接需先验证、再替换客户端。API 返回保存版本与运行版本，二者不等同。

## 调度初始值

礼物 60 > 评论 50 > 关注 10 > 入场 5；同级 FIFO，满时淘汰最低优先级中最旧消息。合成与待播放队列各 8 条。礼物先去重、连击转增量，再按用户/礼物短窗口合并。入场/关注 30 秒至多一条且忙时跳过。礼物不必等待模型。
评论 TTL 60 秒、礼物 120 秒、欢迎 40 秒为建议初值；实际参数由后端扩展配置提案决定。过期必须在出队及播放前检查。不要因 PriorityBlockingQueue 名称就认为它有容量上限。

## 生命周期与失败

应用启动和直播会话启动分离。会话状态 STOPPED → STARTING → RUNNING → STOPPING → STOPPED；异常可进入 FAILED。直播关播只结束会话，不退出 Spring Boot。
停止顺序：关入口、停采集与重连、取消模型与合成、取消待播任务并通知播放端、释放会话资源。旧 sessionId 的音频及回执忽略。
模型需要整体截止时间（含 SSE keep-alive）；TTS 连接/读取/整体截止时间；有限重试、冷却与备用端。播放断线不得无限积压；自动回放必须防重复。实际阈值需故障注入测试。

## 迁移边界

旧 Luna 仅作只读行为参考，不复制 config.json、API Key、Cookie。现有采集签名适配标注 AGPL；第三方代码若复用必须说明许可证并由集成窗口决定。现有大肥鱼模型有使用限制，第一版前端可以先用原创简单占位动画，接口保持可换模型。

## 运行隔离

main：18080/15173；backend：18081/15174；frontend：18082/15175；collector：18083/15176。每个 worktree 自有 runtime、.cache；默认只监听 127.0.0.1。子任务不改用户网络代理或杀其他工作树的进程。
