# 徒步数据助手（HikeDataAssistant）v0.1

两步路（2bulu）负责地图导航；本 App 负责收集徒步状态——华为 WATCH GT5 实时心率、活动计时、按需手机定位与导入 GPX 的路线背景——整理成带来源与时间戳的数据包，交由用户分享给 ChatGPT 做对话分析。

完整产品与验收要求见 [Hike_Copilot_v0.1_Development.md](Hike_Copilot_v0.1_Development.md)。代码审核交接见 [REVIEW.md](REVIEW.md)。

## 构建环境

- JDK 17、Android SDK Platform 35、Gradle 8.11.1（wrapper 自动下载官方发行包）
- `local.properties` 配置本机 `sdk.dir`（不入库）

```bash
# Windows
gradlew.bat assembleDebug
# Linux / macOS / Git Bash
./gradlew assembleDebug

# 单元测试（纯 JVM，不需要设备）
gradlew.bat testDebugUnitTest
```

## 模块结构

```
dev.hike.dataassistant/
├── data/        Reading/ReadingStatus/HikeSnapshot 等统一数据模型
├── session/     HikeSession 会话计时（单调时钟、暂停/继续段）
├── heartrate/   0x2A37 解析、HeartRateEngine（新鲜度/均值/趋势）、BLE 接入、debug 模拟源
├── gpx/         GPX 安全解析、里程/海拔平滑/爬升预计算
├── route/       定位投影到 GPX、折返/交叉歧义处理
├── location/    LocationManager 按需定位 + 被动位置
├── snapshot/    SnapshotBuilder 汇总 + ShareTextBuilder 生成分享文案
├── service/     前台服务（心率会话后台保活）
└── ui/          Compose 单主界面 + ViewModel
```

## 数据可信度规则（实现于代码，测试钉住）

| 规则 | 实现 |
| --- | --- |
| 心率接收 >15 秒标 STALE、>60 秒视为无实时数据 | `HeartRateEngine.currentReading` |
| 5 分钟均值需 ≥3 个样本且覆盖 ≥2 分钟 | `HeartRateEngine.avg5MinBpm` |
| 首个样本后未满 10 分钟趋势写"数据积累中"；覆盖不足不算趋势 | `HeartRateEngine.trend10Min` |
| 暂停期间样本不计入趋势 | 引擎按会话活动区间过滤 |
| 模拟心率仅 debug 模式可用，醒目标注，不混入真实统计 | `isSimulated` 全链路传递 |
| 位置目标年龄 ≤30 秒、精度 ≤30 米，否则"位置不确定" | `RouteContextResolver` |
| 折返/交叉导致匹配歧义时显示"路线位置待确认"，不猜 | 投影候选聚类 |
| GPX 无海拔 → 爬升未知（null），不是 0 | `GpxProcessor` |
| 缺失字段在分享文案中显式说明，不伪装 | `ShareTextBuilder` |

## GT5 真机验证（待用户配合，代码已就绪）

App 内"连接手表"扫描暴露标准心率服务（0x180D）的 BLE 设备。实机验证流程（开发文档 §5）：

1. GT5「设置 → 心率广播」开启；华为运动健康正常连接手表。
2. 本 App 扫描选择 GT5 → 连接订阅 0x2A37 通知。
3. 分别验证：日常模式、手表开启运动模式、锁屏、切到两步路、走出范围重连。
4. 记录接收间隔、最大中断、重连次数、双端耗电（验收指标见开发文档 §5.3）。

若手表未开放心率广播，记录现象并评估华为 Health Kit 实时接口（开发文档 §5.2），不宣称接入成功。

## 当前状态

- [x] Gradle wrapper 公网地址（P1 修复）
- [x] 会话计时、心率引擎与统计规则（单元测试）
- [x] GPX 解析与路线预处理（单元测试）
- [x] 路线匹配与歧义处理（单元测试）
- [x] 快照与分享文案（单元测试）
- [x] BLE 心率接入代码 + 前台服务 + 按需定位 + Compose 主界面
- [ ] GT5 实机验证（阶段 0，需手表与手机）
- [ ] 户外联合测试与耗电对比（阶段 4，需实地）

未真机验证的项目一律不标记"通过"。调试模拟心率入口仅在 debug 构建可见。
