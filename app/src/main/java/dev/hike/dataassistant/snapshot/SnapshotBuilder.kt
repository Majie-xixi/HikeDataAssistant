package dev.hike.dataassistant.snapshot

import dev.hike.dataassistant.data.DataSources
import dev.hike.dataassistant.data.HikeSnapshot
import dev.hike.dataassistant.data.PhonePosition
import dev.hike.dataassistant.data.Reading
import dev.hike.dataassistant.data.ReadingStatus
import dev.hike.dataassistant.gpx.GpxProcessor
import dev.hike.dataassistant.heartrate.HeartRateEngine
import dev.hike.dataassistant.route.RouteContextResolver
import dev.hike.dataassistant.route.RouteMatchStatus
import dev.hike.dataassistant.session.HikeSession

/**
 * 汇总各来源生成一致性快照（开发文档 §8）：
 * 会话时长、心率读数与统计、定位、路线投影与计划剩余。
 * 缺失与不确定在状态字段与 notes 中显式表达，不伪装成 0。
 */
class SnapshotBuilder(
    private val processor: GpxProcessor,
    private val resolver: RouteContextResolver
) {

    fun build(
        session: HikeSession?,
        engine: HeartRateEngine,
        routeModel: GpxProcessor.RouteModel?,
        location: Reading<PhonePosition>?,
        nowWallMs: Long,
        nowMonoMs: Long
    ): HikeSnapshot {
        val notes = mutableListOf<String>()

        val elapsedSeconds = session?.elapsedDurationMs()?.let { it / 1000 }
        val activeSeconds = session?.activeDurationMs()?.let { it / 1000 }
        if (elapsedSeconds != null && activeSeconds != null && elapsedSeconds - activeSeconds >= 60) {
            notes.add("会话含暂停时段，经过时间与活动时间分开列出")
        }

        val heartRate = engine.currentReading(nowWallMs)
        if (heartRate.isSimulated) notes.add("当前心率来自模拟源（仅 debug），不可用于真实评估")
        if (heartRate.status == ReadingStatus.MISSING && heartRate.value == null) {
            notes.add("无实时心率：${heartRate.note ?: "原因未知"}")
        }

        val avg5 = engine.avg5MinBpm(nowMonoMs)
        val trend = if (elapsedSeconds == null) null else engine.trend10Min(nowMonoMs)

        val locationAgeMs = location?.sampledAtEpochMs?.let { nowWallMs - it }
        val match = resolver.resolve(routeModel, location?.value, locationAgeMs)
        val locationReading = location ?: Reading<PhonePosition>(
            null, DataSources.PHONE_LOCATION, null, ReadingStatus.MISSING, note = "尚未请求定位"
        )
        when (match.status) {
            RouteMatchStatus.AMBIGUOUS ->
                notes.add("定位在路线多处附近（折返或交叉），路线位置待确认")
            RouteMatchStatus.LOCATION_STALE ->
                notes.add("路线进度未计算：定位超过 ${RouteContextResolver.MAX_LOCATION_AGE_MS / 1000} 秒")
            RouteMatchStatus.LOCATION_LOW_ACCURACY ->
                notes.add("路线进度未计算：定位精度差于 ${RouteContextResolver.MAX_ACCURACY_M.toInt()} 米")
            RouteMatchStatus.OUT_OF_CORRIDOR ->
                notes.add("路线进度未计算：距路线过远")
            else -> Unit
        }

        val atMeters = match.atMeters
        val remaining = if (match.status == RouteMatchStatus.MATCHED && atMeters != null) {
            processor.remainingMeters(routeModel!!, atMeters)
        } else null
        val remainingClimb = if (remaining != null) {
            processor.remainingClimbMeters(routeModel!!, atMeters!!)
        } else null
        val nextClimb = if (remaining != null) processor.nextClimb(routeModel!!, atMeters!!) else null

        return HikeSnapshot(
            generatedAtEpochMs = nowWallMs,
            elapsedSeconds = elapsedSeconds,
            activeSeconds = activeSeconds,
            heartRate = heartRate,
            heartRateAvg5Min = avg5,
            heartRateTrend = trend,
            location = locationReading,
            routeMatchStatus = match.status,
            routePositionMeters = atMeters,
            routeTotalMeters = routeModel?.totalMeters,
            plannedRemainingMeters = remaining,
            plannedRemainingClimbMeters = remainingClimb,
            nextClimb = nextClimb,
            notes = notes
        )
    }
}
