# GitHub 发布审核交接 · 2026-11-01

## 主集成窗口合并复核 · 2026-11-01

- 合并前 main：`6286b1d7db53711b2f426f0be19c74524a438e54`；合并来源 `chore/github-publish` / `0bbce60cf6a218f8d30dd6525f3434465a178a3d`。
- 已确认来源分支只有这一笔新增提交，仅修改 `.gitignore` 与本交接，没有业务代码、依赖或运行配置改动；合并无冲突。
- 主窗口验证 13 个本机产物/环境配置样例均被忽略，5 个源码/构建文件样例仍可跟踪；主分支当前跟踪文件中未发现本次规则覆盖的环境配置、数据库、私钥或抓包文件。
- `git diff --check` 通过。业务代码与下方已审核快照相同，本轮复用发布窗口已记录的业务测试，不重复调用云服务或重启用户后端。
- 本条只记录合并复核，未再次执行完整历史密钥扫描，也未代替发布窗口推送。发布窗口继续以合并后的最新 main SHA 复核新增提交，执行普通推送与远端 HEAD/克隆验证；合并提交 SHA 由主窗口回复提供。

## 发布状态

- 源仓库：bigfish-java；发布来源：main。
- 已审核业务快照：`6286b1d7db53711b2f426f0be19c74524a438e54`。
- 已确认包含语音修复 `c8be17cac58ec1ee5c694aedf65e7b4737bfc641`。
- 隔离分支：`chore/github-publish`；工作树：`../bigfish-worktrees/github-publish`。
- 用户明确指定目标：<https://github.com/caipengxin333/bigfish-java>，**public**。
- 已通过已有 GitHub CLI 登录创建空仓库，API 确认可见性 public、默认分支 main、当前账号有 push 权限。**代码尚未推送**；远端 HEAD 比对及上传后 clone 验证尚未执行。
- 本分支只修改 `.gitignore` 和本交接，不合并功能分支，不改业务、依赖或全仓库许可证。

## 交给主集成窗口

确认主工作区状态后，由主集成窗口合并 `chore/github-publish`。不得把未合入 main 的发布分支提交直接推到远端 main。

合入后通知发布任务：重新记录最新 main SHA、核对新增提交及完整可达历史；如果只有忽略规则与本交接，业务快照测试可按代码树一致性复用。若 main 含新业务合并，重新审查差异并执行相应验证。推送前再次检查 main；只普通推送审核 SHA 到 `refs/heads/main`，不推全部 refs、标签或功能分支。

上传完成后必须比较远端 main HEAD 与审核 SHA，正常 clone 到新的隔离目录，确认克隆不含私人环境文件且能够构建、无密钥启动 MOCK。不能将本次空仓库创建视作上传完成。

## 文件与密钥审核

- 审核范围：上述 main 可达的全部 **33 个提交、275 个唯一 blob**，提交元数据、187 个当前跟踪文件及索引。主工作区和发布工作区均无预先暂存修改；没有子模块或跟踪的二进制资源。
- 使用 [Gitleaks 8.30.1 官方发行版](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1)，下载校验和一致。扫描在本机执行，没有发送源码或凭据到扫描服务。
- Gitleaks 完整历史、当前跟踪快照、暂存区均为 0 命中；补充规则检查供应商 Key、Token、私钥、Cookie、签名 URL 和带凭据 URL。
- 本机 IDEA 中三项实际凭据仅在进程内读取并逐值比对全部历史 blob、提交元数据和索引；均未命中，没有打印或写入凭据值。
- 补充规则在 `backend/src/test/java/cn/bigfish/collector/WebDouyinConnectionProviderTest.java:29` 命中签名 URL；已确认是人工 synthetic 测试签名，不是真实连接凭据。保留该测试。
- 所有历史文件路径中未发现 `.env`、IDE 配置、runtime、数据库、构建输出、私钥或抓包文件被跟踪。未执行历史改写、密钥轮换、强推或文件删除。
- 主工作区已忽略 `.idea/`、`runtime/`、`.cache/`、`backend/target/`、`frontend/node_modules/`、`frontend/dist/`、tsbuildinfo 和 `.DS_Store`；这些本机内容没有复制到发布工作树。
- 原忽略规则漏掉 `.env.production`、`.vscode/` 和 runtime 外的本机数据库/私钥/抓包。本分支补齐 `.env.*`、IDE 工作区、SQLite/WAL/journal、私钥容器和 HAR/PCAP 等规则；未删除文件，未按扩展名删除测试夹具。
- `.gitignore` 检查确认上述样例路径被忽略；源码、POM、package-lock、构建脚本、application.yml 和人工采集夹具仍可跟踪。没有新增环境示例或复制任何实际环境配置；`${...}` 读取逻辑保留。

