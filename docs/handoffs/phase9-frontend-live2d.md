# 第九轮前端：Live2D 舞台接入

日期：2026-11-02（Asia/Shanghai）。工作树 `bigfish-worktrees/frontend`；分支 `feature/frontend-stage`。

## 基线与提交

- 开工目录、分支正确，工作区干净，原 HEAD `d7435fdd84fe3d74afb9b9b4b1579d19d5292c3c`。阅读 AGENTS、task-boundaries、playback-protocol、最新集成交接以及模型 SOURCE.md。
- 按用户要求 `git merge --no-edit main`，首次快进至 `15be0b96c0e71fc8eb29c6320f33fe03ac74cb8a`。保留故障摘要、停止恢复、音频中断、独占租约、会话隔离和原 101 项测试。
- **最终实现提交：`7cb15345ee6ed146ce7cc9eda5fad7dca926fafb`**，`feat(frontend): add isolated Cubism Live2D stage avatar`。
- 收尾时 main 仅新增项目许可证说明 `d8fccd2376caf603123c056d81ba42a7ccaa1490`。实现提交后工作区干净，再执行 `git merge --no-edit main`，同步提交 **`a027dbb268cae646e0d6706884eb7a4066e2070d`**；前端代码未随这次同步改变。`git merge-base --is-ancestor main HEAD` 返回 0。
- 本文件另行提交，交接文档提交 SHA 见最终回复，避免自引用。没有更新 main、合入其他功能分支、推送、PR、reset、stash、删除或联系其他窗口。

## 修改范围

只实现 `frontend/`，交接新增本文件。原 Java、contracts.ts、WebSocket 格式、音频播放算法和会话控制未修改。工作树忽略的 `runtime/avatar-assets` 仅放用户允许的本机外部资源。

| 文件 | 改动 |
| --- | --- |
| `src/components/StagePage.vue` | 默认注入 StageAvatar，仍支持 stageAvatarKey 替换；调整形象及 MOCK 资源说明 |
| `src/components/StageAvatar.vue` | SVG/Live2D 切换、大小/位置/背景/默认设置、手动表情、作者署名；设置区默认折叠 |
| `src/components/Live2DAvatar.vue` | 独立渲染宿主、加载/错误/占位回退、可见重试入口；卸载取消与晚到回调隔离 |
| `src/stage/avatar-settings.ts` | `bigfish.browser.avatar.v1` 白名单与版本校验，仅保存 mode/size/x/y/background；不保存表情、URL、配置或密钥 |
| `src/stage/live2d/assets.ts`、`core.ts` | 固定路径、模型清单校验、受限相对路径、流式大小限制、超时与取消、安全错误码、固定 Core SRI |
| `src/stage/live2d/runtime.ts`、`lifecycle.ts`、`gl-resources.ts` | 官方 Framework 适配；LipSync/EyeBlink 映射、呼吸/轻微待机、原物理效果、表情管理、嘴型最后覆盖；模型/纹理/帧缓冲/RAF/监听器释放 |
| `vendor/cubism-framework/`、`vendor/cubism-core-types/` | 固定官方 Framework 原始源码和许可，以及编译期 Core 声明/许可；没有可执行 Core 或模型素材 |
| `scripts/build-cubism.mjs`、`vendor/cubism-framework/tsconfig.build.json`、`package.json`、`tsconfig.json`、`.gitignore` | SDK 单独编译到忽略的 `.cubism-cache`；应用继续严格类型检查。没有新增 npm 包或更改依赖版本，lockfile 无需变更 |
| `vite.config.ts` | SDK 本地别名；产物保留 Framework LICENSE/SOURCE；`/avatar-assets` 使用 BIGFISH_API_TARGET，缺省 18082 |
| `tests/avatar.test.ts`、`avatar-fixture.html`、`avatar-visual.html` | 11 项新增自动回归、真实渲染/人工音频本地验收页、375px 容器；夹具不进入构建产物 |

## 渲染与安全边界

