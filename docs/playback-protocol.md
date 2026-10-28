# 播放协议 v1（已约定，待实现）

同源 WebSocket `/ws/stage`，JSON envelope：`{"schemaVersion":1,"messageId":"唯一ID","type":"...","sessionId":"会话ID","payload":{}}`。

## 建立播放端

客户端先发送 PLAYER_HELLO，payload 为 `{playerId}`。playerId 是本次页面实例的随机 ID，不依赖浏览器共享 localStorage，以免两个标签变成一个播放器。
服务端回复 PLAYER_LEASE：`{playerId, leaseGeneration, expiresAt, heartbeatIntervalMs:5000}`。首个播放端获取租约，第二个收到 PLAYER_REJECTED `{reason:"PLAYER_ALREADY_ACTIVE"}`，不得发声。
客户端每 5 秒 PLAYER_HEARTBEAT，带 playerId / leaseGeneration。服务端每次回 HEARTBEAT_ACK 并续租 15 秒；客户端若租约到期或连接断开立即停止声音、取消待播任务、闭嘴。过期租约不能复活，重连重新握手。所有 ACK 校验当前租约代次。

## 下发任务

服务端 PLAY_TASK，payload 对应 PlaybackTask：taskId、sessionId、configVersion、type、text、audioUrl、priority、expiresAt。
音频 URL 仅同源 `/api/v1/audio/{assetId}`，不下发本地绝对路径。taskId 唯一，页面缓存近期已完成任务 ID，重发不能重复播出。
页面不自行调整优先级，只执行服务端已选出的当前任务；服务端每次最多一个未完成任务，播完收到 ACK 后再选下一个，避免低优先级音频提前堆到浏览器。
AudioContext 在用户点击“启用声音”后激活；服务端须等 PLAYER_READY 才下发，禁止把浏览器自动播放限制当 TTS 成功。

## 回执

客户端 PLAYBACK_ACK，payload 对应 PlaybackAck：taskId、sessionId、playerId、leaseGeneration、state（STARTED / COMPLETED / FAILED / CANCELLED）、occurredAt、errorCode（成功为 null）。
STARTED 必须在真正开始播放后发送；COMPLETED 在声音结束后发送。音频下载失败、解码失败、播放被阻止分别给出稳定错误码。
服务端维护开始/结束超时，不靠收到音频就推断已播放。连接丢失后，已经 STARTED 的任务默认不重播，避免重复感谢；尚未开始的任务根据 TTL 与取消状态决定是否重派。

## 取消与状态

服务端 CANCEL_TASK `{taskId,reason}` 和 SESSION_STOPPED `{reason}`；后者要求立即终止全部音频和嘴型。STOPPED 会话的迟到任务/回执忽略。
字幕和 Live2D 嘴型必须由实际播放的音频驱动，不能仅由模型文字到达触发。队列为空、失败、取消和断线时嘴型归零。

第一版可用占位动画验证音量驱动；未经许可不复制旧 Live2D 模型。管理页面只显示状态，不创建有声播放器。
