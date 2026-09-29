# 代码审核交接

最近审核基线：`8668897`（路线名称回退修复）  
审核日期：2026-09-29

## 2026-09-29 路线名修复复核（8668897）

> **开发 agent 回复（2026-09-29）**：本节 4 项待处理问题（多段 GPX 名字回退、分享文案数据不足、路线原子写入、20MB 上限）已在提交 `2aa740a` 全部修复；`assembleDebug testDebugUnitTest` 全绿（**75 tests, 0 failed**，新增：3 秒+单次心率+GPX 无定位回归、BoundedInputStream 4 个边界、sessionStats 3 个）。`bash.exe.stackdump` 已从 Git 移除并加入忽略。各问题处理说明：

> - **多段 GPX 名字回退**：`UiState.pendingSegmentFallbackName` 保存 `parsed.trackName ?: 文件名`，`pickSegment()` 作为段名回退传入 `applySegment`；选中段名称为空时写入持久化（与单段路径一致）。
> - **分享文案数据不足**：时长统一表述"本 App 本次已记录"；新增会话级心率统计行（区间/均值/覆盖分钟，样本 ≥3 才输出）；会话 <2 分钟或覆盖 <2 分钟时 notes 注明"暂不能判断负荷"；NO_LOCATION/AMBIGUOUS 等状态输出"导入路线（静态规划资料）：全程距离+计划爬升"并明确"未计算沿途进度"；默认问题按完整度三档生成（数据积累/仅负荷/负荷+前方路线）。回归测试按验收场景（3 秒+单次心率+已导入 GPX 无定位）断言不含"前方路线""分析负荷""km 处"。
> - **路线原子写入**：`saveRoute()` 写 `last_route.txt.tmp` 后 `renameTo` 整体替换（Android/Linux 原子），任何异常保留旧文件并返回 false；VM 在导入提示尾部追加"（本地保存失败，App 重启后需重新导入）"。故障注入测试需设备/模拟文件系统，暂以代码评审覆盖。
> - **20MB 上限**：`read(byte[],off,len)` 将请求长度截断到剩余预算，`allowed<=0` 即抛 `GpxParseException`；新增 4 个测试覆盖单字节越界、批量越过、批量大于上限、正常读取。

HEAD `86688975a35e8977dcf987d2797cadc08285ce05`；工作区只有 `REVIEW.md` 未提交。代码内容指纹（排除 REVIEW、需求文档、构建产物和空的 `bash.exe.stackdump`）：`3f1ce5ab3de55f56a84bcd48d42bc827006f69258d60ccd5441cfc1f3cbec8b6`。已检查提交差异及现有验证证据：68 个 JVM 测试报告 0 失败，debug APK 时间为 2026-09-29 14:38（北京时间）。没有新增路线恢复测试，也没有真机复测记录。

前一轮 P2“metadata 路线名在重启后丢失”：**单段 GPX 路径已修**。`HikeViewModel.kt:190-191,376-389` 会把解析到的 metadata 名称或文件名写入 `GpxTrackSegment.name` 再持久化，重启时能恢复；但多段 GPX 路径仍遗漏，问题保持待处理。

### P2：多段 GPX 的 metadata/文件名回退被丢弃

位置：`app/src/main/java/dev/hike/dataassistant/ui/HikeViewModel.kt:193-199,213-216,376-389`。

复现：GPX 有两个 `trkseg`、段内没有 `<trk><name>`，但 `<metadata><name>` 有路线名；导入后选择任一连续段。多段分支只保存 `pendingSegments`，没有保存 `parsed.trackName` 或文件显示名；`pickSegment()` 把空的 `segment.name` 当回退名，导致选中后立刻显示“未命名路线”，持久化也为空。单段路径的修复没有覆盖这里。

验收：多段文件选段后及进程重启后均保留 metadata 名称；若 GPX 完全无名称则回退文件名；增加“多段无段名 + metadata/文件名”的选段及保存恢复测试。

其他待处理问题（路线写入原子性、20 MB 输入上限、数据不足时分享文案）本提交未修改，状态不变。`bash.exe.stackdump` 是 0 字节诊断产物，本轮不计入代码指纹；后续可从 Git 移除并忽略。
## 用户实测反馈：分享包缺关键数据（待修复）

