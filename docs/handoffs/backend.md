# Java 后端核心交接

任务/分支：后端核心 / feature/backend-core
基线：a3ac3acdcef2f185df39e0dc6d7819a36b0628f1
状态：实现中；不修改公共契约、POM、主集成目录或旧项目。

## 公共接口提案（待集成窗口确认）

当前仅实现已有播放协议和内部 Java 会话/模拟入口。以下管理 REST 需集成窗口先更新 OpenAPI 与公共 DTO，再接线，避免前后端自行定义不同字段。

- POST /api/v1/sessions：请求 `{mode:"MOCK", conversationMode:"MOCK"}`；默认 MOCK。显式 DEEPSEEK 还须服务器允许付费且配置环境凭据。DOUYIN 本任务拒绝。返回 sessionId、mode、conversationMode、speechMode（LOCAL_TEST_TONE）、state、roomId、savedConfigVersion、appliedConfigVersion。已有运行会话返回 409。
- POST /api/v1/sessions/{sessionId}/stop：幂等；旧 ID 不停止新会话；返回对应会话的停止结果。
- GET /api/v1/runtime：上述状态、各队列深度/容量、模型/合成活动数、播放端租约状态、累计丢弃/失败计数；不含凭据、供应商错误原文、用户内容或本地路径。
- POST /api/v1/events/simulate：仅 MOCK；请求复用 LiveEvent v1，服务端覆盖 receivedAt；返回 EventIngress.Result。建议 202 ACCEPTED，409 REJECTED_SESSION，429 REJECTED_FULL，400 INVALID，200 DUPLICATE。
- GET /api/v1/audio/{assetId}：按已约定播放 URL 提供 audio/wav，Cache-Control: no-store；仅当前会话且未过期资源；未知/旧/过期 ID 404，禁止路径访问。请在 OpenAPI 补登记。
- 保留 bootstrap.mode=SCAFFOLD，直到上述管理入口获确认；以现有 capabilities 布尔字典标记 mock/localTestSpeech，不将测试音误报为真实 TTS。
- PLAYER_READY payload 在原文未明确，建议确认与 heartbeat 一样携带 playerId、leaseGeneration；实现按此校验。

## 调度与依赖提案

- 事件 TTL 暂固定：评论 60s，礼物 120s，欢迎 40s；去重 5min/4096 项；模型整体 20s、合成整体 5s；单模型与单合成并发；礼物合并窗口 150ms；欢迎忙时跳过。后续可由集成窗口增加正式配置字段。
- GIFT.quantity 按 event-schema 已定义为增量，后端不会再次将其当累计值相减；累计转增量由采集器负责。后端按事件 ID 去重，按用户/giftId/comboId 合并增量。
- 不新增依赖。DeepSeek 使用现有 Spring AI；真实 TTS 供应商、凭据安全存储/轮换接口待选型。第一版只生成确定性的 WAV 测试音，不宣称是可理解的人声。
