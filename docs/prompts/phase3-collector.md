# 第三轮任务三：让真实抖音采集可验证地工作

你继续负责抖音采集。当前房间解析、WebSocket、ACK/心跳、归一化、重连已有实现，但没有可用 DouyinSigner，主会话仍返回 SIGNER_NOT_IMPLEMENTED。本轮集中解决这个缺口，不再交付一份新空接口或仅模拟成功。

工作目录：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/collector`。
分支：`feature/douyin-collector`，端口 18083/15176。

## 开始前

检查目录、分支与 git status，保护现有修改；工作区干净后 `git merge --no-edit main`。阅读最新 AGENTS.md、docs/phase3-plan.md、docs/handoffs/phase2-collector.md、docs/providers/douyin.md、docs/event-schema.md 和本文件。每步修改前说明动作，完成后说明改动与验证。禁止批量删除，不改旧 Luna 或主工作树。

## 任务

1. 先审计当前 DouyinSigner、WebDouyinConnectionProvider、DouyinCollectorProbe 和 ServiceLoader 入口。调查官方当前开放能力与适用账户，并检查近期可用的网页协议开源实现；区分官方接口和非官方采集，不能把网页可访问等同于稳定开放 API。
2. 输出最多 3 个具体接入候选：项目/官方 URL、精确 revision、许可证、最近可验证的行为、Java/本地运行依赖、如何对接当前接口。优先选择可合法纳入当前仓库、可直接维护的实现，尽快落到一个方案；不能无限泛搜而不交付。
3. 在来源与使用条件明确的前提下，实现真实可用 signer/provider，重连时重新获取短期参数，不使用固定过期签名。优先沿用 Java 实现；若确需 Node/浏览器辅助进程，先给出最小集成与依赖提案、超时/生命周期/许可证说明，不能未经说明变成另一套长期后台服务。
4. 不复制旧 AI-Vtuber 的 AGPL sign.js、模型或未经许可的采集代码；不能因改名、包装成进程就声称许可证要求消失。若所有可行来源都需要用户决定许可或运行方式，列出具体阻塞证据和可选方案，继续完成其余验证；签名未实现就保持不可用，不能伪报完成。
5. 完善连接失败、签名过期、Cookie 失效、登录要求、房间未开播、平台结构变化的诊断。凭据仅从后端本地注入，不进 URL 日志/错误正文/截图/测试夹具，不绕过登录、验证码或访问限制，遇到这些条件交给用户处理。
6. 使用本工作树显式启用的只读 probe 做端到端接收：当前有效直播间 → 实际业务帧 → COMMENT/GIFT/FOLLOW/ENTRANCE 归一化 → 本地事件接收器。不能只以握手或 HTTP 200 算成功；别把此前房间号当作今天仍在播，需要时向用户一次询问当前链接，继续其他独立工作。
7. 重点验收礼物累计转增量、同用户连击、重复终包、不同用户隔离；自然出现真实礼物时记录是否成功，未观察到则标注未验证。不主动发评论、点赞、关注、购买礼物，也不调用 DeepSeek/TTS 制造费用。
8. 验证停止握手/接收/重连的取消、退避、资源释放、关播 ENDED、内部 roomId 改变，非法消息不拖垮连接；不能频繁轮询或绕过平台限制。

## 范围与交付

只修改 collector 包、对应 collector 测试/最小脱敏夹具、docs/providers/douyin.md、docs/handoffs/phase3-collector.md。ServiceLoader 描述文件若确需新增，仅限 `backend/src/main/resources/META-INF/services/cn.bigfish.collector.DouyinSigner`，在 handoff 显式列明；其他公共配置/POM/DTO/SessionService/Vue 留主窗口处理。

默认测试不联网；运行 `./scripts/maven.sh -B -ntp test`。真实 probe 显式开启，先限制 60–120 秒；若没有礼物无需自动延长等待或送礼。缺凭据/房间时完成本地签名/协议/故障测试，不将其当真实证据。

交付前同步 main、创建本地提交，并在 handoff 分别报告：来源与许可、签名实现、房间解析、真实评论、真实礼物、重连、关播、资源释放、主会话接线所需工厂与参数。注明实际请求次数/时长、命令结果、缺少条件；不要提交真实昵称/UID/完整帧、Cookie、签名 URL。

不能自行把主项目 douyinCollector 状态改为 true，也不能替主窗口修改共享接口。不合并其他功能分支、不更新 main、不推 GitHub、不自动联系其他窗口。最终报告 SHA 和交接路径。
