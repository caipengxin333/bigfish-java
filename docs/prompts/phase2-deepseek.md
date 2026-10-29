# 第二轮任务一：真实 DeepSeek

你在原后端窗口继续，完成真实 DeepSeek 的可用性、故障恢复和独立验收，不重新搭框架。

工作目录：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/backend`
分支：`feature/backend-core`；端口 18081 / 15174。
主目录 `/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-java` 只用于读取交接，不在那里修改。

## 开工与约束

1. 先检查 `pwd`、`git branch --show-current`、`git status --short`。保留已有改动；若有未提交修改，先辨认来源，不自动 reset、stash 或覆盖。
2. 工作区干净后执行 `git merge --no-edit main`，将主项目已完成的合并、接口和本轮文档带入本分支。这里允许合入 main，不允许合入另外两个功能分支；有冲突时逐项检查，不能全部选一方。
3. 阅读 AGENTS.md、README.md、docs/task-boundaries.md、docs/phase2-plan.md、docs/openapi.yaml、docs/event-schema.md、docs/playback-protocol.md，以及本任务文件。已集成的行为基线是 49a7865；本轮从包含此任务文档的 main 继续，不能回到旧第一版重新实现。
4. 每一步改代码前简述本步内容，完成后报告改动和验证。禁止批量/递归删除，禁止回滚其他窗口改动。只在本任务允许的目录修改、测试并提交。
5. 不修改主项目 bigfish-java 或旧 AI-Vtuber；不读取/复制旧 config.json、Cookie 或密钥。只检查本任务指定的环境变量是否存在，不打印其值或全部环境变量。密钥不进聊天、Git、日志、夹具、命令行参数或前端响应。
6. 使用本工作树的 runtime/.cache 和端口；不能连接或停止 main/其他窗口的服务。不得自行创建新窗口/工作树、联系其他任务、推送、建 PR。
7. 公共 DTO、OpenAPI、依赖/POM、会话服务、引擎接线和 Vue 页面由集成窗口负责。本任务遇到跨模块变更先写本任务 handoff 的具体提案；同时继续适配器、独立测试与使用说明，不因等待 UI 或真实房间就停掉所有工作。
8. 默认自动化测试只能访问本地替身；真实验证须单独显式启用。用户要求接入真实服务，但缺少凭据/房间/权限时不能伪造成功：向用户说明缺少哪一项及如何在本机配置，并继续不依赖该项的工作。不要反复请求同一信息。
9. 最后创建本地提交，在 handoff 中记录基线/实现 SHA、修改文件、测试命令和结果、真实验证证据（脱敏）、未完成事项、公共接口/依赖提案和供集成调用的最小示例。最终回复给出 SHA 与 handoff 路径。由主窗口统一合并，不自行更新 main。

## 本任务范围

仅修改 docs/phase2-plan.md 的 DeepSeek 独占目录；不改 speech、collector、配置/会话/播放引擎、Vue、公共 DTO 或 POM。
现有实现是 DeepSeekConversation（Spring AI OpenAiApi）+ ConfigSnapshot + ConversationPort。已有整体超时、取消与本地 SSE 测试，保留这些能力并检查真实协议兼容性。

## 要完成的工作

1. 核对 DeepSeek 官方最新 Chat Completions 与模型列表文档。实现有界的模型发现/校验能力和可供主窗口调用的连接检测方法；鉴权成功、模型存在、真实生成成功是不同验证结果。不要只检查 Key 非空就宣称通过，不硬编码猜测的模型名称。
2. 保留模型、人设、maxOutputTokens 从配置快照读取。流式输出只取面向观众的回答；若供应商返回思考内容，不将其直接播出。单任务输出有上限，不能被持续 token/keep-alive 无限延长。
3. 确保停止/取消/整体超时关闭实际 HTTP/SSE 资源，不只是取消 future；迟到回调不能送入旧会话。避免锁顺序导致取消与完成互相等待。
4. 区分鉴权失败、余额/权限不足、未知模型、429、5xx、连接/读取/整体超时、取消和输出格式错误，用固定脱敏错误码/耗时给集成层；不能把所有错误只呈现为无原因沉默。有限重试必须受同一个总截止时间限制，鉴权/模型错误不重试；不重复生成已成功的回复。
5. 凭据继续从服务端环境变量 BIGFISH_DEEPSEEK_API_KEY 获取，BIGFISH_ALLOW_PAID=true 才允许显式真实请求。无 Key 正常启动，默认 MOCK 不调用网络。不要读取其他项目的 Key。
6. 提供安全的独立验收方式：先明确检查当前模型，再发最多 3 条短测试文本（每条 max_tokens 不超过 256）；API 入口可用现有 POST /sessions 的 mode=MOCK、conversationMode=DEEPSEEK。当前测试音不会因为真实文字就变成人声；本任务只验收真实回复。没有凭据时完成全部替身测试并准确标记真实请求未验证。
7. 输出 docs/providers/deepseek.md，包含环境变量、IDEA 设置步骤（仅占位符）、调用示例、模型发现策略、错误码、重试/取消策略与集成工厂签名；前端状态/连接检测 REST 需要的字段写进 handoff 提案，不自行增加公共 API。

## 必须验证

- 原 DeepSeek/SSE 测试与后端全套测试不退化；新增鉴权、未知模型、429/5xx、半截流、长 keep-alive、持续输出、取消和下次请求恢复的故障测试。
- 切换配置后新任务用新模型，旧任务仍保留旧快照；测试不得真实联网或泄露凭据。
- 如果真实请求成功，记录日期、模型 ID、请求次数、耗时范围和结果类别，不记录密钥或完整用户对话。不要把 GET /models 成功等同于生成成功。
- 最终运行 `./scripts/maven.sh -B -ntp test`，记录失败/跳过原因，不删断言。交接到 docs/handoffs/phase2-deepseek.md。

参考：https://api-docs.deepseek.com/api/create-chat-completion/ 、https://api-docs.deepseek.com/api/list-models/ 。
