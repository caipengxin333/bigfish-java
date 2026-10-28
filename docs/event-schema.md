# 事件格式 v1

对应 cn.bigfish.contract.LiveEvent。事件不含 Cookie、签名、API Key 或原始整包。平台 ID 全部 string，时间 ISO-8601 UTC。Java Map payload 在入口按 type 校验；不能把它当不受约束的任意业务对象。

| 字段 | 约束 |
|---|---|
| schemaVersion | 固定 1；其他版本拒绝 |
| eventId | 非空；优先平台 msgId；无 msgId 时以稳定业务字段构造，不用每次随机 UUID |
| sessionId | 由后端创建的本次会话 ID；采集器原样带回 |
| roomId | 统一使用平台内部 roomId 字符串；配置页 roomId 是用户输入的网页房间号，由采集器解析 |
| type | COMMENT / GIFT / FOLLOW / ENTRANCE / LIKE |
| userId | 平台用户 ID 字符串；不可用时空字符串，不把昵称当永久身份 |
| username | 展示昵称，允许空，后端输出时做长度/内容处理 |
| receivedAt | UTC 接收时间；使用服务端时间计算队列 TTL，不信任前端提交的时间 |
| payload | 以下按 type 区分 |

COMMENT：`{"content":"你好"}`。
GIFT：`{"giftId":"1","giftName":"小心心","quantity":1,"comboId":"组ID或空字符串","unitValue":1,"valueUnit":"DOUYIN_DIAMOND"}`。
FOLLOW/ENTRANCE：`{}`。
LIKE：`{"count":1}`。

quantity 为正整数增量，由采集器将同组累计值转增量；重复累计值不再次发事件。unitValue 为平台最小单位整数，未知时 null，禁止伪造为 0 金额或换算主播收入。真实礼物与测试礼物需在会话 mode 中区分。

幂等键 `(sessionId,eventId)`；同组连击去重键还需包含用户、giftId、comboId。去重缓存有 TTL 与容量。断线后重发不应重复感谢。采集器只能输出完整合法事件，不懂的消息类型记计数后忽略，不能终止整个连接。

接口边界：CollectorPort 的 sink 调用 EventIngress.accept；必须快速返回。满队列返回 REJECTED_FULL，不允许采集回调等待模型/音频，也不允许无限重试。

回放夹具示例（不连接真实抖音）：

```json
{"schemaVersion":1,"eventId":"fixture-gift-001","sessionId":"mock-session","roomId":"mock-room","type":"GIFT","userId":"mock-user","username":"测试观众","receivedAt":"2026-10-28T12:00:00Z","payload":{"giftId":"1","giftName":"小心心","quantity":1,"comboId":"mock-combo","unitValue":1,"valueUnit":"DOUYIN_DIAMOND"}}
```
