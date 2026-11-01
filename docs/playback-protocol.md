# 播放协议 v1（已实现，模拟闭环）

同源 WebSocket `/ws/stage`，JSON envelope：`{"schemaVersion":1,"messageId":"唯一ID","type":"...","sessionId":"会话ID","payload":{}}`。

## 建立播放端

客户端先发送 PLAYER_HELLO，payload 为 `{playerId}`。playerId 是本次页面实例的随机 ID，不依赖浏览器共享 localStorage，以免两个标签变成一个播放器。
服务端回复 PLAYER_LEASE：`{playerId, leaseGeneration, expiresAt, heartbeatIntervalMs:5000}`。首个播放端获取租约，第二个收到 PLAYER_REJECTED `{reason:"PLAYER_ALREADY_ACTIVE"}`，不得发声。
客户端每 5 秒 PLAYER_HEARTBEAT，带 playerId / leaseGeneration。服务端每次回 HEARTBEAT_ACK 并续租 15 秒；客户端若租约到期或连接断开立即停止声音、取消待播任务、闭嘴。过期租约不能复活，重连重新握手。所有 ACK 校验当前租约代次。

## 下发任务

服务端 PLAY_TASK，payload 对应 PlaybackTask：taskId、sessionId、configVersion、type、text、audioUrl、priority、expiresAt。
音频 URL 仅同源 `/api/v1/audio/{assetId}`，不下发本地绝对路径。taskId 唯一，页面缓存近期已完成任务 ID，重发不能重复播出。
页面不自行调整优先级，只执行服务端已选出的当前任务；服务端每次最多一个未完成任务，播完收到 ACK 后再选下一个，避免低优先级音频提前堆到浏览器。
AudioContext 在用户点击“开始互动”的直接调用链中尝试激活，随后协调创建/接入会话与握手；浏览器阻止时提供“点击启用声音”补救。只有本地音频可用且租约绑定预期 sessionId，才发送 PLAYER_READY，服务端在此之后下发任务。

## 回执

客户端 PLAYBACK_ACK，payload 对应 PlaybackAck：taskId、sessionId、playerId、leaseGeneration、state（STARTED / COMPLETED / FAILED / CANCELLED）、occurredAt、errorCode（成功为 null）。
STARTED 必须在真正开始播放后发送；COMPLETED 在声音结束后发送。音频下载失败、解码失败、播放被阻止分别给出稳定错误码。
服务端维护开始/结束超时，不靠收到音频就推断已播放。连接丢失后撤销旧播放代次、清空旧音频和待播任务，不重播已经完成、取消或过期任务；恢复后只处理当前有效代次的新工作。

## 取消与状态

服务端 CANCEL_TASK `{taskId,reason}` 和 SESSION_STOPPED `{reason}`；后者要求立即终止全部音频和嘴型。STOPPED 会话的迟到任务/回执忽略。
字幕和 Live2D 嘴型必须由实际播放的音频驱动，不能仅由模型文字到达触发。队列为空、失败、取消和断线时嘴型归零。

第一版可用占位动画验证音量驱动；未经许可不复制旧 Live2D 模型。管理页面只显示状态，不创建有声播放器。

## 当前集成行为

- 初次 PLAYER_HELLO 的 envelope.sessionId 为空字符串；PLAYER_LEASE 返回当前会话 ID。没有运行会话时 SESSION_STOPPED reason=NO_SESSION，客户端应提示先启动会话。
- PLAYER_READY 与 PLAYER_HEARTBEAT payload 都是 `{playerId, leaseGeneration}`，envelope 必须带租约绑定的 sessionId。HEARTBEAT_ACK 返回同样两字段及 expiresAt。
- “开始互动”协调音频解锁、会话与租约；已有活动会话直接接入，不重复创建。不确定的启动结果只查询，不盲目重发 POST。已停止/改变的会话不能通过“恢复播放”重新创建。
- 服务端 STARTED 等待上限 5 秒；开始后完成期限为实际音频 duration + 5 秒，最多 30 秒。主会话只接收正时长且不超过 25 秒的音频，测试音 300ms。下发前还须有 duration + 10 秒以上剩余 TTL，预留开始和完成的等待预算。前端 120 秒是防御上限，不表示主会话允许该长度。
- 合成文件在 AudioAssets 读取到受限内存后，由 SpeechPort.release 单文件释放；同源音频字节保留到完成回执、过期或取消。LocalTestSpeech 可复用测试音，不执行删除。
- 普通 hidden 不主动断开；冻结、pagehide、音频中断、断线或租约失效时静音并关闭播放连接。协议没有 NOT_READY 消息，音频中断时通过断开使服务端撤销 ready，避免继续生成无人播放的任务。可见时核对租约及目标会话，失效后手动恢复。心跳不依赖动画帧，STARTED 依据实际音频时钟；仍不保证系统休眠、长期后台或 OBS 可用。
- 旧传输尚未关闭且租约未到期时保持独占隔离，即使 runtime.player.connected=false，另一个播放端也可能暂时收到 PLAYER_ALREADY_ACTIVE。客户端应等待释放，不抢占或无限放宽租约。
- 浏览器本地 transport=mock 与 Java MOCK 会话是不同测试层：联调使用默认真实 HTTP/WebSocket 接口，Java 仍显式选择 MOCK 数据源。