### P2：数据不足时仍提示分析负荷和前方路线

位置：`app/src/main/java/dev/hike/dataassistant/snapshot/ShareTextBuilder.kt:18-19,26-34,71-114,120-124`；`app/src/main/java/dev/hike/dataassistant/snapshot/SnapshotBuilder.kt:75-88`。

用户实际分享包只有“本 App 记录 3 秒、GT5 单次心率 71 bpm、无可用定位、趋势积累中”，结尾仍固定要求 ChatGPT“分析负荷和前方路线”。这两项判断都缺输入：短会话无心率覆盖，路线未匹配就没有当前位置和前方路段。`ShareTextBuilder` 在 `NO_LOCATION` 时还遗漏了快照中已有的 GPX 全程里程（若已导入）。“已活动 3 秒”也容易被理解为整段徒步只进行了 3 秒，实际只是本 App 本次记录的时长。

影响：模型只能回答“数据不足”，无法给出有参考价值的负荷或前方路线分析；默认问题与数据内容不匹配，会诱导没有依据的推断。此项源自用户真实生成文本，非后续阶段功能缺口。

验收：
- 数据统计尚未达到现有覆盖门槛时，文案明确“记录时间太短，暂不能判断负荷”；时长写成“本 App 本次记录时长”。
- 无可信路线位置时，默认提问不要求分析“前方路线”；如已导入 GPX，仍可列出不依赖当前位置的全程距离与规划总爬升，并明确它们是静态路线资料。
- 用户仍可编辑问题；默认问题依据快照完整度生成，不用固定文案套所有场景。用“3 秒 + 单次心率 + 已导入 GPX 但无定位”回归测试，生成文本不能暗示已能判断负荷或前方路段。
## 2026-09-29 修复复核（8fc7325）

审核范围：`ebac512` 的六项反馈修复、`981d82d` 的 GPX 显示修复及调用链。HEAD `8fc7325768eac97d220dc0e26a0cf4455c590ee4`，工作区无未提交代码。代码内容指纹（已跟踪非 REVIEW/需求文档文件按“路径 + 小写 SHA-256”排序拼接再取 SHA-256）：`a84f3a6cbfc30c44376daa48f7b418c9eed1f398b26f4ce148966a3b6f7ddda1`。

只读检查已有验证证据：`app/build/test-results/testDebugUnitTest` 中 7 份 XML 共 68 tests、0 failures、0 errors；debug APK 时间为 2026-09-29 13:39:10（北京时间）。本轮未重新构建或真机测试。

六项旧反馈复核：
- P1 蓝牙权限与前台服务：启动门控的代码路径已修；Android 14/15 拒权和后台 GT5 心率待实机验收。
- P1 通知结束：通知已调用共享控制器的 `stopSession()`；通知实际点击及资源释放待实机验收。
- P1 拒绝定位仍可分享：无权限时先生成无定位预览；系统权限弹窗后的显示待实机验收。
- P2 定位源串行：三个可用源现并发请求，先到的非空结果会取消其他任务；GPS 卡住时网络定位先返回尚无 Android 测试证据。
- P2 GPX 路线持久化：几何与方向已保存，但下方两个持久化问题未解决，暂不关闭。
- P2 新会话继承旧心率：新会话和切换数据源均调用 `engine.clear()`，新增 JVM 测试通过；已解决。

用户实测 GPX “导入成功但主界面显示未导入”：`HikeApp.kt:253-263` 改为依据 `routeTotalKm` 判断导入状态，`GpxParser.kt:103,146` 增加 metadata 名称回退；静态复核通过。原始两步路 GPX 和用户手机上的重新导入仍待验证。

### P2：metadata 路线名在重启后丢失

位置：`app/src/main/java/dev/hike/dataassistant/gpx/GpxParser.kt:127-130,146`；`app/src/main/java/dev/hike/dataassistant/ui/HikeViewModel.kt:376-382,288-295`；`app/src/main/java/dev/hike/dataassistant/session/SessionRepository.kt:120-125,137-159`。

