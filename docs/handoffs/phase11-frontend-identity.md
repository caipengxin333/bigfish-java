# 抖音接入身份配置：前端交接

日期：2026-11-02（Asia/Shanghai）。工作树 `bigfish-worktrees/frontend`，分支 `feature/frontend-stage`。

## 交付与基线

- 开工工作区干净，上一轮 HEAD `9aaaaffabf140e6efe008f09a9e3434b0186c02e`。
- 执行 `git merge --no-edit main`，快进到 `be628e125caa855ba708f6222aa5cb42171658ee`。已阅读 AGENTS、职责边界、phase10/phase9 集成交接及抖音说明。
- 实现提交：**`83f8ba186be332d707cea62782be44206a268032`**。
- 交付前复核本地 main 仍为 `be628e1`，`git merge-base --is-ancestor main HEAD` 返回 0。
- 本文件在独立文档提交中记录实现 SHA；最终文档提交 SHA 见当前窗口最终报告及 Git 日志。
- 只改前端及本文件。没有修改 Java、采集、contracts.ts、OpenAPI、协议、依赖或其他交接；没有推送、联系其他窗口、操作主项目运行会话。

## 当前交付边界

**界面、显式本地 mock 和回归已完成；真实身份管理与检查连接尚未接入。**

当前 main 只有启动环境注入的 guest/external 凭据链路；公共 OpenAPI 与前端 contracts 没有网页身份读取、保存、清除或独立连接检查契约。不能将当前环境变量链路表述成网页内存保存已经生效，也不能用现有 runtime 总事件数证明评论/礼物接收。

- `/admin?transport=real`：显示“真实接口 · 待接入”，状态无法确认，真实操作禁用。没有猜测 URL、发出身份请求或自动回退 mock。
- 用户明确打开 `/admin?transport=mock#douyin-identity` 才能演练；全区标明 MOCK、仅假数据、非真实连接测试。
- `src/identity/editor.ts` 的 `IdentityPort`、小写 visitor/cookie 及字段均为**前端内部视图模型和可注入测试端口**，不是公开 DTO/HTTP 接口。主窗口确认契约后需要适配，不能直接作为公共定义使用。
- mock 只保存合成的元数据（布尔标记、枚举、版本、时间）以测试刷新，键为 `bigfish.mock.identity-metadata.v1`。Cookie、User-Agent、手动 ID 的原值均不保留在存储中，不发送网络请求、不输出日志。正式实现不得复用这份 localStorage 作为凭据存储。

## 修改内容与文件

| 文件 | 内容 |
| --- | --- |
| `frontend/src/components/AdminPage.vue` | 增加身份面板，读取已保存房间、房间草稿/冲突保护和运行锁定；业务配置保存逻辑不变 |
| `frontend/src/components/DouyinIdentityPanel.vue` | 游客/Cookie 模式、独立普通选项表单、密码输入、期限、UA、UID 高级补填、明确替换/清除、状态与连接检查、Chrome/Safari 指引、折叠 mock 演练工具 |
| `frontend/src/identity/editor.ts` | 可注入编辑状态机、字段验证、单操作锁、有限超时/取消、旧结果隔离、安全错误白名单、上下文变化后仅重读元数据 |
| `frontend/src/mock/identity.ts` | 无网络、无原始凭据存储的显式 mock，支持失败/超时/到期/冲突/重启场景 |
| `frontend/tests/identity.test.ts` | 16 项状态、隐私与竞态回归 |
| `frontend/tests/identity-panel.test.ts` | 4 项真实 Vue SFC 渲染回归，使用现有 Vue/TypeScript 工具，无新增依赖 |
| `frontend/tests/identity-fixture.html` | 隔离实际 Vue 组件、阻止 fetch、仅假数据的浏览器夹具与宽度/输入清空检查 |
| `frontend/tests/identity-visual.html` | 375px iframe 验收入口；两份夹具均不进入生产构建入口 |

交互细节：

