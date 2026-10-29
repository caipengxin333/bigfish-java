# 主项目集成交接 · 2026-10-30

## 合并结果

主目录 bigfish-java，分支 main。三个 --no-ff 合并提交：
- 4a866ec：feature/backend-core（来源 89303e9）
- ed46713：feature/frontend-stage（来源 1f6aa01）
- 0135637：feature/douyin-collector（来源 db2668c）

无合并冲突。原三个开发工作树未重置、删除或同步覆盖；旧 AI-Vtuber 未改动。未推送远端。接口集成代码在合并提交之后单独提交，可用 `git log -1` 查看最终 SHA。

## 接线

- 公共 StartSessionRequest / RuntimeView / TypeScript / OpenAPI 固化。
- sessions/start/stop、runtime、events/simulate、session/replay 管理接口实现，停止幂等、旧会话返回 409。
- SessionService 统一管理引擎与 MockCollector。显式回放原创人工输入，累计礼物 1→3→3，去重后增量 1+2，感谢总量 3。
- Vue 管理页、舞台直接使用真实 HTTP/WebSocket；显示明确的 MOCK 数据源、MOCK 回复与 LOCAL_TEST_TONE。舞台内测试按钮避免切换页面触发静音。
- Java 同源音频下载 / Web Audio / 字幕 / RMS 嘴型 / STARTED / COMPLETED 全链路已实际运行。
- 未启动会话时 NO_SESSION 立即提示，不再等 8 秒握手超时。开发代理保留同源检查，未配置任意 Origin。

## 验证

- `./scripts/maven.sh -B -ntp test`：67 项全部通过，无跳过，包含新增 REST 联合测试与采集回放→音频→回执。
- `npm --prefix frontend test`：34 项全部通过，包含最终 NO_SESSION 修复。
- `npm --prefix frontend run build`：类型检查和构建成功。
- `./scripts/maven.sh -B -ntp -Pbundle-ui -DskipTests package`：单 JAR 构建成功，打包前已执行上述测试。
- `node scripts/smoke.mjs`：打包页、配置持久化/409/恢复、能力声明通过，原配置内容已恢复。
- 浏览器 18080：管理页启动→舞台连接/启用→评论与礼物播放→采集器连击回放→停止，通过。
- 浏览器 15173 → 18080：Vue 开发代理下 HTTP/WS/音频下载与回执，通过。
- 截图证据 `/tmp/bigfish-integration-stage.png`（本机临时文件，不入 Git）；详细过程和限制见 ../validation.md。

## 运行和下一阶段

主项目可用一个 JAR 运行，页面 /admin 和 /stage；也可 IDEA 启动 BigfishApplication + Vue 开发服务。默认端口 18080 / 15173，SQLite runtime/app.db，采集器无需单独启动。

本轮完成的是模拟闭环集成，不是正式直播上线：真实 DOUYIN 尚无房间解析/签名连接提供器，接口返回 422；DeepSeek 适配仅用本地 SSE 测试，未迁移 Key 或调用付费接口；声音为测试音，不是人声。Live2D、OBS 后台播放、真实外网稳定性与 20 分钟以上运行尚未验收。已有依赖审计告警未在本轮升级，正式发布前需统一处理。

下一阶段先选定并接入真实 TTS，再验证 DeepSeek，最后接入经确认来源的抖音连接提供器和真实直播间。其他窗口继续工作前，在各自干净的工作树合入 main 获取公共契约；不要重复 cherry-pick 已合并提交。