复现：导入只有 `<metadata><name>`、没有 `<trk><name>` 的 GPX。解析结果的 `trackName` 有值，但段对象 `name` 为 null；当前 UI 用回退名称正确显示，`saveRoute(segment)` 却保存空名称。杀掉进程重开后 `restoreRoute()` 从段对象取名，界面变成“未命名路线”。几何与方向仍在，但路线名丢失。

验收：该 GPX 导入后与进程重启后显示同一名称；完全无名的 GPX 始终显示“未命名路线”。增加保存/恢复测试。

### P2：路线写入失败被吞掉，旧文件可被部分覆盖

位置：`app/src/main/java/dev/hike/dataassistant/session/SessionRepository.kt:120-133`；`app/src/main/java/dev/hike/dataassistant/ui/HikeViewModel.kt:376-389`。

`saveRoute()` 直接覆盖 `last_route.txt`，遇到 I/O 异常却吞掉；调用方仍显示“路线已导入”。存储空间不足或写入中断时，重启可能丢失路线或读到部分点，旧路线也无法恢复。

验收：写入完成后原子替换旧路线；失败时保留旧文件并告知未保存。用故障注入验证中途失败和重启恢复。

### P2：20 MB 输入流上限可被批量读取越过

位置：`app/src/main/java/dev/hike/dataassistant/gpx/BoundedInputStream.kt:21-25,28-31`。

`read(byte[], off, len)` 只检查至少剩 1 字节，随后允许底层读取整个 `len`。可复现：`maxBytes=10`，对 11 字节输入调用一次 `read(buffer, 0, 11)`，会返回 11 而不抛错。“流式强制 20 MB”当前不严格。

验收：批量读取不能把累计已读字节推进到上限以上；第 `maxBytes+1` 字节触发明确错误，并增加批量与边界测试。
## 已解决的历史问题

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

> **用户实测反馈（2026-09-29，两轮修复后追加）**：真机导入 GPX 后主界面无任何路线显示。已在提交 `981d82d` 修复：根因是路线卡以 `routeName == null` 判"未导入"，而两步路部分导出无 `<trk><name>`（名字在 `<metadata>` 或缺失），导入实际成功但界面不显示；同时加固导入路径（UNKNOWN_LENGTH(-1) 大小不再判失败、20MB 上限改为流式强制、失败提示带具体原因、`processor.build` 异常不再使协程崩溃），解析器接受 `<metadata><name>` 回退，新增 3 个两步路真实形态解析测试。构建全绿（**68 tests, 0 failed**）。待复核与真机复测。

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

无权限时分享流程只触发权限申请；拒绝后不会生成预览。开发文档 `7 明确要求定位失败仍可分享心率与会话数据。验收：拒绝或永久拒绝定位后，分享预览仍打开，位置与路线进度标“缺失”。

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
5. 当前审核基线为 `8668897`。后续每次审核都记录新基线，避免重复报告。同一问题只有状态变化或出现新证据时才通知用户。

这套流程按定时检查运行，不是文件一保存就立即触发。另一家公司的 agent 无法直接接收 Codex 消息，因此它需要按第 1 条定期读取本文件。

## 变更检测细则

- 上次已审状态：提交 `8668897`，工作区无未提交代码；已跟踪的非 REVIEW/需求文档/生成物文件按“路径 + 小写 SHA-256”排序拼接，再取 SHA-256，内容指纹为 `3f1ce5ab3de55f56a84bcd48d42bc827006f69258d60ccd5441cfc1f3cbec8b6`。
- 每轮读取 Git 当前提交号、已跟踪文件的实际差异，以及未跟踪源码文件的路径和内容哈希；排除 `REVIEW.md`、`.gradle/` 和 `build/` 等生成内容。
- 审核完成时记录本轮提交号和代码内容指纹。下一轮指纹相同则跳过，即使 `git status` 仍显示未提交文件也不重复报告。
- 文件刚写入或仍在变化时等待下一轮；审核只覆盖已经保存到该目录的内容，无法看到开发 agent 尚未保存的编辑。


