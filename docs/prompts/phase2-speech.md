# 第二轮任务二：阿里云官方语音合成（Java）

> 维护更新（2026-11-01）：main 的 `c8be17c` 已修复真实合成的 NLS_INVALID_WAV。开始新工作前先同步 main，再读 docs/handoffs/phase2-speech.md 顶部维护记录。请求必须使用 PCM，由本地按实际长度封装 WAV；保留对应回归测试，不能恢复为直接请求并使用供应商 WAV。

你在原前端窗口继续，但这轮职责改为 Java TTS，Vue 和公共播放接线由主集成窗口负责。工作目录名称 frontend 不限制语言，里面仍是完整仓库；不新建工作树、不换分支、不重复改上一轮页面。

工作目录：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/frontend`
分支沿用：`feature/frontend-stage`；端口 18082 / 15175。
主目录 `/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-java` 不得修改。
用户已明确选择阿里云官方语音合成，不以 Edge-TTS 或本地模型替代本轮交付。

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

仅修改 speech 包、对应 speech 测试/夹具、docs/providers/aliyun-nls.md 和 docs/handoffs/phase2-speech.md。不改 conversation、collector、CoreConfiguration、RuntimeEngine、AudioAssets、播放协议、Vue 或 POM。
实现既有 SpeechPort，保留 LocalTestSpeech。优先 Java 21 HttpClient + 现有 Jackson 调用阿里云 NLS 短文本 REST TTS，请求 PCM 并在本地封装 WAV 输出，避免先引入 Python 服务。若确实需要 SDK，把组件/固定版本/用途/许可证写成依赖提案，由集成统一处理；不要照抄官方旧示例依赖版本。

## 要完成的工作

1. 核对阿里云当前官方 NLS REST TTS 文档、可用音色、地域与凭据。明确 NLS AppKey + Access Token 的含义，不能误用百炼 DashScope API Key。实现真实 AlibabaNlsSpeech（命名可调整）和可供集成调用的工厂/关闭方法。
2. 凭据只从后端读取。本轮拟使用 BIGFISH_ALIYUN_NLS_APP_KEY / BIGFISH_ALIYUN_NLS_TOKEN；文档列明地区/显式启用方式。生产请求只到选定的官方 HTTPS 接入点，使用 POST、正确编码，禁止日志打印 Token、带 Token 的 URL、原始错误正文。
3. 处理 Token 失效：至少完成环境 Token 的有效/失效状态识别和明确错误。设计并测试带到期时间的 TokenProvider，刷新采用单飞和截止时间；若真实自动续期还需要 SDK/AccessKey 授权或新依赖，写明具体需求，继续完成静态 Token 的真实验收，不能把尚未实现的自动续期算作完成。
4. 音色必须校验。当前配置 zh-CN-XiaoyiNeural 是 Edge 标识，不能直接发给阿里云；提供阿里云音色与默认值建议供集成配置。语速/语调等扩展先用供应商合理默认值，需要公开配置时写提案。
5. 正确处理文本上限。官方当前短文本接口限制 300 字符、超长可能被截断；实现前再次核对。首版超限要在本地明确拒绝或在同一截止时间和音频总量内分段合成，不能无提示截断后宣称全文已播。不要把整段模型输出无限分段并发发送。
6. 严格遵守 SpeechPort 传入的总 deadline，实现连接/读取/整体超时、cancel、有限并发、有限重试与冷却。401/403、Token 失效、参数/音色错误不盲目重试；429/5xx 的重试不突破总时限；失败不偷偷回退为测试音或另一付费供应商。
7. 输出真实 PCM WAV（首选 16kHz/16bit/单声道）、正确 duration；文件位于本工作树配置的 audio 目录，UUID/taskId 命名、不覆盖旧任务。基线 AudioAssets 只接收 audio/wav 且不超过 2,000,000 字节。检查音频头、大小、空/截断内容，不能把供应商 JSON 错误当 WAV 成功保存。
8. 避免临时音频无限增长：设计单任务文件的拥有者和显式释放方法；仅处理该任务创建的明确路径，不递归/扫描批量删除。取消/失败时释放该任务自己的文件，成功文件需在集成读取后释放；把接线方法写清楚，不在返回 AudioAsset 前删掉文件。资源总量/并发有上限。
9. 真实验证最多 3 条短句，提供显式启用的独立验证入口；默认测试不产生云端请求。没有 AppKey/Token 时完成本地替身和文件验证，并列出准确的本机配置步骤，不要求用户把凭据发到聊天。

## 必须验证

- 合成结果是可解码的人声 WAV，duration 与音频内容一致；单纯 HTTP 200 或返回文件不能算完整成功。
- 本地替身覆盖超时、取消、429、Token 失效、JSON 错误、截断音频、错误音色、长文本和下一次请求恢复；取消后不继续产出可播文件。
- 当前引擎合成超时 5 秒、播放完成等待 30 秒。先让适配器尊重传入 deadline，在 handoff 提出合理的真实语音时限/短句时长和集成改动，不能自行改共享引擎或无限延长超时。
- 真实合成成功时记录音色、脱敏请求次数、耗时、音频大小/采样率/时长；可在本机提供不含私人内容的测试音频供试听，音频/凭据不入 Git。实际浏览器播放链路由主窗口最终联调。
- 运行 `./scripts/maven.sh -B -ntp test`；交接到 docs/handoffs/phase2-speech.md，区分已实现的静态 Token 与自动刷新能力。

参考：https://help.aliyun.com/zh/isi/user-guide/restful-api-3 。