- 默认游客。普通接入选项与 Cookie 单独保存；切换游客不会清除已配置 Cookie，也不会自动检查或启动互动。
- Cookie 模式先保存 UA/模式等选项，再保存 Cookie；输入为空不覆盖旧凭据，失败保留输入，成功立即清空。只显示配置标记、来源和时间，不宣称“已登录成功”。清除需点击独立按钮及确认。
- 互动运行或状态不确定时禁止修改、清除、检查；房间为空、草稿未保存、冲突/结果待确认时不检查。管理页不启动 AI 或发声。
- 模式、房间、运行状态、跨页状态改变会使旧检查失效；中断或超时不会自动重发写请求或检查。页面卸载清空敏感输入并取消；重新聚焦/恢复只读状态。
- 检查区分别展示未配置、检查中、失败、连接但未收消息、收到评论、收到礼物。检查失败就在按钮旁展示；没有自然礼物时只说“尚未观察到礼物”，不推断权限。
- UID 默认自动解析；明确解析失败时展开高级设置并提供补填按钮。UA/UID 已配置只显示标记，空值保留已有设置；关闭手动覆盖恢复自动解析。
- 页面包含“刷新页面保留；后端重启后需重新确认配置。”并明确这是待接入的后端内存约定。mock 期限选项 30/60/240/1440 分钟仅为演练范围，变更期限不延长已有记录，须主窗口确认。
- 保留原故障摘要、配置冲突、来源选择、舞台租约、停止恢复及 Live2D 代码；本轮未改播放链路。

## 验证结果

| 命令/验证 | 实际结果 |
| --- | --- |
| `npm --prefix frontend test` | **132/132 通过**：原 112 项 + 本轮 20 项；0 失败/跳过 |
| `npm --prefix frontend run typecheck` | SDK 构建及 `vue-tsc --noEmit` 通过 |
| `npm --prefix frontend run build` | SDK、`vue-tsc -b`、Vite 构建通过，93 modules；原 SDK 许可产物保留 |
| `git diff --check` / 提交前 staged diff 检查 | 通过 |
| 浏览器实际管理页 | Codex 内置浏览器，15175、显式 mock：模式分离、成功清空及未配置房间禁止检查通过 |
| 实际 Vue 浏览器夹具 | 保存失败保留密码输入、替换成功清空、运行锁定、连接无消息/评论、解析失败补填、超时清除旧成功、刷新恢复、清除确认、模拟后端重启通过 |
| 桌面/375px | 桌面 1280 与 375px iframe 均无横向溢出；夹具输出 `375/375 PASS`，敏感输入已清空；窄屏检查按钮可操作 |

自动测试另外覆盖：收到礼物的独立证据、读失败不展示旧成功、Cookie 到期、版本冲突、异常脱敏、双击、取消后迟到保存不清空新草稿、父级配置读取打断首次身份读取后恢复、旧房间检查不污染新房间、不自动重试/偷偷回退。

测试日志 `/tmp/bigfish-identity-tests.log`；截图 `/tmp/bigfish-identity-desktop.jpg`、`/tmp/bigfish-identity-375.jpg`（仅 mock 元数据，无真实凭据，不入 Git）。最终 132 项测试在代码提交前完成。

**以上浏览器交互使用真实 Vue 组件，但服务均为 mock。** 未提交真实 Cookie、未运行 Java、未连真实抖音、未调用 DeepSeek/NLS、未验证真实礼物或网页凭据热更新。Safari/Chrome 原生浏览器本轮未实测；Live2D/音频本轮仅保留既有回归，未重复做真实模型或发声验收。仅启动并退出自己的 `./scripts/dev-frontend.sh`（15175），未停止其他窗口服务。

## 可复现操作

