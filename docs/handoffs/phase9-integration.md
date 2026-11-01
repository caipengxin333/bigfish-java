# 第九轮主集成：Live2D 舞台与外部资源

日期：2026-11-02（Asia/Shanghai）。主目录 `bigfish-java`，分支 `main`。

## 合并与集成修补

- 基线 `d8fccd2`；主目录和两个工作树开始时干净。后端 tip `813e8d5`、前端 tip `9aaaaff` 均包含该 main 基线。
- 后端实现 `be893e4`，以 `--no-ff` 合入，主合并提交 **`237d36c`**；前端实现 `7cb1534`，主合并提交 **`64a54a5`**。均无冲突，未使用 cherry-pick/reset。
- 后端按环境变量提供受限只读资源，前端独立 Live2D 组件复用已有音量与播放状态。无新 Java/npm 依赖版本、业务 DTO、数据库字段或播放协议。
- 主窗口将 Vite 资源代理缺省端口从 18082 修正为 **18080**，与其余代理一致；工作树仍以 BIGFISH_API_TARGET 覆盖。
- 统一外部资源目录：必须在 BIGFISH_DATA_DIR 之外，不能使用默认 runtime/avatar-assets。前端独立测试目录仅是该轮临时资源根，不能作为主项目默认部署约定。
- 更新 README、AGENTS、架构、任务边界、前端说明、新增 docs/live2d.md；增加本机模型/Core 忽略规则，补齐第三方来源及构建产物的许可文件。Framework 与 Core 声明不重新授权为 Apache-2.0。

## 主窗口验证

| 验证 | 结果 |
| --- | --- |
| JDK 21，完整 Maven 测试 | **254 项：252 通过、2 项真实 NLS/Token 默认跳过，0 失败/错误**；01:30:00 +08:00 完成 |
| 前端测试 | **112/112 通过**；保留既有 101 项及新增 11 项 |
| 前端构建 | SDK 编译、严格类型检查与 Vite 构建通过 |
| 官方 SDK 来源核对 | 本机官方 ZIP SHA-256 匹配；46 个 Framework TS 文件、上游 LICENSE/README/CHANGELOG 和 Core d.ts 与包内文件一致；外部 Core 哈希符合 SRI 固定值 |
| JAR | 最终 bundle-ui 打包于 01:40:58 +08:00 通过；10 个前端产物逐字节与 dist 一致，包含外部资源服务及 META-INF/前端许可声明，不含 Core 可执行文件、model3、moc3、模型贴图或测试页面 |
| 浏览器 | 本机 Codex in-app browser；实际 JAR 同源页面加载模型、Core、8192 贴图和手动脸红表情 |

后端测试需要本机端口和 JVM attach，按权限流程在沙箱外运行。日志：`/tmp/bigfish-phase9-main-tests.log`、`/tmp/bigfish-phase9-main-ui-tests.log`、`/tmp/bigfish-phase9-main-ui-build.log`、`/tmp/bigfish-phase9-main-package.log`。

## 实际 Java + Vue + Live2D 联调

- 使用独立 JAR 端口 **18084**，SQLite/音频目录为主工作树忽略的 `runtime/phase9-integration`；显式 `BIGFISH_ALLOW_PAID=false`，全部测试选择 MOCK/MOCK/LOCAL_TEST_TONE。没有读取凭据、调用抖音或云服务，没有停止用户 18080/15173 或 Luna。
- 模型与官方 Core 从前端交接的本机目录复制到工作区独立 `avatar-assets`，26 个文件逐文件哈希一致；原始项目未修改，保留来源文件和水印。
- 浏览器通过真实 Java `/avatar-assets/` 加载形象，未使用前端轮次的 Python 静态替身；模型完成加载，表情选择可用。
- 发送一次模拟评论：实际字幕为模拟回复，浏览器播放中音量约 **0.109**；同任务观察到 **STARTED → COMPLETED**，结束后音量 0。后端记录模型和合成成功。
- 发送一次模拟礼物：字幕为模板感谢，音量约 **0.109**；后端诊断确认合成成功及 **01:33:12 播放完成**。这不是抖音真实礼物验收。
- 测试页关闭后重新进入，原会话保留、播放端未就绪；点击开始互动恢复现有会话。模型偏好保留，未自动启动新的互动。
- 最后从页面停止互动：后端确认 **STOPPED**，播放连接关闭、音量 0，形象仍可显示。
- 验收结束后已关闭临时测试标签页，并通过原进程终端停止本轮 18084 JAR；用户的后端和前端仍由用户自行重启。
- 截图 `/tmp/bigfish-phase9-main-stage.jpg` 为本机验收证据，不提交含受限模型的图片。运行日志 `/tmp/bigfish-phase9-main-runtime.log`。

本次主窗口观察实际播放状态、音量和渲染效果，没有重新测量 Cubism 内部嘴型数值；前端独立夹具的嘴型峰值、取消闭嘴、纹理释放及 375px 证据见 phase9-frontend-live2d.md，不能冒充本轮主窗口测量。当前仅本机 IAB 验证，Safari/Chrome、长期后台、GPU 峰值、系统休眠和 OBS 仍未验收。

## 使用与后续

- 本机已备资源根：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/avatar-assets`，位于 Git 仓库之外。
- 在用户 IDEA 的 BigfishApplication 环境变量设置 BIGFISH_AVATAR_ASSETS_DIR 为该路径并重启；前端刷新后选择“舞台形象设置 → Live2D · 本机 BigFish”。使用 JAR 时运行最新产物，同样需要该环境变量。详细操作见 docs/live2d.md。
- 当前固定 BigFish 入口，没有自动目录扫描、模型上传/选择、AI 情绪或 OBS 第二播放器；后续需求不能当成本轮已交付功能。
- 模型/Core 不随 Git、dist、JAR 发布。Framework 和编译期 Core 声明须保留原条款，发布应用时依实际使用核对 SDK 发行许可。源码合并不代表商业模型授权已经取得。
- 后续各窗口先合入最新 main，保留资源代理、目录边界、原播放控制与既有采集修复；主窗口最终提交 SHA 在回复中给出。没有推送 GitHub 或联系其他窗口。
