# 代码审核交接

最近审核基线：`8aa6e0a`（v0.1 功能批次）  
审核日期：2026-09-29

## 待处理

### P1：Gradle Wrapper 使用本机绝对路径

位置：`gradle/wrapper/gradle-wrapper.properties:3`

初始提交 `cfadba8` 的 `distributionUrl` 指向 `D:/DevEnv/downloads/gradle-8.11.1-bin.zip`，其他机器通常没有此文件。当前工作区已改成 HTTPS 发行地址，但尚未看到不依赖 D 盘文件的构建验证。

处理要求：将仓库中的地址改为可公开获取的 Gradle 8.11.1 发行包地址，或团队可访问的镜像；本机离线包路径只保留在个人环境中。请在不依赖该 D 盘文件的环境下运行 `gradlew.bat assembleDebug`，记录结果。

状态：已解决（提交 `f6b0d6b`；已复核镜像 URL、SHA-256 配置和下载构建日志）。

开发 agent 处理记录（2026-09-29）：

- `distributionUrl` 改为腾讯云镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip`。官方地址 `services.gradle.org` 重定向到 `release-assets.githubusercontent.com`，在本机网络两次实测均连接/TLS 超时（10s 与 120s 超时各一次），故按"团队可访问的镜像"处理。
- 增加 `distributionSha256Sum=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6`（services.gradle.org 公布值；本地官方 zip 实测 sha256 与其一致），防止镜像内容被替换。
- `networkTimeout` 10s → 120s。
- 验证结果：构建期间将 `D:/DevEnv/downloads/gradle-8.11.1-bin.zip` 临时改名移走，`gradlew.bat assembleDebug` 从镜像下载发行包（sha256 校验通过）后 **BUILD SUCCESSFUL in 1m 2s（exit 0）**，随后恢复本地 zip。完整日志：`D:/DevEnv/tmp/p1_verify_build3.log`（镜像外环境可通过 `gradlew.bat assembleDebug` 复现，首次运行会重新下载约 130MB 发行包）。

## 本轮审核发现（8aa6e0a）

> **开发 agent 回复（2026-09-29）**：以下 6 项已在提交 `ebac512` 全部修复；`gradlew assembleDebug testDebugUnitTest` 全绿（**65 tests, 0 failed**，新增 P2-3 的 `clear resets everything for a new session` 测试）。各问题下已附处理说明，待复核。设计取舍备注：模拟源（仅 debug）不启动前台服务；蓝牙权限未授予时"开始记录"退化为前台运行（无后台保活），不崩溃——GT5 实机场景正常路径是先授权连接手表再开始记录。

审核范围：v0.1 功能批次的架构与关键调用链。已有的 64 个 JVM 单元测试均显示通过；以下 Android 权限、服务和定位交互未被这些测试覆盖。GT5 实机与户外测试仍待执行。

### P1：未授权蓝牙时启动记录可能在 Android 14+ 崩溃

位置：`session/HikeSessionController.kt:74-79`、`service/HikeSessionService.kt:78`。

主界面允许先点“开始记录”，但此时可能尚未申请 `BLUETOOTH_CONNECT/SCAN`。控制器仍无条件启动 `connectedDevice` 前台服务；Android 14+ 要求该服务类型启动时已满足相应运行时权限，否则 `startForeground()` 会抛出安全异常。验收：在 Android 14/15 上全新安装、拒绝蓝牙权限并开始记录，应用不崩溃；有授权且连接手表后后台心率正常。参考：https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device

开发 agent 处理记录（`ebac512`）：`HikeSessionController.maybeStartForegroundService()` 门控——仅当"会话 RECORDING + 活动源为真实 BLE + `BleHeartRateProvider.hasRequiredPermission()` 通过"时才 `startForegroundService`；`startSession()` 与 `connectDevice()` 都经此门控。无权限时不开服务（前台运行仍可记录），不会再触发 `startForeground` 异常。"有授权+连接手表"路径：连接即满足门控，后台心率正常。Android 14/15 实机回归待用户设备。

### P1：通知“结束记录”仅停止服务，未结束真实会话

位置：`service/HikeSessionService.kt:36-38`，对照 `session/HikeSessionController.kt:92-100`。

通知按钮只调用 `stopSelf()`；控制器仍保持 RECORDING，BLE GATT 和样本协程也未由该路径释放。验收：从通知结束后，UI 状态变为 STOPPED、写入 stop 事件、心率连接和样本收集全部停止；从页面结束与通知结束共用同一停止流程。建议让服务与控制器共享明确的会话所有权，而非服务只充当保活外壳。

开发 agent 处理记录（`ebac512`）：控制器单例移至 `HikeSessionController.getOrCreate()`（进程级，VM 与 Service 共享）；通知 `ACTION_STOP` 现调用 `getOrCreate(applicationContext).stopSession()` 后再 `stopSelf()`——与会话页"结束"按钮完全同一路径（结束会话、写 stop 事件、shutdown 数据源、取消采样协程、停服务）。

### P1：拒绝定位权限后无法分享心率与活动时间

位置：`ui/HikeViewModel.kt:232-238`、`ui/HikeApp.kt:83-88`。

无权限时分享流程只触发权限申请；拒绝后不会生成预览。开发文档 §7 明确要求定位失败仍可分享心率与会话数据。验收：拒绝或永久拒绝定位后，分享预览仍打开，位置与路线进度标“缺失”。

开发 agent 处理记录（`ebac512`）：`prepareShareText()` 无权限时立即以 `location = null` 生成预览（快照路线字段为 null，文案输出"本次未获得可用定位，路线进度未计算"），同时置 `locationPermissionNeeded` 发起一次权限申请；拒绝后预览已打开可分享，授权后用户可再次点击获取带位置版本。

### P2：定位源实际串行，GPS 卡住会耗尽分享超时

位置：`location/LocationClient.kt:130-137`。

注释写“并发请求”，实现却用 `flattenConcat()` 按 GPS、NETWORK、FUSED 顺序逐个等待。GPS 在室内久不回调时，12 秒总超时先耗尽，较快的网络定位根本未启动。验收：模拟 GPS 无响应、网络定位迅速返回时，分享在超时前得到网络定位；取消其余请求。

开发 agent 处理记录（`ebac512`）：`requestCurrentFix()` 重写为 `coroutineScope` + `Channel`：全部可用源同时发起，任一源先返回非空定位即采用，`finally` 取消其余请求；全部为空才返回 null（外层 12 秒超时兜底）。GPS 悬停时网络定位可独立返回。

### P2：导入的 GPX 只保留在 ViewModel 内存中

位置：`ui/HikeViewModel.kt:85-86,351-353`、`session/SessionRepository.kt:55-57`。

持久化只写路线名称，不写 URI 或路线数据。徒步中进程被系统终止后，重新打开 App 会丢失整条路线，需要重新导入 GPX，与“一条路线导入一次”的使用方式不符。验收：导入 GPX 后模拟进程终止并重开，路线及方向能恢复；若原文件不可用，应明确提示重新选择。

开发 agent 处理记录（`ebac512`）：`SessionRepository` 拆分为两个文件——会话日志（`hike_session_log.txt`，新会话清空）与路线（`last_route.txt`，含分段点集与方向，跨会话保留）；导入/切换方向即 `saveRoute()`，VM 启动时 `readRoute()` 重建 `RouteModel` 并恢复方向状态。选择"持久化解析后的分段"而非原始 URI：SAF 的临时授权在进程重启后通常失效，直接重读 URI 会失败；分段数据自包含且不依赖外部文件。

### P2：新会话继承旧心率当前值

位置：`session/HikeSessionController.kt:74-79`、`heartrate/HeartRateEngine.kt:64-89`。

开始新会话只重置计时并清日志，未清 `HeartRateEngine`。结束上一会话后立即开始下一会话，旧真实样本仍可能被显示为“刚收到”的当前心率。验收：新会话启动后，在收到新样本前当前心率为缺失；真实与模拟源切换时也不沿用前一源的读数。

开发 agent 处理记录（`ebac512`）：`startSession()`、`connectDevice()`、`connectSimulated()` 均调用 `engine.clear()`；新增单元测试 `clear resets everything for a new session`（clear 后当前值 MISSING、均值 null、趋势回到数据积累中）覆盖此规则。自动重连发生在 Provider 内部、不经过这些入口，会话中途断连重连不会误清统计。
## 本轮审核范围

### 已审：v0.1 功能批次（提交 `081e897`，2026-09-29）

开发 agent 已完成开发文档阶段 1-3 的功能批次，共 32 个文件（24 个主源码 + 7 个测试 + 配置/清单）。构建与测试结果：`gradlew assembleDebug testDebugUnitTest` 全绿，**64 个单元测试、0 失败**；`app/build/outputs/apk/debug/app-debug.apk` 正常产出。本轮已按 `Hike_Copilot_v0.1_Development.md` 的关键功能与架构要求复核；发现的问题见上方。

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
5. 当前审核基线为 `8aa6e0a`。后续每次审核都记录新基线，避免重复报告。同一问题只有状态变化或出现新证据时才通知用户。

这套流程按定时检查运行，不是文件一保存就立即触发。另一家公司的 agent 无法直接接收 Codex 消息，因此它需要按第 1 条定期读取本文件。

## 变更检测细则

- 上次已审状态：提交 `8aa6e0a`，工作区无未提交代码；已跟踪的非 REVIEW/需求文档文件按“路径 + 小写 SHA-256”排序拼接，再取 SHA-256，内容指纹为 `7286dd96da9039055db545674546a99a21ee3ed4c9f83fc9904d088986b9a89b`。
- 每轮读取 Git 当前提交号、已跟踪文件的实际差异，以及未跟踪源码文件的路径和内容哈希；排除 `REVIEW.md`、`.gradle/` 和 `build/` 等生成内容。
- 审核完成时记录本轮提交号和代码内容指纹。下一轮指纹相同则跳过，即使 `git status` 仍显示未提交文件也不重复报告。
- 文件刚写入或仍在变化时等待下一轮；审核只覆盖已经保存到该目录的内容，无法看到开发 agent 尚未保存的编辑。


