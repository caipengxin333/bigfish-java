# 第十一轮集成：本机 Cookie 身份配置与接收检查

日期：2026-11-03。主目录 `bigfish-java`，分支 main。基线 be628e125caa855ba708f6222aa5cb42171658ee；功能集成提交为 7c6668c9754dc50d461dd22b5b92055052eb5276。

## 用户验收更新（2026-11-03）

用户在集成完成后明确反馈“礼物问题已经解决了”，确认礼物接收与感谢正常；当前状态更新为**用户实测通过**。以下独立采集数据仍保留其原始验证范围，不将用户反馈改写成自动测试或新增逐条日志证据。

本次文档更新以 7c6668c 为基线，在 README 同步功能状态、使用说明与最近一次集成测试结果；没有修改代码、环境变量或公共接口，也没有重新调用云服务。检查 README 与本交接的差异、Markdown 本地链接及 `git diff --check`；纯文档更新不重跑程序测试。文档提交 SHA 见提交历史，原有未跟踪礼物排查记录保留。

## 合并来源与边界

| 窗口 | 分支末端 | 主窗口合并提交 |
| --- | --- | --- |
| 采集 | feature/douyin-collector / f037a45 | 8bb184e |
| 后端 | feature/backend-core / 4e564a8 | ad4ceb1 |
| 前端 | feature/frontend-stage / 99eb23e | 85db851 |

三个分支无冲突合并。各窗口遵守公共接口边界：后端交付内部服务、前端交付演练表单、采集交付外部身份解析。主窗口补齐公共 DTO、OpenAPI、本机管理 Controller、真实页面客户端及跨模块接线，没有让任何功能窗口跨边界覆盖其他窗口。

## 最终行为与重要集成修补

- REAL 管理页可选择游客或提供完整 Cookie + 同一浏览器 UA；可选手工 visitor，默认走采集窗口已验证的同身份页面自动解析。
- 对接后端的原子替换语义，移除“自动身份解析尚未提供”的临时阻断。Cookie/UA/visitor 不能分两步保存成混合快照；空字段不清除，明确游客/清除会移除运行时 Cookie 并在本进程屏蔽环境旧 Cookie。
- 凭据仅后端进程内存；刷新页面保留，重启丢失页面配置并重新应用启动环境。所有写入做 revision CAS；互动/检查/退出未释放时禁止修改。固定版本 lease 隔离旧回调；本机期限到达主动结束使用该凭据的会话/检查。
- 本机敏感 API 校验 RemoteAddr/Host、专用管理头、写入 Origin 和 JSON，拒绝转发来源与跨站请求。32 KiB body 上限、字段长度与字符约束、未知/重复 JSON 键拒绝、固定错误码、no-store，不返回 Cookie/UA/visitor。
- Spring 全局严格 JSON 配置下，单独允许可选 record 字段缺省，必填字段由 Bean Validation 保证；保留未知字段与空 primitive 拒绝。真实 HTTP 测试覆盖省略 visitor/绝对截止时间的保存。
- Vite `/api` 改为显式 `changeOrigin:false`。简写字符串实际默认改写 Host，浏览器首次验收曾被 Origin 校验拒绝；修补后成功，未削弱后端来源校验。
- REAL 页面已单独接入 `identity/api.ts` / `managed.ts`；MOCK 的旧演练端口保留，只在明确 mock 视图展示，不能用演练存储替换真实接口。运行时真凭据不进入 localStorage/普通配置导出；提交成功清空输入，失败不盲重试，旧版本/迟到响应不覆盖新状态。
- “检查连接”默认 20 秒（API 最大 30 秒），最多一次实际解析/连接尝试；每次间隔 30 秒、5 分钟最多 3 次。只有采集器和独立统计 sink，不依赖业务引擎、AI/TTS。可取消；未释放资源不能启动第二轮或新互动。
- 检查区分保存/本地格式通过、连接、评论、礼物、资源释放；未观察到礼物不判定没有权限，收到礼物不代表感谢语音完成。导航/离开时取消已知检查，未知迟到启动也取消对应 ID；后端期限不依赖页面持续在线。
- 将检查单次尝试限制移到 `DouyinCollector` 可选构造参数，保留原 Web provider 的安全身份元数据和外部解析证据；正常互动重连行为不变。
- 修正首次加载 EXTERNAL 时被误当草稿而仍显示游客的问题；期限采用同一时刻计算，避免正好 60 秒被判低于下限。
- 没有修改签到/点赞的业务策略：LIKE_NO_SPEECH 仍有效。保留 ACK、解析、队列、连击去重、语音与播放的历史回归。

## 公共接口

