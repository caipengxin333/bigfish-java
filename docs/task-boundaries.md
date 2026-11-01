# 文件边界与协作流程

> 当前第四/五轮：后端负责 Java 会话与播放生命周期，前端负责 Vue 页面、一键互动与浏览器音频，采集窗口负责采集解析与签名。集成结果见 [phase5-integration.md](handoffs/phase5-integration.md)，各窗口继续前必须同步最新 main。第二/三轮计划仅作历史记录，不再将前端窗口分配给 Java 语音。

> 下列目录是角色的最大边界，实际修改仍以当前明确任务为准；没有新任务的模块不顺手重写。公共字段、线上播放协议和依赖调整交集成窗口。

| 角色 | 分支 | worktree（相对主仓库） | 后端 / 前端端口 |
|---|---|---|---|
| 集成（当前窗口） | main | . | 18080 / 15173 |
| 后端核心 | feature/backend-core | ../bigfish-worktrees/backend | 18081 / 15174 |
| 前端与舞台 | feature/frontend-stage | ../bigfish-worktrees/frontend | 18082 / 15175 |
| 抖音采集 | feature/douyin-collector | ../bigfish-worktrees/collector | 18083 / 15176 |

## 修改权限

集成独占：根 AGENTS.md / README.md、pom.xml、backend/pom.xml、.mvn/、scripts/、contract 包、frontend/src/contracts.ts、docs/openapi.yaml、docs/event-schema.md、docs/playback-protocol.md、架构及任务说明。

后端：backend/src/main/java/cn/bigfish 下 configuration/event/scheduler/conversation/speech/playback/persistence/management；backend/src/main/resources（静态资源除外）；backend/src/test/java/cn/bigfish/core/；docs/handoffs/backend.md。基础集成测试如需改动提出说明，不能删除或削弱已有断言。

前端：frontend/、本任务 docs/handoffs/phase*-frontend*.md。src/contracts.ts 仅允许按已确认公共契约同步 TypeScript 镜像，不能自创公共字段或语义；package.json/lockfile 的依赖调整须交集成窗口。本轮不得改 Java 语音。

采集：backend/src/main/java/cn/bigfish/collector/、backend/src/test/java/cn/bigfish/collector/、backend/src/test/resources/collector/、docs/handoffs/collector.md。新依赖通过提案交集成窗口统一加，不自行修改公共 POM。

## 提交与合并

- 三个分支从同一个基线提交创建。各自创建小范围本地提交，提交前检查没有密钥、数据库、音频或缓存。
- 任务完成后记录 commit SHA；由当前窗口在 main 依次 `git merge --no-ff <branch>` 并联调。不要同时 merge 与 cherry-pick 相同提交。
- 公共接口变更先由集成窗口提交 main，相关任务在工作区干净后合入该提交再继续；不要自行 rebase/reset 他人的分支。
- 后端和前端允许使用约定的 mock，不等待真实抖音；mock 状态必须可见。采集器先用事件夹具和独立连接验证。
- 冲突由集成窗口根据接口约定解决，不用自动“全部选我方/对方”。合并后的测试结果单独记录。
- 代码隔离不能隔离机器资源。每个任务的 runtime/worktree.env 由初始化脚本生成；测试须使用自己的数据库/端口，不连接主工作树的服务。
- 可以共享只读源代码参考，但不得共享可变 SQLite 或音频目录。新窗口不会自动继承当前对话的全部上下文，必须读文档。

## 交接模板

```
任务/分支：
基线提交与最终提交：
已实现（逐项）：
修改目录：
运行及测试命令、结果：
真实服务验证 / mock 验证区分：
待处理问题与风险：
公共接口/依赖变更提案：
集成步骤：
```
