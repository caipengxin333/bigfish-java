# 框架基线验证记录

日期：2026-10-28。本文件记录骨架交付时的实测，不代表后续业务模块已完成。

## 通过

- Java 21.0.11、Maven 3.9.11、Node 24.16.0。
- `./scripts/maven.sh -B -U -Pbundle-ui package`：BUILD SUCCESS。
- 5 项 Spring Boot + SQLite 集成测试：0 failure / 0 error / 0 skip。
  - 配置读取、写入后再次读取。
  - 旧 expectedVersion 返回 409。
  - 队列容量等非法值拒绝。
  - 未实现能力明确返回 false。
  - 未知字段、缺失 boolean、null 数字拒绝。
- `cd frontend && npm run build`：vue-tsc 与 Vite production build 通过，锁文件已保存。
- `bash -n scripts/*.sh`：逐个脚本语法检查通过（实际执行时列举明确文件路径）。
- `./scripts/run.sh`：合并后的单 JAR 在 127.0.0.1:18080 成功启动。
- `node scripts/smoke.mjs`：health、bootstrap、/admin、/stage、保存/回读、409 全部通过，测试恢复原设置。
- 浏览器查看 /admin：服务已连接，配置 v3，初始配置可见，未实现功能为待接入。

## 运行产物

- JAR：backend/target/bigfish-server-0.1.0-SNAPSHOT.jar（忽略，不提交）。
- 数据库：runtime/app.db（忽略，不提交）。
- 日志：.cache/backend-build.log、.cache/package-build.log、.cache/server.log（忽略，不提交）。
- 前端构建：frontend/dist（忽略，不提交）。

## 环境问题及处理

原 JAVA_HOME 对 Maven 无效，脚本在 macOS 上定位 Java 21。全局 Maven 使用旧 HTTP 镜像与系统目录缓存，本项目改用独立 HTTPS settings 和 .cache/maven，不改全局配置。一次下载 TLS 握手中断后重试成功，未关闭证书验证。本机端口监听/访问及下载在沙箱外获准执行。

## 未验证 / 未实现

没有接入真实抖音、模型或 TTS；没有调用付费服务；没有真实 Live2D 模型；播放租约和 WebSocket 协议仅有约定。没有做长时间直播测试。三个子任务尚未开始，也未自动创建任何 Codex 对话。
