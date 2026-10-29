# 合成采集夹具

events.jsonl 全部为人工构造，未抓取真实用户、房间或礼物。字段是协议适配后的白名单，不含 Cookie、签名、完整 URL 或原始整包。

由 MockCollector 回放。零数量单礼物变成 1；同用户同组累计 1→3 输出 1、2；重复包和终包重复累计被忽略；另一个用户与另一个组各输出 1。异常 JSON 和未知类型后继续处理关注、入场、点赞。

输入 roomId 已是模拟内部 ID；start 的 roomId 只是模拟请求参数，不将网页房间号覆盖到事件。receivedAt 在回放接收时生成。真实与模拟必须通过会话 mode 区分，本夹具不能作为真实协议或真实礼物验收证据。

## 二进制与连接夹具

`WireFixtures` 在测试中独立构造 Protobuf 字节与 gzip 数据，不使用生产编码器，也没有复制上游 proto 或真实抓包。`DouyinProtocolTest` 还以固定十六进制字节核对 ACK 和心跳，覆盖旧 varint / 新数字字符串礼物字段、坏包、未知消息和解压上限。

`JdkCollectorSocketTest` 使用 127.0.0.1 随机端口的 RFC6455 对端，实际经过 Java 21 HttpClient/WebSocket 握手和分片回调，验证 Ping/Pong、二进制 ACK、取消握手、帧大小与入队上限。`DouyinCollectorTest` 注入内存传输做重连/超时故障测试。这两类测试均不连接抖音。

运行采集测试：`./scripts/maven.sh -B -ntp -pl backend '-Dtest=cn.bigfish.collector.*Test' test`。完整回归：`./scripts/maven.sh -B -ntp test`。本机 WebSocket 测试需要允许 loopback 端口，既有 Spring 集成测试需要允许 Mockito 的 JVM agent；权限受限时不要通过跳过断言或禁用测试掩盖失败。
