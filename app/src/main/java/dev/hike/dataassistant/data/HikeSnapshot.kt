package dev.hike.dataassistant.data

import dev.hike.dataassistant.heartrate.HeartRateEngine
import dev.hike.dataassistant.heartrate.HeartRateTrend
import dev.hike.dataassistant.route.RouteMatchStatus

/**
 * 生成分享包时的一致性快照（开发文档 §8）。
 * elapsed/active 在会话未开始时为 null；路线字段在定位不可信或匹配歧义时为 null。
 */
data class HikeSnapshot(
    val generatedAtEpochMs: Long,
    val elapsedSeconds: Long?,
    val activeSeconds: Long?,
    val heartRate: Reading<Int>,
    val heartRateAvg5Min: Int?,
    val heartRateTrend: HeartRateTrend?,
    /** 本次记录的会话级心率统计（区间/均值/覆盖），样本不足为 null。 */
    val sessionHr: HeartRateEngine.SessionHrStats? = null,
    val location: Reading<PhonePosition>,
    val routeMatchStatus: RouteMatchStatus?,
    val routePositionMeters: Double?,
    val routeTotalMeters: Double?,
    /** 导入 GPX 的计划总爬升；无海拔为 null。 */
    val routeTotalClimbMeters: Double? = null,
    val plannedRemainingMeters: Double?,
    val plannedRemainingClimbMeters: Double?,
    val nextClimb: PlannedClimb?,
    val notes: List<String>
)