- 首次默认 SVG。读取/保存形象偏好及 storage 事件不会启动互动或请求 TTS；持久化为 Live2D 时页面仅加载本机形象资源。
- 唯一音频输入为已有 `mouthLevel`、`speaking`、`connected`。Live2D 没有 AudioContext、播放器、TTS 或后端轮询。仅 speaking 且 connected 时跟随实际音量，音量为零/停止/取消/断线/失租立即归零，不等待下一帧。
- LipSync/EyeBlink 从 model3.json 的 Groups 读取并与 MOC 真实参数匹配，缺映射显示警告，不假定所有模型都是 ParamMouthOpenY。此模型实际为 ParamMouthOpenY 与 ParamEyeLOpen/ParamEyeROpen。
- 以模型默认参数为每帧基准，依次处理眨眼、可选呼吸/角度参数、手动表情、模型 physics，最后覆盖嘴型。模型未声明 motion 文件，本轮待机是小幅程序运动，不复制旧 bundle 或旧状态轮询。
- 表情只加载明确选择的模型声明文件，恢复默认会废弃晚到表情请求。名称/路径包含水印、署名、版权、watermark、credit、copyright 的表情禁用且不可通过适配层选择；本机的“13 水印消除”不加载。原水印和额外作者署名保留。
- 固定同源 model/core 地址；模型引用拒绝绝对地址、`..`、百分号转义、查询串等。数据请求禁止重定向。清单 1 MiB、MOC 8 MiB、physics 2 MiB、每张纹理 40 MiB、表情 256 KiB；最多 4 张纹理/32 个表情，资源请求 30 秒超时。Core 15 秒、图像解码 15 秒超时，失败需用户重试。
- 保持整幅模型画布可见，位置在可用留白内移动；不裁切原素材。背景“透明”作用于形象区域，外层舞台仍沿用原页面样式。
- WebGL 上下文丢失/不可用、资源缺失/超时/非法、MOC 不兼容等回退 SVG，不关闭声音连接。R4 遮罩纹理/帧缓冲增加适配层显式清理，官方源码保持原样；最后释放私有 WebGL 上下文。Core 全局脚本与 SDK 模块是页面级缓存，模型退出不尝试卸载已经执行的 JS。

## 固定 SDK 与许可来源

