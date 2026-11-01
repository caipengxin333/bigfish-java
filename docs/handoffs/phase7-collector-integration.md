# 主集成：抖音游客房间初始化修复

日期：2026-11-01（Asia/Shanghai）。目录 `bigfish-java`，分支 `main`。

## 合并与范围

- main 基线 `5d93ed5`；实际新成果在 `feature/douyin-collector`，tip `efae277`，实现提交 `2129a37`。`feature/backend-core` 没有新的待合并提交。
- 主目录、后端和采集工作树均干净；采集分支包含最新 main。以 `git merge --no-ff feature/douyin-collector` 合并，提交 `9d49b11`，无冲突。
- 生产改动仅 `DouyinGuestSession`、`DouyinRoomPage`：固定页面兼容 UA、跳过确切空初始 roomInfo、将游客字段遍历深度与 JSON 预算统一为 64。
- 保留 2 MiB 限制、重复/冲突数据拒绝、明确游客字段要求、验证页分类、签名与房间一致性、取消/资源释放约束。新夹具全部为人工数据。
- 主窗口仅统一 README、AGENTS、架构与采集文档的当前状态，并记录本交接；没有新增公共字段或依赖，没有修改前端、会话、模型或语音实现。

## 主窗口实际验证

| 验证 | 结果 |
| --- | --- |
| `./scripts/maven.sh -B -ntp test`，JDK 21 | 237 项，235 通过、2 项真实 NLS/Token 默认跳过，0 失败/错误 |
| 其中采集模块 | 83/83 通过；包含新页面夹具、游客初始化、原生签名、JDK 本机 WSS、事件/ACK/心跳与退出测试 |
| `-Pbundle-ui -DskipTests package` | 完整测试通过后打包成功 |
| JAR 核对 | 包含新页面兼容 UA 的采集类；静态 index 与本地 dist 一致 |
| 差异检查 | `git diff --check` 通过 |

本机日志 `/tmp/bigfish-room-main-test.log`、`/tmp/bigfish-room-main-package.log`。测试需要本机临时端口及 JVM attach，按权限流程在沙箱外执行；均为本地替身。本轮没有新增真实抖音或云服务请求。前端无改动，沿用第六轮 101 项通过的基线，不将此前测试声称为本轮重跑。

## 采集窗口实测证据与未完成项

主窗口核对了采集交接及本机日志 `/tmp/bigfish-room-final-resolve.log`、`/tmp/bigfish-room-receive.log`，没有自行再次联网探测。

- 测试房间 `440132407257`：resolve 为 LIVE / roomResolved=true，HTTP 2 次，2307 ms。
- 随后一次 60 秒 receive：HTTP 2 次、签名 1 次、握手尝试/连接成功各 1 次；停止前 CONNECTED，停止后 STOPPED。
- COMMENT/GIFT/FOLLOW/ENTRANCE/LIKE 均 0，ACK 0，心跳 6，MALFORMED 92，重连 0。接收验收未通过，不能推断是房间没有评论或礼物。
- 采集窗口记录停止后资源计数归零。此证据只属于当时指定房间，不能保证现在仍在播或推广为所有房间兼容。

**本轮修复页面初始化，没有修复入站消息解码。** `UNVERIFIED / LIVE_RECEIVE_NOT_VERIFIED` 的能力说明应继续保留；CONNECTED、签名成功或本地测试通过均不等于实际评论/礼物接收通过。下一项由采集窗口定位 MALFORMED 的封装、压缩或字段差异，用最小脱敏结构补回归；不能在没有证据时断言具体协议原因。

## 运行与继续开发

- 用户当前 IDEA 后端未重启，需重启才能加载本次 Java 类；使用 JAR 时改为运行本次打包产物。未修改房间配置、密钥、数据库，也未停止用户服务或 Luna。
- 采集窗口继续前先在干净工作区合入最新 main，保留页面初始化与已有主窗口修复；最终文档提交 SHA 由主窗口回复提供。
- 只有有效自然评论、礼物事件成功解码并进入下游，才能分别增加对应验收结论；本轮没有主会话真实弹幕 → 模型 → 人声 → 舞台的通过证据。
- 没有推送 GitHub，没有自动联系其他窗口，没有批量删除文件。
