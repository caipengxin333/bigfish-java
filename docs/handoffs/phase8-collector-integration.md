# 主集成：抖音 Protobuf 解码与首次真实评论接收

日期：2026-11-02（Asia/Shanghai）。目录 `bigfish-java`，分支 `main`。本记录更新第七轮的接收结论；历史失败记录保留。

## 合并与审核

- main 基线 `ab9d532`；采集分支 `feature/douyin-collector` 已包含该基线，交付实现 `4bbab80`、交接文档 `77857f6`。主目录与采集工作树检查时均干净。
- 以 `git merge --no-ff feature/douyin-collector` 合并，提交 **`dc49d1a`**，无冲突。
- 修复将 PushFrame 的 `payload_encoding=pb`（Protobuf 序列化）与头部 `compress_type`（gzip/identity 压缩）分开处理；旧实现会在解压前拒绝这种线上有效帧，导致消息和 ACK 都不可达。
- ACK 优先使用头部 `im-internal_ext`，保留 logId、大小与冲突校验及 Response 回退；单条坏消息隔离，不丢弃同批其他有效消息和有效 ACK。保留帧/解压大小、字段数、消息数、队列与 TTL 限制。
- 新内部诊断仅保留固定枚举、长度、数量、字段号和 wire type，计数/摘要均有上限，仅独立 probe 输出；不保存异常对象、原帧或用户信息。没有扩展 REST DTO、依赖或前端。
- 主窗口更新 README、AGENTS、架构及采集文档的当前结论；没有改动会话、模型、语音、签名算法或公共接口。

## 主窗口实际验证

| 验证 | 结果 |
| --- | --- |
| JDK 21，`./scripts/maven.sh -B -ntp test` | **244 项：242 通过，2 项真实 NLS/Token 默认跳过，0 失败/错误**；00:28:41 +08:00 完成 |
| 其中采集模块 | **90/90 通过**；包含独立字节向量、pb/gzip、分片、ACK、坏消息隔离、诊断上限、礼物增量、资源释放等回归 |
| `-Pbundle-ui -DskipTests package` | 完整测试通过后打包成功；00:31:39 +08:00 完成 |
| JAR 核对 | 包含新 `InboundDiagnostics` 和 `DouyinProtocol`，与当前编译类一致；解码类包含 `pb`、`compress_type`、`im-internal_ext`；静态 index 与当前 frontend/dist 一致 |
| 差异检查 | `git diff --check` 通过 |

测试日志 `/tmp/bigfish-decode-main-test.log`；打包日志 `/tmp/bigfish-decode-main-package.log`。后端测试需要本机临时端口及 JVM attach，按权限流程在沙箱外执行；本轮没有新增真实抖音或云服务请求。前端无改动，沿用第六轮 101 项通过的基线，没有把此前测试声称为本轮重跑。

## 采集窗口实测证据与边界

主窗口核对最新 `collector.md` 与本机 `/tmp/bigfish-decode-resolve.log`、`/tmp/bigfish-decode-receive.log`，没有自行重复联网探测。

- 房间 `440132407257`：resolve 为 LIVE、roomResolved=true，HTTP 2 次，2453 ms。
- 随后一次 60 秒 receive（含初始化/握手，停止清理后 60117 ms）：HTTP 2、签名 1、WSS 握手/连接各 1；73 个完整帧成功通过封装、解压与消息列表解析。
- 实际派发 **COMMENT 24、ENTRANCE 46、LIKE 22**，去重 1；ACK 73、心跳 6、MALFORMED 0、重连 0、最后错误为空。停止前 CONNECTED，停止后 STOPPED，采集资源计数归零。
- **GIFT 0、FOLLOW 0**：真实礼物、关注、真实关播、重连及长期运行仍未验证。此结果是独立采集器向本地 sink 的有效接收，不能替代主会话真实弹幕 → DeepSeek → NLS → 舞台的完整验收。
- 已直接确认一个会导致旧实现整批拒绝的线上结构，并验证修复后接收正常；上轮 92 个错误包未保存，不能追溯断言每个历史包都同因。

## 运行与后续协作

- 已生成 `backend/target/bigfish-server-0.1.0-SNAPSHOT.jar`。用户需**重启 IDEA 的 BigfishApplication**，或停止旧 JAR 后运行新产物；刷新前端不能替换旧 Java 解码类。本轮没有停止或重启用户正在使用的后端，也未修改密钥、房间配置、SQLite 或 Luna。
- 保留供应商状态的按会话证据语义：新会话未收到事件时仍可能显示 `UNVERIFIED / LIVE_RECEIVE_NOT_VERIFIED`；实际接受事件后使用既有 `LIVE_EVENT_OBSERVED`。不能把一次独立 probe 的成功硬编码为所有后续会话已验证。
- 后续在当前直播房间观察自然评论，分别检查采集接收/派发、模型、语音和播放回执。礼物需要自然出现时单独验收，不自动发送评论、点赞或购买礼物。
- 实现窗口继续前须在干净工作区合入最新 main，保留本轮序列化/压缩分离、头部 ACK、隐私与有界诊断回归，以及已有主窗口会话/播放修复。
- 仅本地合并和提交；没有推送 GitHub、自动联系其他窗口或批量删除文件。最终文档提交 SHA 由主窗口回复提供。