- 官方 SDK：**Cubism SDK for Web 5-r.4**，Framework tag commit `8df84780f2aa1298f3b30965cdae143e049f3c8e`。
- [官方 SDK 下载页](https://www.live2d.com/en/sdk/download/web/)、[固定 ZIP](https://cubism.live2d.com/sdk-web/bin/CubismSdkForWeb-5-r.4.zip)、[Framework 固定源码](https://github.com/Live2D/CubismWebFramework/tree/5-r.4)、[官方 Web SDK 文档](https://docs.live2d.com/en/cubism-sdk-manual/cubism-sdk-for-web/)。使用固定版本，不使用 latest。选择 R4 的 WebGL 路线，避免引入新版外部 shader/WebGL2 构建迁移。
- ZIP SHA-256：`d78904d908bd232b800219e01732e4ea2f0562b5e9f35a2670742a1c16d22942`。
- 外部 Core：**05.01.0000**（浏览器实读 `0x05010000`）；SHA-256 `25ae938cb4fe282ce189b357bcc97e603d1e1f7ec78bf04150d401c23cdc792f`，SRI `sha256-Ja6TjLT+KCzhibNXvMl+YD0eH37Hi/BBUNQBwjzceS8=`。更新 Core 需同时重新验证版本与 SRI，不能静默换文件。
- Framework 为 [Live2D Open Software License](https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html)；Core 和声明为 [Live2D Proprietary Software License](https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html)。不是 MIT，也不因主项目 Apache-2.0 而改变。原许可/版权头保留，Framework LICENSE/SOURCE 输出到 dist/licenses；最终第三方汇总、发行许可审核交主窗口。
- 核对 46 个 Framework TS 文件与官方 ZIP 完全相同。未复制 Luna 的 bundle.js。

## 本机外部资源

本工作树资源根：`/Volumes/ZHITAI_MAC/创业项目测试/大肥鱼直播/bigfish-worktrees/frontend/runtime/avatar-assets`（被现有 Git 规则忽略）。布局：

```text
runtime/avatar-assets/
  sdk/live2dcubismcore.min.js
  sdk/LICENSE.md
  models/BigFish/
    SOURCE.md
    BigFish.model3.json
    大肥鱼.moc3
    大肥鱼.physics3.json
    大肥鱼.cdi3.json
    大肥鱼.8192/texture_00.png
    ... 原始表情与来源文件
```

- URL 对应 `/avatar-assets/sdk/live2dcubismcore.min.js` 和 `/avatar-assets/models/BigFish/BigFish.model3.json`，保持 model3 相对目录结构。
- 模型先读旧项目 SOURCE.md，再复制到本机隔离资源目录；旧项目只读。24 个复制文件逐文件 SHA-256 与来源一致，没有改写贴图或模型。MOC3 header version 5，已通过官方 Core 一致性检查及实际渲染。
- 贴图 8192×8192，30,009,916 bytes；RGBA 解码约 256 MiB，另有 GPU/浏览器开销。检查 MAX_TEXTURE_SIZE 和上传错误，不自动缩图；低显存设备可回退 SVG。
- 模型作者 **Mao绒绒家园**，[原发布](https://www.bilibili.com/video/BV1yep465EQz)；旧 SOURCE 记录的上游仓库 [xkk6663/dsh-live2d-mao](https://github.com/xkk6663/dsh-live2d-mao)，commit `52ef4746fa690c46e2596ea84b872b7af8a5e10c`。只按用户确认的本机非商业测试使用，授权并非 MIT；不得随 GitHub、安装包、前端产物或 JAR 分发。保留 SOURCE 与原水印/署名。

## 验证结果：自动测试与构建

- `npm --prefix frontend test`：**112/112 通过**，保留原 101 项；新增设置白名单、固定路径、映射、受限表情、畸形清单、缺文件/超限/取消/超时、安全文案、嘴型即时关闭、晚到模型释放、重试、RAF 取消/恢复时间上限、纹理/帧缓冲清理。
- `npm --prefix frontend run typecheck`：通过。
- `npm --prefix frontend run build`：通过（vue-tsc + Vite）；Live2D runtime 独立延迟 chunk 约 168 kB / gzip 36.5 kB，不增加初次 SVG 的运行时加载。
- `git diff --check`：通过。检查 dist 的 8 个文件，无 Core JS、model3、MOC、贴图或测试页；模型素材也未进入暂存区。
- 上述命令最后验证的前端文件与最终实现提交一致；后续同步 main 只有许可证文档，没有前端代码变化。

## 验证结果：真实浏览器与替身的区别

浏览器：**Codex in-app browser（本机）**。未单独验证 Safari、Chrome、手机浏览器或其他 GPU；375px 是本机浏览器中的窄 iframe，不等于真手机验收。

- 使用 `scripts/dev-frontend.sh` 启动 15175，Vite 代理读取工作树 env。18082 原为空，启动仅允许 `/avatar-assets/` 的只读 Python 临时静态服务（`/tmp/bigfish-phase9-assets.py`）。**未启动 Java、抖音、DeepSeek 或 NLS；未操作 18080/15173。**
- 正式 `/stage?transport=mock` 页面真实显示模型、原水印、署名；手动表情选择/恢复、大小/位置/背景、恢复默认操作可用；重新打开页面读取偏好而不启动会话。
- `/tests/avatar-fixture.html` 中 Core、MOC、8192 贴图、WebGL、BrowserAudio、StageController 均为实际实现；只有会话传输使用现有 MockStageTransport。音频为内存生成的 4 秒人工测试音，非 TTS。
- 读取 CubismModel 实际参数：播放时嘴型峰值约 **0.659**；自然结束为 **0.000**，回执 STARTED → COMPLETED；500ms 取消/断开后 **playing=false、嘴型=0.000**，没有伪造 COMPLETED。
- 卸载实测：模型释放计数增加、存活纹理由 2 到 **0**，随后 200ms 模型更新增量 **0**；重新挂载可再次显示。
- 故障注入：本地拦截模型 404、WebGL getContext 返回 null，以及真实 `WEBGL_lose_context` 扩展制造上下文丢失。均有对应安全提示、SVG 回退和可见重试入口。模型 404 时人工音频仍获得 STARTED/COMPLETED；撤销故障后用户点击重试恢复模型。
- 桌面 1280/1280、窄屏 iframe **375/375**（clientWidth/scrollWidth），设置区展开后无横向溢出。
- 临时截图：`/tmp/bigfish-phase9-stage.jpg`、`/tmp/bigfish-phase9-mobile.jpg`，仅本机证据，不提交含受限模型的截图。测试结束已恢复 SVG 默认偏好、关闭临时页，并停止本轮 15175 前端/18082 静态服务；外部资源保留供主窗口检查。

## 待集成事项与限制

1. **正式 Java 资源服务未联调**。主窗口提供只读同源 `/avatar-assets/` → 本工作树隔离 `runtime/avatar-assets`，支持中文文件路径、JS/JSON/PNG 正确 MIME，拒绝越界，不随构建打包。前端不需要新 DTO、REST 接口、WebSocket 消息或额外轮询。
2. 主窗口统一处理 THIRD_PARTY_NOTICES/发行许可，不把官方 Framework、Core 或模型许可归入项目 Apache-2.0。模型仅本机测试，不能直接用于可分发产品。
3. 集成后在隔离端口按原脚本启动，用 MOCK + LOCAL_TEST_TONE 验证真实后端任务→原播放器→嘴型。资源就绪前 SVG 及声音应正常使用。前端变更无需 RAM/供应商凭据。
4. CSP 如启用，应允许同源 Core 脚本及本地图像 Blob 解码，并单独验证浏览器 WebGL。SRI 不匹配、硬件能力不足时应保持明确回退，不能解除校验来掩盖失败。
5. 本轮只有固定 BigFish 模型，无任意 URL、上传、OBS 第二播放器或 AI 情绪协议；没有模型动画列表时使用基础待机。尚未做长时运行、峰值内存/GPU 性能剖析、多 WebGL 实例或真实系统休眠验收。
6. 恢复、租约、后台音频判断继续由已有控制器负责；动画 RAF 可以暂停，不能承担心跳或唯一 STARTED 判定。已有自动回归通过，不把资源替身测试写成真实后端全链路完成。
