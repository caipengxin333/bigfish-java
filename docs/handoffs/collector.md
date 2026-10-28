# Java 抖音消息采集器交接

任务/分支：抖音采集 / feature/douyin-collector。

基线：a3ac3acdcef2f185df39e0dc6d7819a36b0628f1。工作目录仅 collector worktree；原项目只读参考，不修改主集成目录。

## 当前实现阶段

- 独立 CollectorPort mock：合成 JSONL 回放、v1 事件白名单、单礼物至少 1、按房间/用户/礼物/组累计转增量、稳定消息 ID 去重。
- 缓存和异步派发队列有 TTL 与容量；异常消息/业务回调隔离；ENDED 与幂等 stop；无 Spring 自动注册，不改变骨架 API 能力声明。
- 夹具全部人工构造。用户确认目前没有房间号，真实评论、礼物、心跳及关播未验证。
- 继续实现协议/传输适配和故障注入验证；最终提交与测试结果将在完成时补充。

## 依赖和许可证记录（2026-10-29）

本轮不修改公共 POM、不新增运行依赖、不复制第三方脚本、proto 文件或生成代码。以下是技术调查，不是对分发权的保证。

| 组件 | 版本/来源 | 许可证与本轮处理 |
|---|---|---|
| Java HTTP/WebSocket、gzip、SHA-256 | 项目 Java 21；测试主机 Oracle JDK 21.0.11 | 使用 JDK API，不把 JDK 二进制打进仓库；交付 JRE 的发行版与许可由集成决定 |
| Jackson | 现有 Boot BOM 2.19.4 | Apache-2.0；仅使用现有依赖读取合成夹具 |
| DouyinLiveWebFetcher | 2026-10-29 在线查阅 main（非固定发布版）；旧项目来源记录抓取于 2026-10-27 | 仓库标注 AGPL-3.0，README 另有非商业研究声明；不直接复用或转译签名、连接代码和 proto |
| 旧项目 douyin_protocol.py / sign.js | 只读旧项目 HEAD 0989f57191b180787e8dac66b5686106e134cf22，vendor/douyin_sign/SOURCE.md | 本地记录前者 AGPL-3.0-or-later、后者上游 AGPL-3.0；不复制、不执行 |
| mini-racer | 旧项目和上游固定 0.12.4 | Python/V8 路径，本 Java 任务不引入；引擎许可不替代脚本许可 |
| protobuf-java / protoc（候选） | Java 4.31.1 / protoc 31.1，仅作候选调查，不是要求升级到此旧版本 | [BSD-3-Clause](https://github.com/protocolbuffers/protobuf/blob/v31.1/LICENSE)；若采用生成式解析，集成统一选支持版本、审查 schema 来源后更新 POM |
| GraalJS（候选） | 暂不选版本、不申请加入 POM | [引擎源代码 UPL-1.0](https://github.com/oracle/graaljs/blob/master/LICENSE)；具体 Maven 发行物/传递依赖另审。仅在有明确授权签名源码时评估 |

来源：[上游仓库和声明](https://github.com/saermart/DouyinLiveWebFetcher)、[上游运行依赖](https://github.com/saermart/DouyinLiveWebFetcher/blob/main/requirements.txt)、[JDK 21 WebSocket API](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/WebSocket.html)、[Protobuf 官方 wire format](https://protobuf.dev/programming-guides/encoding/)。

纯 Java 的网络、gzip、有限字段 wire 解析、确认、心跳、重连可行；签名不是 JDK 自带功能，当前没有获准复用且经真实验证的实现。Java 调 JS 技术上可行，但引入 GraalJS/Node 不消除签名脚本许可边界，也需要执行超时、内存限制和禁用宿主/文件/网络访问。不能把 AGPL 脚本移到子进程就宣称可以闭源分发。本轮保留连接参数提供接口，默认不连接真实平台，不自动回退到模拟。

## 协议研究范围

上游当前实现显示：网页房间号需要解析为内部 roomId，Cookie/浏览器信息参与连接；WebSocket 使用签名连接参数；gzip 后得到 Response，needAck 对应 logId/internalExt 的确认；另有心跳和关播控制消息。这里仅将必要字段号/消息行为作为兼容性调查事实，不复制上游算法表达；上游并非官方稳定协议。线上有效性须重新验证。[调查来源](https://github.com/saermart/DouyinLiveWebFetcher/blob/main/liveMan.py)

旧本地 proto 的礼物 repeat/group/combo 为 varint，上游当前 main 声明为数字字符串；解析应只对白名单计数字段支持两种 wire 编码，不能据此宣称任何线上版本均兼容。礼物金额不查旧项目价格表，不换算人民币/主播收入，缺字段时输出 null。[字段调查](https://github.com/saermart/DouyinLiveWebFetcher/blob/main/douyin.proto)

## 公共接口提案

1. 后端显式创建 MOCK / DOUYIN 会话，展示真实 mode；不可仅凭类存在把 bootstrap.douyinCollector 改成 true。
2. CollectorPort 的 Consumer sink 无法返回 EventIngress.Result；当前一次性派发，不重试，结果统计需由后端包装 EventIngress.accept。若需采集器统计 REJECTED_FULL，提议后续由集成调整接口。
3. 房间解析/签名实现与安全凭据提供方式由集成确定；本轮不增加 API 字段，不从旧 config.json/Cookie 文件读取凭据。