统一前缀 `/api/v1/douyin/credentials`；详细 schema 见 `docs/openapi.yaml` 和 `contract/DouyinAccessContract.java`。

| 方法/后缀 | 用途 |
| --- | --- |
| GET 根路径 | 脱敏元数据、来源、版本、使用状态 |
| PUT /mode | 明确切换 GUEST 并清除当前 Cookie |
| PUT /cookie | 完整原子替换 EXTERNAL 配置 |
| POST /clear | 显式确认清除并转为 GUEST |
| POST /checks | 有界只读采集检查，202 异步开始 |
| GET /checks/{id} | 本次检查状态与评论/礼物计数 |
| POST /checks/{id}/cancel | 取消对应检查，不停止新 ID |

不能将内部 Snapshot 或原始凭据挂到普通 `/config`、`bootstrap`、`providers`。所有接口均需 `X-BigFish-Local-Management: 1`；写入需同源 Origin、JSON，取消也提交 `{}`。本轮只支持本机同源管理，远程代理/服务器凭据管理另行设计。

## 验证记录

- 最终 `./scripts/maven.sh -B -ntp -Pbundle-ui verify`：**322 项，320 通过，0 失败/错误，2 条显式云端测试按条件跳过**（AlibabaNlsLiveTest、AlibabaNlsRamLiveTest 未设置 live 开关）；最终 JAR 构建成功。
- `npm --prefix frontend test`：**140 项全部通过**；`npm --prefix frontend run build` 通过，包含 TypeScript 检查。
- MockMvc 使用真实凭据服务/检查服务 + 人工 Collector，验证 API 接线、自动 visitor、CAS、来源校验早于 JSON 解析、长度/未知键/重复键拒绝、仅元数据返回、互动/检查互斥及取消释放。人工 COMMENT/GIFT 计数不冒充平台接收。
- 新前端回归覆盖首次 EXTERNAL 展示、原子保存、输入清空、错误保留草稿、版本冲突、换行/manual visitor 校验、主动清除、lease 锁、超时、旧检查/迟到响应隔离；原 mock/舞台测试保留。
- 隔离 Java 18084 / Vite 15177、`runtime/phase11-integration`：浏览器 REAL 表单人工 Cookie 保存成功、刷新保留、字段不回显、明确清除回到游客。未使用真实浏览器凭据，未点击真实网络接收检查。
- 对最终 JAR 直连和 Vite 代理执行真实 HTTP：自动 visitor 保存、读取、旧 revision 409、跨源 403、非法换行 400、清除成功。固定人工值不出现在服务日志或 SQLite 普通业务配置中。
- `BIGFISH_BASE_URL=http://127.0.0.1:18084 node scripts/smoke.mjs`：健康、打包 admin/stage、供应商状态、诊断、配置持久化/CAS 检查通过，测试显示名称已恢复。最终 JAR 的 index.html 与最新 frontend/dist 一致。
- OpenAPI JSON 与本地引用解析通过；git diff --check 通过。本轮没有调用真实 DeepSeek/NLS、发送评论、点赞、购买礼物或推送 GitHub。
- 测试实例验收后关闭，主服务 18080 / 15173 未被停止或替换。开工前已有未跟踪 `docs/handoffs/gift-check-20261102.md` 原样保留，没有纳入本轮提交。

## 真实礼物证据及下一步

采集窗口在 2026-11-03 00:14–00:15 +08、用户授权的 external Cookie 会话测试：65 条 WebcastGiftMessage 全部解析，11 条连击重复，54 条 GIFT 到独立 sink；评论 55，点赞 19，ACK 163，MALFORMED 0，资源完全释放。见 collector.md 顶部记录。

这次独立采集证明指定 external 会话的真实礼物采集已可用，当时没有验证具体礼物名称、主会话业务接纳或感谢语音，也不能推出游客必然收不到礼物。该次 Cookie 已释放，主窗口未复用。后续礼物接收与感谢的用户实测结果见本文顶部，现已通过验收。

后续更换房间或凭据时：刷新管理页 → 保存房间号 → 填写自身 Cookie/UA → 保存 → 独立检查 → 资源释放后启动舞台互动。若出现异常，用同一 session 的 collector_inbound 和 gift_trace 核对 GIFT 至 PLAYBACK 完成；不需要为排查专门购买礼物。

三个功能窗口下一轮开工前：保留自身改动，干净后 `git merge --no-edit main`，阅读本记录与当前公共契约；不得以旧演练接口覆盖 REAL 接线，不得撤销同源保护、可选字段解析、自动 visitor、原子替换和回归测试。主窗口不代替用户联系其他窗口或修改其 worktree。
