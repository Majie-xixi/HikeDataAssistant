package dev.hike.dataassistant.data

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
    val location: Reading<PhonePosition>,
    val routeMatchStatus: RouteMatchStatus?,
    val routePositionMeters: Double?,
    val routeTotalMeters: Double?,
    val plannedRemainingMeters: Double?,
    val plannedRemainingClimbMeters: Double?,
    val nextClimb: PlannedClimb?,
    val notes: List<String>
)
