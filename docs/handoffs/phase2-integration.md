# 第二轮主窗口集成 · 2026-10-31

## 合并范围

主项目 `bigfish-java` / `main`，三个工作树保留不动，没有推送远端。

| 分支 | 交付头提交 | main 合并提交 |
|---|---|---|
| feature/backend-core | 0962b82 | faf76c1 |
| feature/frontend-stage（本轮 Java TTS） | 467d8ab | 8ef0231 |
| feature/douyin-collector | 105304d | 1ff0868 |

均为 `--no-ff`，无冲突。后续各窗口开始新任务前，先确保本工作树已提交且无未保存改动，再将最新 main 合入自己的分支；不要复制目录覆盖。

## 集成内容

- StartSessionRequest 新增可省略的 speechMode，默认 LOCAL_TEST_TONE；显式 null/未知值拒绝。回复选 MOCK / DEEPSEEK，语音选 LOCAL_TEST_TONE / ALIYUN_NLS，采集来源目前仍为 MOCK。
- ProviderServices 读取后端环境变量，前端只收到配置存在性、启用开关、验证状态和稳定错误码。网页轮询不访问外部服务。
- `/api/v1/providers/deepseek/check` 显式检查目录中的已保存模型；鉴权、模型存在性和生成分开报告，生成始终 NOT_CHECKED。实际生成由模拟评论触发。
- 管理页和舞台独立选择服务，运行中锁定，跨页面回显实际会话模式。缺配置时禁用真实选项，本地模拟可正常运行。
- 阿里云 NLS 接入会话，支持音色显示在页面；旧 Edge 音色返回 NLS_UNSUPPORTED_VOICE。目前只使用静态 Token，未接 CreateToken 自动刷新；更换凭据后重启后端。
- WAV 先读入受限内存，再在 finally 单文件 release；失败、过长音频也释放，取消调用适配器 cancel 清理迟到文件。测试音可复用。
- 模型整体期限 20 秒，NLS 10 秒，内部重试最多两次并共享预算。主会话限制音频 25 秒；STARTED 等待 5 秒，完成期限为 duration + 5 秒；下发前预留 duration + 10 秒 TTL。
- RuntimeView 新增 lastError/lastErrorAt，区分 MODEL_*、NLS_*；新会话重置，普通任务成功不抹掉最近错误。

## 验证结果

- `./scripts/maven.sh -B -ntp test`：136 项，135 通过、1 项真实 NLS 凭据测试跳过，0 失败/错误。覆盖本机 SSE/HTTP、采集协议和房间解析替身、REST/WebSocket、文件释放和超时。
- `cd frontend && npm test`：36 项全通过。`npm run build`：类型检查与生产构建通过；修复了首次构建发现的下拉框标签错误。
- `./scripts/maven.sh -B -ntp -Pbundle-ui -DskipTests package`：通过，测试已单独执行。
- 单 JAR 在 127.0.0.1:18080 启动，本次显式 BIGFISH_ALLOW_PAID=false。页面确认 DeepSeek/NLS 为 MISSING，真实选项不可选。
- 浏览器：启动 MOCK → 舞台 HELLO/LEASE/READY → 模拟礼物/评论字幕正确，Web Audio 音量约 11%，两段均出现 STARTED/COMPLETED → 停止会话后静音断开、撤销正常。
- `node scripts/smoke.mjs`：health、能力状态、打包页面、SQLite 保存/读取/409 通过；原配置内容恢复，版本 v5→v7。
- 沙箱拒绝端口访问与 JVM attach；按权限流程在沙箱外重试成功，未修改全局配置。

## 未验收与下一步

1. 真实 DeepSeek、NLS、抖音请求数均为 **0**。未读取或迁移旧 Luna 凭据，没有付费请求。先按 README 在 IDEA 环境变量设置凭据，以模拟事件做每服务最多 3 次短请求验收。
2. 抖音分支完成房间解析、连接提供器、协议处理及本地测试，但 **没有可使用的 DouyinSigner 实现**。主会话仍返回 DOUYIN_UNAVAILABLE，状态 SIGNER_NOT_IMPLEMENTED；真实房间到主会话生命周期接线也待此依赖到位。可注入的签名接口不代表真实可用。
3. Token 自动刷新、超过 25 秒语音分段、真正 Live2D、OBS 后台播放及 20 分钟以上稳定性未验收。当前超长文本/音频明确失败；建议人设要求每次一两句、尽量不超过 60 字。
4. 本地故障测试证明错误可区分、任务有期限，不证明供应商长期可用；收费长测需另定计划。
5. 旧前端依赖审计告警仍由后续统一升级处理，本轮未更改依赖版本。

页面：`http://127.0.0.1:18080/admin`、`http://127.0.0.1:18080/stage`。当前窗口启动了本地服务，改用 IDEA 启动前应停止此进程，避免端口冲突。