## 文档路径与第三方来源

当前文档仍包含本机卷/工作树绝对路径，已报告且未擅自整理。具体位置（以已审核快照为准）：

- `docs/handoffs/frontend.md:4`、`docs/handoffs/phase2-collector.md:4`、`docs/handoffs/phase2-speech.md:24`。
- `docs/prompts/backend.md:12,14,15`、`docs/prompts/collector.md:12,14,15`、`docs/prompts/frontend.md:12,14,15`。
- `docs/prompts/github-publish.md:5,12`。
- `docs/prompts/phase2-collector.md:5,7`、`docs/prompts/phase2-deepseek.md:5,7`、`docs/prompts/phase2-speech.md:7,9`。
- `docs/prompts/phase3-collector.md:5`、`docs/prompts/phase3-speech.md:7`、`docs/prompts/phase3-stability.md:7`。
- `docs/providers/aliyun-nls.md:87`、`docs/开新窗口指南.md:7,13,14,15`。

这些路径也存在于历史文档版本；改当前文档不能去除历史记录。Git 的原有作者元数据也会随正常历史发布。未修改作者身份或历史。

采集实现的来源、固定 revision 和未采用上游代码的许可证说明见 `docs/providers/douyin.md`、`docs/handoffs/collector.md`。人工 JSONL、页面及 wire 夹具来源见 `backend/src/test/resources/collector/README.md` 与 WireFixtures 注释。未发现复制的 AGPL 签名脚本、proto、旧 Luna 目录或受限 Live2D 模型。前端占位图形/测试音为源码生成，未跟踪外部媒体资源；package-lock 记录直接前端依赖的 MIT/Apache-2.0 许可。没有擅自给整个仓库增加 LICENSE；public 可见性不等同于已授予全仓库开源许可。

## 已执行验证

以下业务验证针对 `6286b1d` 的代码；工作树仅另有本次忽略规则修改。构建进程清除供应商凭据和相关 JVM 注入变量，设置 `BIGFISH_ALLOW_PAID=false`。

| 检查 | 结果 |
| --- | --- |
| `./scripts/maven.sh -B -ntp test` | 140 项，139 通过，1 项真实 NLS 测试默认跳过，0 失败/错误 |
| `npm --prefix frontend ci`（官方 registry，隔离缓存） | 锁定依赖安装成功 |
| `npm --prefix frontend test` | 36 项全部通过 |
| `npm --prefix frontend run build` | TypeScript 与 Vite 生产构建通过 |
| `./scripts/package.sh` | 完整脚本通过，含前端重建、Maven verify 和单 JAR 打包；140 项后端测试同上 |
| 无密钥单 JAR 启动 | 随机端口 63752，独立 `runtime/publish-smoke`，云服务 disabled |
| README `node scripts/smoke.mjs` | health、admin/stage、配置持久化、CAS 409 与配置恢复通过 |
| MOCK 会话 | MOCK 回复与 LOCAL_TEST_TONE 启动成功，停止后 STOPPED |

初次依赖下载和绑定端口被沙箱拒绝，按权限流程在沙箱外重试成功；没有关闭 TLS、占用 18080/15173 或停止用户服务。仅停止本次创建的临时服务进程。没有付费云调用。

## 依赖告警与后续范围

`npm audit --json` 返回 3 项 high：`vite`、`vue`、`@vue/server-renderer`。固定版本仍为 Vite 7.1.12、Vue 3.5.22；审计给出的修复版本分别为 Vite 7.3.7、Vue 3.5.44。此次没有运行 audit fix、强制升级或调整公共依赖。由主集成窗口另行评估并回归，不把构建成功写成依赖无漏洞。

本机审核脚本、扫描结果和构建日志保留在发布工作树 `.cache/publish-audit/`，均被忽略，不纳入本提交。最终修补提交 SHA 由发布任务回复提供，避免文档自引用提交号。