1. 在本工作树运行 `./scripts/dev-frontend.sh`（读取 `runtime/worktree.env`，本窗口 15175）。
2. 打开 `/admin?transport=mock#douyin-identity`；确认 MOCK 标记，选择“使用登录 Cookie”，填写假 UA `FAKE_BROWSER_UA_FOR_UI_TEST`，保存接入选项。此时 Cookie 仍未配置。
3. 仅填写假 Cookie `FAKE_COOKIE_FOR_UI_ONLY`，保存后输入为空，显示“已配置（未证明登录有效）”；刷新应保留元数据而不回显原值。不要使用真实凭据。
4. 展开身份区高级调试，勾选“模拟保存失败”，填写新假值并替换：错误提示且输入保留；取消故障后替换：清空。测试独立清除按钮与二次确认。
5. 勾选“模拟互动运行中”：所有修改/清除/检查禁用。基础配置中的房间为空或未保存时也不能检查，不会自动保存基础配置。
6. 为避免改动业务配置，可打开 `/tests/identity-fixture.html`（固定演练房间 123456），选择无消息、评论、礼物、失败、超时、UID 解析失败等场景，再点击检查。mock 超时为 2.5 秒。未知结果需要刷新状态后手动重试。
7. 点击“模拟后端重启”，状态回到游客/未配置；没有真实 Java 重启。可以勾选模拟读取失败，刷新确认不残留成功状态。
8. `/tests/identity-visual.html` 检查 375px；点击“检查布局和输入清理”查看宽度与空输入标记。页面不会读出或显示输入内容。
9. `/admin?transport=real` 应显示真实身份功能待接入且禁用，不默认使用 mock。不要用 mock 结果判断真实账号状态。

## 待主窗口确认的公共契约与集成步骤

本轮不提自定义路径/字段进入线上；以下是能力与语义需求，由主窗口统一命名、定义 DTO/OpenAPI 后再接线。

1. **只读脱敏状态**：当前选择、是否配置 Cookie、来源、更新时间、到期时间、UA/UID 是否配置及自动/覆盖来源。需要后端实例/配置版本以识别重启、跨页替换；不能返回 Cookie/完整 UA/手动 ID 或签名 URL。网页选项和实际运行会话身份应区分。
2. **分开保存**：普通模式/UA/UID/期限与敏感 Cookie 写入分开；Cookie 只写不读、空值不覆盖、显式清除、默认游客；切换游客是否保留旧 Cookie 本轮按保留演练，需确认。模式不能偷偷回退或改变当前会话；存储只在后端内存，更新下一次启动生效而不要求重启 Java。
3. **并发与期限**：写请求携带确认过的版本，服务端原子校验互动状态、版本及后端实例；明确 Cookie 与普通字段的期限范围、到期清理、重启后默认状态、环境注入来源与网页来源优先关系。前端锁定不能代替服务端保护。
4. **显式有界连接检查**：与主互动/模型/语音分离，使用已保存房间和身份；给出检查关联 ID、房间/身份版本、起止时间、运行状态、稳定失败码、自动 UID 解析失败原因、独立评论/礼物计数。需要明确超时/取消释放采集资源及服务端并发检查规则，不能在浏览器取消后继续无限采集。真实无礼物样本不应返回“无权限”。
5. **安全边界**：主窗口需确认身份写入的同源/请求鉴权与 CSRF 保护、敏感体/头不记日志、响应/异常脱敏、缓存控制；配置导出不可包含凭据。原始供应商响应不透传。
6. 契约落地后实现真实 `IdentityPort` 适配及严格响应校验，将正式来源、检查结果与失败码映射到 UI；当前组件的 mock 来源/场景展示不可直接充作真实数据。对接真实运行锁定和配置版本后，再验收后端重启、并发修改、超时结果核对及账号接收。

Chrome 指引依据 [官方 Network 参考](https://developer.chrome.com/docs/devtools/network/reference)，Safari 标头入口参考 [WebKit Network Tab](https://webkit.org/web-inspector/network-tab/)。Safari WebSocket 可能显示“没有请求 Cookie”、应转直播间文稿请求获取的具体发现来自本轮用户说明，已原意纳入界面；本轮没有复现真实账号浏览器请求。页面不要求发到聊天、不建议上传完整 HAR 或运行陌生脚本。
