# 第二轮：真实模型、阿里云语音和抖音接入

本轮用户请求：为另外三个现有窗口写好任务。语音供应商已由用户明确选择阿里云官方服务。
行为基线：main 的 49a7865，已验证 Java 67 项 / 前端 34 项；当前默认 MOCK 数据源 + MOCK 回复 + 300ms 测试音。
本文件定义第二轮职责，优先于第一轮 prompts 的宽泛目录授权；没有变更原有 Git 分支名称或工作树。

## 分工

| 当前窗口 | 第二轮职责 | 原工作树 / 分支 | 后端 / Vue 端口 |
|---|---|---|---|
| 主集成窗口 | 配置页面、公共 API/DTO、会话/播放接线、跨模块验收 | bigfish-java / main | 18080 / 15173 |
| 原后端窗口 | 真实 DeepSeek 适配与独立验收 | backend / feature/backend-core | 18081 / 15174 |
| 原前端窗口 | Java 阿里云 NLS 语音适配与独立验收 | frontend / feature/frontend-stage | 18082 / 15175 |
| 原采集窗口 | 抖音房间解析、连接提供器与真实接收验证 | collector / feature/douyin-collector | 18083 / 15176 |

原前端窗口这轮改做 Java TTS。目录/分支名字只是历史标签，里面仍是完整项目；不要误以为只能编写 Vue，也不需要创建新目录。配置 UI 统一留给当前主窗口，在服务契约确认后处理。

## 独占修改范围

- DeepSeek：`backend/src/main/java/cn/bigfish/conversation/**`；`backend/src/test/java/cn/bigfish/core/DeepSeek*Test.java`；新增 `backend/src/test/java/cn/bigfish/conversation/**`；`backend/src/test/resources/conversation/**`；`docs/handoffs/phase2-deepseek.md`、`docs/providers/deepseek.md`。
- 阿里云语音：`backend/src/main/java/cn/bigfish/speech/**`；`backend/src/test/java/cn/bigfish/speech/**`；`backend/src/test/resources/speech/**`；`docs/handoffs/phase2-speech.md`、`docs/providers/aliyun-nls.md`。不修改 Vue，也不修改原后端窗口的测试或配置类。
- 抖音采集：`backend/src/main/java/cn/bigfish/collector/**`；`backend/src/test/java/cn/bigfish/collector/**`；`backend/src/test/resources/collector/**`；`docs/handoffs/phase2-collector.md`、`docs/providers/douyin.md`。
- 集成窗口保留其余目录，特别是 contract、CoreConfiguration、RuntimeEngine、SessionService、ManagementController、AudioAssets、PlaybackCoordinator、前端、脚本、依赖及所有公共契约。

三个实现窗口都不自行发布新的管理 REST 接口，不直接替换主会话 Bean。按既有 ConversationPort / SpeechPort / CollectorPort 交付适配器、工厂与独立演示；把工厂调用签名、关闭方法、异常类型写清楚，主窗口接入。DeepSeek 已有正式会话入口，可在自己工作树用原 API 验证；TTS/真实采集暂用显式启用的独立验收入口。

## 配置与凭据决策

- 本轮先沿用后端环境变量作为凭据来源，不实现不完整的网页保存 Key 功能，也不把凭据加入 RuntimeSettings/SQLite 明文业务配置。
- DeepSeek 保留 BIGFISH_DEEPSEEK_API_KEY / BIGFISH_ALLOW_PAID；模型名称来自配置快照，不靠修改前端字段就假装已切换到真实模式。
- 阿里云首选 NLS 短文本 TTS REST。下列为本轮拟实现命名，当前基线尚不读取：BIGFISH_ALIYUN_NLS_APP_KEY / BIGFISH_ALIYUN_NLS_TOKEN；地区、刷新所需凭据和开关由语音任务文档列明。不要把 NLS AppKey/Token 与百炼 DashScope API Key 混为一套。
- 真实请求须由明确的供应商选择、凭据就绪和显式启用共同控制，默认仍可无 Key 启动。短请求冒烟每个服务最多 3 次，失败不无限重试，不自动新建/购买云服务。更长收费压测另列计划与预算，不自动执行。
- 用户在对应窗口本机设置凭据，禁止把完整 Key/Token 粘贴进任务文本。缺少凭据不会阻止本地替身、超时取消、音频格式和协议故障测试。
- 当前配置页面新增模式选择、供应商就绪状态、模型/音色校验与连接测试由集成窗口后续实现；API Key 网页保存涉及独立安全存储决策，不在这三份适配器任务里偷偷加入。

## 集成前需要统一的变化

1. StartSessionRequest 增加独立 speechMode，兼容现有请求；RuntimeView 如实报告真实供应商与就绪状态，不能只根据 Bean 存在就宣布可用。
2. DOUYIN 房间网页号 → 内部 roomId → 会话生命周期接线，旧会话/关播/停止及时取消。
3. 当前 SpeechPort 输出文件，AudioAssets 只接受本实例 audio 下 WAV、最大 2,000,000 字节；真实合成需要单文件生命周期释放，避免磁盘增长。
4. 当前合成整体期限 5 秒、STARTED 等待 5 秒、播放完成等待 30 秒、前端最大音频 120 秒；接入真实人声时按实际 duration、任务 TTL 与有限上限一起调整，禁止只在一个模块无限延长超时。
5. 音色字段目前为 Edge 音色标识，不能直接传给阿里云。供应商切换时要校验并展示对应音色。模型字段也需校验真实可用性；未验证状态不能标为连接成功。

这些是主窗口待实现的集成事项，不是声称现有接口已经支持。各任务先输出明确内部工厂/调用示例和提案，不擅自改公共协议。

## 执行与验收顺序

三个窗口先合入 main，再并行完成适配器。各任务完成后，主窗口先统一配置契约和页面，再接 DeepSeek、阿里云语音，最后接抖音；先用模拟事件验证真实语音，再用真实直播消息贯通。

验收分层标注：代码/本地替身通过、供应商真实短请求通过、跨模块联调通过、长期稳定性通过。任何层没做就标为未验证。长期验收目标是覆盖原先 20 分钟左右沉默场景，检查超时/限频/Token 失效/断线后可恢复；本轮不默认触发 30 分钟付费调用。

官方参考（查阅于 2026-10-30）：
- DeepSeek Chat Completions：https://api-docs.deepseek.com/api/create-chat-completion/
- DeepSeek 模型列表：https://api-docs.deepseek.com/api/list-models/
- 阿里云 NLS REST TTS：https://help.aliyun.com/zh/isi/user-guide/restful-api-3
实际实现时再核对当前文档；不要照抄官方旧示例中的过期依赖版本或打印凭据的日志方式。
