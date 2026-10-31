# 框架基线验证记录

日期：2026-10-28。本文件记录骨架交付时的实测，不代表后续业务模块已完成。

## 通过

- Java 21.0.11、Maven 3.9.11、Node 24.16.0。
- `./scripts/maven.sh -B -U -Pbundle-ui package`：BUILD SUCCESS。
- 5 项 Spring Boot + SQLite 集成测试：0 failure / 0 error / 0 skip。
  - 配置读取、写入后再次读取。
  - 旧 expectedVersion 返回 409。
  - 队列容量等非法值拒绝。
  - 未实现能力明确返回 false。
  - 未知字段、缺失 boolean、null 数字拒绝。
- `cd frontend && npm run build`：vue-tsc 与 Vite production build 通过，锁文件已保存。
- `bash -n scripts/*.sh`：逐个脚本语法检查通过（实际执行时列举明确文件路径）。
- `./scripts/run.sh`：合并后的单 JAR 在 127.0.0.1:18080 成功启动。
- `node scripts/smoke.mjs`：health、bootstrap、/admin、/stage、保存/回读、409 全部通过，测试恢复原设置。
- 浏览器查看 /admin：服务已连接，配置 v3，初始配置可见，未实现功能为待接入。

## 运行产物

- JAR：backend/target/bigfish-server-0.1.0-SNAPSHOT.jar（忽略，不提交）。
- 数据库：runtime/app.db（忽略，不提交）。
- 日志：.cache/backend-build.log、.cache/package-build.log、.cache/server.log（忽略，不提交）。
- 前端构建：frontend/dist（忽略，不提交）。

## 环境问题及处理

原 JAVA_HOME 对 Maven 无效，脚本在 macOS 上定位 Java 21。全局 Maven 使用旧 HTTP 镜像与系统目录缓存，本项目改用独立 HTTPS settings 和 .cache/maven，不改全局配置。一次下载 TLS 握手中断后重试成功，未关闭证书验证。本机端口监听/访问及下载在沙箱外获准执行。

## 未验证 / 未实现

没有接入真实抖音、模型或 TTS；没有调用付费服务；没有真实 Live2D 模型；播放租约和 WebSocket 协议仅有约定。没有做长时间直播测试。三个子任务尚未开始，也未自动创建任何 Codex 对话。


## 三分支集成 · 2026-10-30

- 主分支合并：后端 `4a866ec`、前端 `ed46713`、采集器 `0135637`，均为 --no-ff，未冲突；三个开发工作树保持原分支和提交。
- 公共契约：StartSessionRequest、RuntimeView、OpenAPI；实现 sessions/start/stop、runtime、events/simulate、session/replay。DOUYIN 返回 422；未明确开启服务器付费开关的 DEEPSEEK 返回 503，不回退为假成功。
- MockCollector 生命周期绑定 SessionService；明确发起回放才生成测试消息；累计 1→3→3 输出增量 1+2、终包去重，后端短窗合并感谢 3 个。
- Java 完整联合回归 67 项，0 失败/错误/跳过（包含真实 loopback HTTP/WebSocket、音频下载、独占租约、READY 门禁、断线隔离、停止撤销、旧 ID 不影响新会话）。所有模型网络验证为本地 SSE 替身，无真实模型费用。
- Vue 新增 runtime API 验证、明确模式、会话冲突、事件拒绝、超时不自动重试，以及 NO_SESSION 立即提示的回归；最终 34 项全部通过。
- `npm run build` 和 `maven -Pbundle-ui -DskipTests package` 通过；打包前已完整执行测试，不在打包阶段重复运行。
- 单 JAR 18080：浏览器从管理页启动模拟会话，舞台 HELLO/LEASE/READY 成功；礼物字幕“谢谢模拟观众送来的1个小心心！”、评论字幕带 [模拟回复]。实际 Web Audio 时钟触发 STARTED/COMPLETED，观察到 RMS 音量 3%/11%，回放完成后归零。
- 连击回放经 Java 采集器完成两段任务（评论 + 合并礼物），浏览器分别记录 STARTED/COMPLETED；数量 3 的断言在 HTTP+WebSocket 联合测试中验证。
- Vue 独立 15173 → Java 18080：HTTP 会话、WS 同源租约、音频代理实际通过；未放宽后端 Origin 规则。
- `node scripts/smoke.mjs` 通过：health、能力声明、打包页面、SQLite 配置更新及 409；原配置内容已恢复，版本正常递增。
- 沙箱禁止 loopback bind/connect 与 JVM attach：首次测试/启动/HTTP 检查受限；按权限流程在沙箱外重试通过，没有关闭 TLS、修改全局网络或削弱断言。
- 尚未验收真实抖音评论/礼物、真实 DeepSeek、人声 TTS、OBS/后台持续播放及 20 分钟以上稳定性。测试音不是人声，SVG 是原创占位。
- 前端分支交接曾报告现有依赖审计告警，本轮未引入新依赖或修改锁定版本，正式发布前另行统一升级评估。

## 第二轮集成 · 2026-10-31

- 完整记录见 [phase2-integration](handoffs/phase2-integration.md)，该记录说明最新状态；上文是历史阶段结果。
- DeepSeek / 阿里云 NLS 已接入主会话、管理页面、状态与错误接口；真实抖音仍缺 signer，未启用。
- Java 136 项：135 通过、1 项真实凭据测试跳过；前端 36 项通过；前端构建和单 JAR 打包通过。
- 浏览器真实 HTTP/WebSocket + MOCK 事件 + 本地测试音验证：礼物/评论字幕、STARTED/COMPLETED、停止撤销通过。SQLite 保存/回读/409 冒烟通过，原配置内容恢复。
- 本轮未配置真实凭据，供应商真实请求 0 次，未验收长期稳定性；不能把本地替身测试视为云服务验收。
