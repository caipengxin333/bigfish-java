# 第三轮主项目集成

日期：2026-11-01（Asia/Shanghai）。集成工作目录为 `bigfish-java`，分支 `main`。用户授权检查并合并三个已完成的功能分支；本轮不推送 GitHub，不重启用户正在运行的 IDEA 服务。

## 审查与合并

起始 main：`55a6fa04758b86be5550af5fd1c3d07cbc560b3c`，README 整理已提交，工作区干净。

| 分支 | 已审查交付 tip | main 合并提交 |
| --- | --- | --- |
| feature/backend-core | `dea3d9132e92d10ea6d6d06a0c452e4e48a40932` | `3907a55` |
| feature/frontend-stage（Java 语音） | `f0b88eb1042ea7ff707b92fc5de9834f427fa6ea` | `4bcf6c7` |
| feature/douyin-collector | `d7b6fdcc4d44f2837c0a3a51812e0f1b3b499db5` | `81f54c9` |

三个工作树均干净，交付包含起始 main 和 `c8be17c`，diff 格式检查通过。按上述顺序 `--no-ff` 合并，无冲突；未 reset、复制覆盖、删除分支或批量删除文件。语音 PCM→WAV、严格音频检查和回归测试保留。

已检查任务末次交接、生产代码、测试及来源声明。签名核心按固定 revision 的 MIT 来源移植，原声明保留于源码与 SPI 资源；补充根目录 `THIRD_PARTY_NOTICES.md`，打包确认该声明仍在 JAR 中。没有引入旧项目 AGPL 脚本、云凭据或新 POM/npm 依赖。

## 主窗口补齐

1. `ProviderServices` 按显式 Token 模式选择工厂。默认 STATIC 兼容已有 AppKey + Token；RAM 需要 AppKey + AccessKey ID/Secret，不依赖静态 Token，也不在失败后跨模式回退。构造、启动和状态查询不签发 Token，首次实际合成按需获取。配置错误返回固定码，创建失败关闭临时 Handle。
2. `/api/v1/providers` 新增只读 `aliyunToken`，仅返回模式、状态、期限、剩余秒数、刷新结果、固定错误码、尝试/成功时间、退避时间和尝试次数。未输出 Token、AccessKey、签名或原始响应。
3. `GET /api/v1/runtime/diagnostics` 返回当前会话摘要和最近最多 20 条阶段变化；使用独立公共 DTO，不直接暴露内部实现对象。包含等待原因、最近模型/合成/播放成功时间、最近失败、工作线程与容量淘汰计数；没有消息正文、昵称或外部 eventId。
4. Vue 管理页/舞台显示当前阶段和最近成功，管理页可展开最近 8 条阶段记录并查看 Token 状态。按 sessionId/state 匹配快照，操作、失败或新会话时不继续展示旧诊断。OpenAPI、TypeScript、响应校验与测试同步。
5. 抖音供应商原因更新为 `LIVE_RECEIVE_NOT_VERIFIED`，文案说明原生签名和游客采集已实现。主会话 `DOUYIN` 仍明确返回 422，bootstrap 能力仍为 false；没有把本地替身通过当作线上接收通过。独立 probe 与 `WebDouyinConnectionProvider.guest()` 已随合并代码可用，输入规范直播链接/房间号即可进行后续显式验收。
6. README、AGENTS、smoke 脚本与第三方说明更新为当前状态。各工作树后续继续开发前需合入最新 main，保留此接线与对应回归；本轮没有自动联系或修改其他窗口的工作目录。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| 后端 `./scripts/maven.sh -B -ntp test`，Java 21，云调用关闭 | **198 项：196 通过，2 项真实 NLS/Token 测试默认跳过；0 失败/错误**；18:39:50 完成 |
| 首次沙箱内后端测试 | 本地端口与 JVM attach 被限制，69 项环境错误；保留断言，获准在沙箱外完整重跑并通过，不计为代码通过证据 |
| 前端 `npm ci`、`npm test` | 依赖安装成功，**37/37 通过** |
| 前端 `npm run build` | TypeScript 与 Vite 生产构建通过 |
| `./scripts/maven.sh -B -ntp -Pbundle-ui -DskipTests package` | 已完成上面的全量测试后打包成功；没有重复运行相同测试 |
| JAR 内容 | 包含前端新诊断 API 客户端、诊断 DTO、原生 signer SPI 及完整 MIT 声明 |
| 独立 JAR 启动 | 随机端口 62790、独立临时 SQLite/音频目录，无云凭据且显式关闭云调用；启动成功 |
| `scripts/smoke.mjs` | 页面、健康状态、供应商/Token、有限诊断、配置持久化与 CAS 冲突检查通过；测试配置已恢复 |
| 浏览器真实 HTTP/WebSocket 模拟联调 | 管理页启动 MOCK 会话；舞台连接、启用声音、连击回放；评论和礼物各一个 STARTED/COMPLETED，感谢合计 3 个小心心；随后显示当前任务已处理完及三类最近成功时间 |
| 结束与清理 | 页面停止会话并静音，关闭本轮临时标签页，终止本轮 Java PID 48090；未停止用户 IDEA 服务，未删除测试目录 |
| OpenAPI / 文档 | JSON 解析、schema 引用/required 字段、本地链接及 diff 空白检查通过 |

新增接线测试覆盖：默认 STATIC 兼容、显式 RAM 不回退静态 Token、配置缺失/无效模式、关闭云调用、轮询不签发、状态不泄露凭据；诊断容量上限、正文脱敏、新会话重置、真实本机 WebSocket 回执与诊断成功时间的一致性。

本机验证记录（均不提交）：`/tmp/bigfish-phase3-main-test-unrestricted.log`、`/tmp/bigfish-phase3-package.log`、`/tmp/bigfish-phase3-integration.QMteUP/`。浏览器完成画面为该临时目录中的 `stage-completed.jpg`，不作为跨机器运行依赖。

## 仍需真实验收

- 本轮真实 DeepSeek、阿里云 CreateToken/合成、抖音 HTTP/WSS 请求均为 0；用户此前静态 NLS 和 DeepSeek 基本成功结果不被本轮覆盖。
- RAM 云端签发需要用户在后端配置独立 RAM 凭据及匹配权限；现有静态 Token 不会因此自动续期。
- 抖音需用当前正在直播的房间，先做有界只读 probe；评论、礼物、关注、进场分别按实际接收证据报告。没有自然礼物时明确礼物未验证，不主动送礼。游客初始化、页面字段、签名和上游 cursor 等是否被当前平台接受仍未知；本轮不解禁主会话。
- Live2D、OBS 后台播放、真实长时间稳定性未验收。前台播放和现有期限/队列保护继续生效。
- 集成代码仅本地提交；需要发布时，由上传窗口审核新的 main SHA 后普通推送。最终提交 SHA 由主窗口回复报告，不在本文件自引用。
