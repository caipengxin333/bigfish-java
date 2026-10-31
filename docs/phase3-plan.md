# 第三轮：运行诊断、语音续期、真实抖音采集

日期：2026-11-01。用户已确认本轮基本联调成功。起始基线 `364d620`，包含语音修复 `c8be17c`；开始任务时必须合入当前最新 main，不能只停在这两个历史提交。

本计划的职责划分优先于 phase2-plan 和第一轮文档。本轮不重建框架、不重做已完成的 DeepSeek/NLS 接入。三个已有工作树继续使用；当前窗口负责主项目、Vue、公共接口与最终合并联调。

## 任务与独占目录

| 窗口 | 任务 | 工作树 / 分支 | 可修改范围 |
|---|---|---|---|
| 后端 | 分阶段诊断、队列与故障恢复、持续运行测试 | bigfish-worktrees/backend / feature/backend-core | conversation、event、scheduler、playback 包；management/RuntimeEngine.java；新增 management/diagnostics 包；相应 conversation/core 测试；docs/handoffs/phase3-stability.md；docs/providers/deepseek.md；docs/runtime-stability.md |
| 原前端、现语音 | NLS Token 自动获取与续期，保留 PCM 转 WAV 修复 | bigfish-worktrees/frontend / feature/frontend-stage | speech 包及 speech 测试/夹具；docs/providers/aliyun-nls.md；docs/handoffs/phase3-speech.md |
| 采集器 | 可用签名实现、真实评论与礼物接收、生命周期验证 | bigfish-worktrees/collector / feature/douyin-collector | collector 包及 collector 测试/夹具；必要的 META-INF/services/cn.bigfish.collector.DouyinSigner 描述文件；docs/providers/douyin.md；docs/handoffs/phase3-collector.md |
| 主集成 | 模式/状态/配置接线、Vue 展示、合并和端到端验收 | bigfish-java / main | 其余目录，特别是 contract、configuration、SessionService、ManagementController、OpenAPI、前端、依赖与脚本 |

端口继续隔离：main 18080/15173，backend 18081/15174，frontend 18082/15175，collector 18083/15176。业务数据库、音频、日志、缓存不能共用。三个实现窗口本轮均不修改 Vue。

## 同步与交付

每轮开工先确认目录/分支、保留现有修改，工作区干净后 `git merge --no-edit main`，再读本文件与自己的提示词。交付前再次同步 main，并运行后端测试。语音窗口额外验证 `git merge-base --is-ancestor c8be17c HEAD` 返回 0。

公共接口变化写入各自 handoff：提议的字段/方法、调用示例、默认值、失败行为、验证结果。为不依赖主窗口即可继续开发，内部工厂、只读诊断快照、独立显式验收入口应先完成。不要自行变更公共 DTO、接口地址或 POM。

本地替身验证默认不访问云服务。真实短句沿用每供应商每次验收最多 3 次、显式启用的范围；更长收费测试先列计划与预算。签名来源、房间、凭据或云权限缺失要明确说明，但继续完成不依赖这些条件的工作。不能复制主项目 IDEA 密钥到其他工作树，也不能要求用户在聊天粘贴凭据。

完成后创建小范围本地提交，交接基线 SHA、实现 SHA、测试命令/结果、真实与替身证据、剩余条件。不能把文档提交自己的 SHA 写入自身造成循环；报告实现提交 SHA，最后的文档提交 SHA 放在最终回复。仅主窗口合并回 main。

建议主窗口集成顺序：运行诊断 → Token 工厂与状态接线 → 抖音来源接线 → Vue 配置/诊断 → 整体故障恢复测试；未具备签名或真实接收证据时继续明确禁用 DOUYIN，不用假就绪替代。

## GitHub 上传是独立任务

[上传提示词](prompts/github-publish.md)只审核、构建并上传已合并 main 的指定提交，不承担第四份业务开发。现在启动就上传当前稳定版；要上传第三轮最终版，应在本窗口合并验收后再执行。默认只推 main，不推未合并分支、所有 refs 或工作树目录。

上传准备采用独立发布工作树，避免与当前窗口同时修改主目录。发现需改忽略规则、清理历史或修改源码时给出具体结果，普通修补在发布分支提交后交主窗口合并；不得强推、改写历史或批量删除。
