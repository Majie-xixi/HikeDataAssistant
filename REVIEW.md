# 代码审核交接

审核基线：`cfadba8`（Android 环境验证骨架）  
审核日期：2026-09-29

## 待处理

### P1：Gradle Wrapper 使用本机绝对路径

位置：`gradle/wrapper/gradle-wrapper.properties:3`

审核基线中的 `distributionUrl` 指向 `D:/DevEnv/downloads/gradle-8.11.1-bin.zip`，其他机器通常没有此文件。当前工作区已改成 HTTPS 发行地址，但尚未看到不依赖 D 盘文件的构建验证。

处理要求：将仓库中的地址改为可公开获取的 Gradle 8.11.1 发行包地址，或团队可访问的镜像；本机离线包路径只保留在个人环境中。请在不依赖该 D 盘文件的环境下运行 `gradlew.bat assembleDebug`，记录结果。

状态：待复核（已提交修复 `f6b0d6b`，构建验证通过，见下方开发记录）。

开发 agent 处理记录（2026-09-29）：

- `distributionUrl` 改为腾讯云镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip`。官方地址 `services.gradle.org` 重定向到 `release-assets.githubusercontent.com`，在本机网络两次实测均连接/TLS 超时（10s 与 120s 超时各一次），故按"团队可访问的镜像"处理。
- 增加 `distributionSha256Sum=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6`（services.gradle.org 公布值；本地官方 zip 实测 sha256 与其一致），防止镜像内容被替换。
- `networkTimeout` 10s → 120s。
- 验证结果：构建期间将 `D:/DevEnv/downloads/gradle-8.11.1-bin.zip` 临时改名移走，`gradlew.bat assembleDebug` 从镜像下载发行包（sha256 校验通过）后 **BUILD SUCCESSFUL in 1m 2s（exit 0）**，随后恢复本地 zip。完整日志：`D:/DevEnv/tmp/p1_verify_build3.log`（镜像外环境可通过 `gradlew.bat assembleDebug` 复现，首次运行会重新下载约 130MB 发行包）。

## 本轮审核范围

### 待审：v0.1 功能批次（提交 `081e897`，2026-09-29）

开发 agent 已完成开发文档阶段 1-3 的功能批次，共 32 个文件（24 个主源码 + 7 个测试 + 配置/清单）。构建与测试结果：`gradlew assembleDebug testDebugUnitTest` 全绿，**64 个单元测试、0 失败**；`app/build/outputs/apk/debug/app-debug.apk` 正常产出。请按 `Hike_Copilot_v0.1_Development.md` §4/§6/§8/§9/§10 逐项复核。

实现要点（自述，供复核定位）：

- 数据模型：`data/Reading.kt`、`data/HikeSnapshot.kt`（含模拟标记 isSimulated 全链路）
- 会话：`session/HikeSession.kt`（单调时钟、暂停段）；`session/SessionRepository.kt`（行式持久化、进程被杀恢复样本）；`session/HikeSessionController.kt`
- 心率：`heartrate/HeartRateMeasurementParser.kt`（0x2A37）；`heartrate/HeartRateEngine.kt`（15s/60s 新鲜度、5 分钟均值覆盖规则、10 分钟趋势、暂停过滤、模拟隔离）；`heartrate/BleHeartRateProvider.kt`（0x180D 扫描/连接/CCCD 订阅/重连退避）；`heartrate/SimulatedHeartRateProvider.kt`（仅 debug）
- GPX：`gpx/GpxParser.kt`（拒 DTD/实体、trkseg/rte、多段不拼接）；`gpx/GpxProcessor.kt`（里程、50m 分桶平滑+首末锚点、爬升、前方爬升段、反向）
- 路线匹配：`route/RouteContextResolver.kt`（≤30s/≤30m 门槛、走廊投影、折返/交叉歧义）
- 快照与分享：`snapshot/SnapshotBuilder.kt`、`snapshot/ShareTextBuilder.kt`（缺失显式说明、不外发坐标）
- 定位：`location/LocationClient.kt`（按需一次、被动监听、API 29/30+ 双路径）
- 前台服务：`service/HikeSessionService.kt`（connectedDevice 类型、通知停止入口）
- UI：`ui/HikeViewModel.kt`、`ui/HikeApp.kt`（单主页面、扫描/选段/分享预览/覆盖确认对话框）

已知边界（非缺陷申报）：GT5 实机验证（阶段 0）与户外联合测试（阶段 4）需要用户手机与手表，尚未执行，未宣称通过；`HikeSessionService` 通知文案为静态文本，心率数值未进通知（可后续增强）。

---

（以下为此前记录的历史审核范围说明）

当前仓库只有 Compose 环境骨架，已有 debug APK，但本轮未重新构建。GT5 心率、GPX 导入、定位、状态快照和分享功能尚未进入本轮审核；这些功能完成后需按 `Hike_Copilot_v0.1_Development.md` 逐项复核。

协作方式：开发 agent 完成一批改动后提交或保存；审核方读取差异并在此文件写明问题；开发 agent 修复后注明对应提交和构建结果，再由审核方复核并关闭问题。避免双方同时修改同一代码文件。

## 自动审核流程

1. 开发 agent 每完成一批可运行改动就保存并提交；请在开始下一批工作前读取本文件的待办。只需向它交代一次这条规则。
2. Codex 每 15 分钟检查本工程的 Git 提交和工作区差异。若没有新代码，保持安静。未提交且仍在编辑的文件，待写入稳定后再审核，避免审到半成品。
3. 发现问题时，Codex 在本文件记录优先级、文件行号、影响、修复要求和验收方法，并通知用户。审核方只改本交接文件，不与开发 agent 同时修改产品代码。
4. 开发 agent 修复后，在对应问题下补充提交号与构建或测试结果；Codex 在下次定期检查中复核，确认后标记“已解决”。未验证的问题保持“待复核”。
5. 当前审核基线为 `cfadba8`。后续每次审核都记录新基线，避免重复报告。同一问题只有状态变化或出现新证据时才通知用户。

这套流程按定时检查运行，不是文件一保存就立即触发。另一家公司的 agent 无法直接接收 Codex 消息，因此它需要按第 1 条定期读取本文件。

## 变更检测细则

- 上次已审状态：提交 `cfadba8`，当时没有其他产品代码改动。当前未提交的代码属于下一轮待审内容。
- 每轮读取 Git 当前提交号、已跟踪文件的实际差异，以及未跟踪源码文件的路径和内容哈希；排除 `REVIEW.md`、`.gradle/` 和 `build/` 等生成内容。
- 审核完成时记录本轮提交号和代码内容指纹。下一轮指纹相同则跳过，即使 `git status` 仍显示未提交文件也不重复报告。
- 文件刚写入或仍在变化时等待下一轮；审核只覆盖已经保存到该目录的内容，无法看到开发 agent 尚未保存的编辑。
