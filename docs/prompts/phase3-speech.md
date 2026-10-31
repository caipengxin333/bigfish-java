# 第三轮任务二：阿里云 NLS Token 自动获取和刷新

你继续在原前端窗口负责 Java 语音。本轮只补 Token 获取/续期与恢复能力，保留已经成功的真实合成链路；不修改 Vue。

## 必须先同步，防止覆盖已修复问题

工作目录：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/frontend`。
分支：`feature/frontend-stage`，端口 18082/15175。

1. 检查 pwd、git branch --show-current、git status --short，保护所有已有修改，不能自动 reset、stash 或覆盖。
2. 工作区干净后执行 `git merge --no-edit main`。然后运行 `git merge-base --is-ancestor c8be17c HEAD`，必须返回 0；再检查 `git merge-base --is-ancestor main HEAD`。
3. 阅读最新 AGENTS.md、docs/phase3-plan.md、docs/handoffs/phase2-speech.md 顶部维护记录、docs/handoffs/phase2-integration.md、docs/providers/aliyun-nls.md。
4. `c8be17c` 修复了真实 zhigui 响应 WAV 头与实际长度不一致的问题：**请求 format=pcm → 完整接收 → 本地按真实长度封装 WAV → 严格校验**。不能恢复为直接请求供应商 WAV，不能删除格式、长度、静音或时长检查；保留该提交的回归测试。`364d620` 包含交接约定。

## 任务

1. 先检查现有 StaticNlsTokenProvider、RefreshingNlsTokenProvider。已有刷新调度框架已经实现；缺少的是官方 CreateToken 请求与实际工厂接入，不要重写一套重复框架。
2. 核对阿里云官方当前 Token 文档，实现固定官方地域/域名的 Token 签发客户端，读取真实 Token.Id 与 ExpireTime。生产端点不接受任意网页输入，不跟随到其他主机；使用正确的官方请求签名与时间/nonce。优先 Java 21 和现有 Jackson；需要新 SDK/POM 时先形成具体依赖提案交主窗口，继续完成不依赖新依赖的部分。
3. 交付可注入的 TokenProvider 工厂：STATIC 模式沿用现有 AppKey+NLS Token；RAM 模式使用独立阿里云访问凭据取得临时 NLS Token，再交给现有刷新器。模式必须显式，默认 STATIC；不在 STATIC 失效后偷偷改用其他账户。建议启动变量 BIGFISH_ALIYUN_NLS_TOKEN_MODE=STATIC/RAM、BIGFISH_ALIYUN_ACCESS_KEY_ID、BIGFISH_ALIYUN_ACCESS_KEY_SECRET；支持 STS 时另列明确变量与到期行为。这些是交给主窗口接线的命名提案，不擅自修改 ProviderServices。
4. 获取与续期在后台有界执行：提前刷新、单飞、有限等待者、超时取消、失败退避；有效旧 Token 在刷新暂时失败时的使用策略要明确，过期后不得继续当有效。服务端拒绝旧 Token、并发更换、新旧回调竞争不能废弃新 Token；不能重复发布已经成功的音频。
5. 只在显式选择真实服务并启用后按需获取 Token。构造对象、加载配置和状态轮询不能悄悄访问云服务。给出稳定脱敏状态/失败码与最小集成示例；网页只能看到模式、到期时间/剩余时间、刷新结果，不能看到 Token 或 AccessKey。
6. 凭据仅从本工作树后端安全注入。用户目前配置的是静态 NLS Token，不能据此假设已有 RAM AccessKey；不要复制主项目 IDEA 配置或旧 Luna 密钥，不在聊天要求用户粘贴密钥，不自动创建云账户/权限/密钥或购买服务。缺 RAM 凭据时完成真实客户端代码与本地验证，清楚标注云端签发尚未验证。

## 修改范围和验证

仅修改 speech 包、speech 测试/夹具、docs/providers/aliyun-nls.md、docs/handoffs/phase3-speech.md。不改 configuration、RuntimeEngine、contract、Vue、POM、公共接口或主工作树。

- 本地测试覆盖官方签名规则的固定向量/替身请求、响应解析、真实到期时间、提前刷新、单飞、失败恢复、取消、时钟偏移、畸形/缺字段响应、旧 Token 拒绝与新 Token 竞争。使用假凭据，不能提交真实响应或签名 URL。
- STATIC 模式必须继续兼容现有用户配置；PCM→WAV、Java Sound 解码、错误文档拒绝、超时/文件释放回归必须继续通过。
- 运行 `./scripts/maven.sh -B -ntp test`。真实验收单独显式开启，具备独立凭据时最多 1 次 Token 签发和 1 条短句合成；不要靠反复请求等待 Token 过期，续期竞态用可控时钟与替身测试。
- 每一步修改前简述内容，完成后说明变化与结果，禁止批量删除。交付前再次合并最新 main，创建本地提交。
- handoff 写明基线/实现 SHA、实际结果、工厂调用示例、主窗口需要注入的参数/状态映射、缺少的云端验证条件。未接入主会话的部分不能宣称已全链路自动续期。
- 不更新 main、不推送远端、不创建 PR、不自动联系其他聊天。最终报告 SHA 与交接文件绝对路径。
