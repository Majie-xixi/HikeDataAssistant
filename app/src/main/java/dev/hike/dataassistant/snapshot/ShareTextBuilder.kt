package dev.hike.dataassistant.snapshot

import dev.hike.dataassistant.data.HikeSnapshot
import dev.hike.dataassistant.data.ReadingStatus
import dev.hike.dataassistant.heartrate.HeartRateTrend
import dev.hike.dataassistant.route.RouteMatchStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 把快照渲染为分享给 ChatGPT 的文字（开发文档 §8 的格式）。
 * 所有缺失与不确定都显式写出；不包含坐标、设备地址等隐私信息。
 */
object ShareTextBuilder {

    const val DEFAULT_QUESTION =
        "请根据现有数据分析负荷和前方路线，指出还缺什么信息；不要只凭一个心率数值判断我一定能否继续。"

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun build(snapshot: HikeSnapshot, routeName: String?, userQuestion: String): String {
        val lines = mutableListOf<String>()

        // 1. 会话
        val active = snapshot.activeSeconds
        if (active != null) {
            val elapsed = snapshot.elapsedSeconds
            if (elapsed != null && elapsed - active >= 60) {
                lines.add("我在徒步，累计经过 ${formatDuration(elapsed)}，其中活动 ${formatDuration(active)}。")
            } else {
                lines.add("我在徒步，已活动 ${formatDuration(active)}。")
            }
        } else {
            lines.add("徒步尚未开始记录，以下为当前状态。")
        }

        // 2. 心率
        val hr = snapshot.heartRate
        val simPrefix = if (hr.isSimulated) "【模拟数据，仅调试】" else ""
        val hrText = when (hr.status) {
            ReadingStatus.FRESH -> {
                val age = hr.sampledAtEpochMs?.let { snapshot.generatedAtEpochMs - it } ?: 0L
                "${simPrefix}GT5 心率最新接收值 ${hr.value} bpm（${formatAge(age)}前）"
            }

            ReadingStatus.STALE -> {
                val age = hr.sampledAtEpochMs?.let { snapshot.generatedAtEpochMs - it } ?: 0L
                "${simPrefix}GT5 心率 ${formatAge(age)}前收到 ${hr.value} bpm（数据可能过期）"
            }

            else -> "${simPrefix}无实时心率（${hr.note ?: "原因未知"}）"
        }
        val statsText = buildString {
            val avg = snapshot.heartRateAvg5Min
            val trend = snapshot.heartRateTrend
            val avgPart = if (avg != null) "最近 5 分钟均值 $avg bpm" else null
            val trendPart = when (trend) {
                is HeartRateTrend.Change -> "最近 10 分钟变化约 ${formatDelta(trend.deltaBpm)} bpm"
                HeartRateTrend.Accumulating, HeartRateTrend.InsufficientCoverage ->
                    if (avg != null || trend != null) "最近 10 分钟趋势数据积累中" else null

                null -> null
            }
            val parts = listOfNotNull(avgPart, trendPart)
            if (parts.isNotEmpty()) append(parts.joinToString("，"))
        }
        lines.add(if (statsText.isEmpty()) "$hrText。" else "$hrText；$statsText。")

        // 3. 路线背景
        when (snapshot.routeMatchStatus) {
            RouteMatchStatus.MATCHED -> {
                val name = routeName ?: "未命名路线"
                val acc = snapshot.location.value?.accuracyMeters?.toInt()
                val accText = if (acc != null) "$acc 米精度的手机定位" else "手机定位"
                val pos = snapshot.routePositionMeters
                val total = snapshot.routeTotalMeters
                val rem = snapshot.plannedRemainingMeters
                val climb = snapshot.plannedRemainingClimbMeters
                if (pos != null && total != null && rem != null) {
                    val climbText = when {
                        climb != null -> "爬升约 ${climb.roundToInt()} m"
                        else -> "爬升未知（GPX 无海拔）"
                    }
                    lines.add(
                        "按导入的 GPX（$name）和 $accText 估算，位于路线约 " +
                            "${km(pos)}/${km(total)} km 处，沿线计划剩余约 ${km(rem)} km、$climbText。"
                    )
                }
                snapshot.nextClimb?.let {
                    lines.add("前方约 ${it.distanceToStartMeters.roundToInt()} m 后开始约 ${it.climbMeters.roundToInt()} m 爬升。")
                }
                lines.add("以上路线数据是规划估算。")
            }

            RouteMatchStatus.AMBIGUOUS ->
                lines.add("当前位置在导入路线（${routeName ?: "未命名路线"}）多处附近（折返或交叉），路线位置待确认，未估算剩余里程。")

            RouteMatchStatus.LOCATION_STALE ->
                lines.add("定位已超 30 秒未更新，路线进度未计算。")

            RouteMatchStatus.LOCATION_LOW_ACCURACY ->
                lines.add("位置不确定（定位精度不足），路线进度未计算。")

            RouteMatchStatus.OUT_OF_CORRIDOR ->
                lines.add("当前位置距导入路线过远，路线进度未计算。")

            RouteMatchStatus.NO_LOCATION ->
                if (snapshot.routeTotalMeters != null) lines.add("本次未获得可用定位，路线进度未计算。")

            RouteMatchStatus.NO_ROUTE -> Unit
            null -> Unit
        }

        // 4. 未提供项与备注
        lines.add("未提供症状、天气和补水情况。")
        snapshot.notes.forEach { lines.add("备注：$it。") }

        // 5. 问题
        val question = userQuestion.ifBlank { DEFAULT_QUESTION }
        lines.add("")
        lines.add(question)
        lines.add("（数据整理于 ${formatTime(snapshot.generatedAtEpochMs)}）")

        return lines.joinToString("\n")
    }

    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> if (m > 0) "$h 小时 $m 分" else "$h 小时"
            m > 0 -> "$m 分"
            else -> "$s 秒"
        }
    }

    private fun formatAge(ageMs: Long): String =
        if (ageMs < 60_000) "${(ageMs / 1000).coerceAtLeast(0)} 秒" else "${ageMs / 60_000} 分"

    private fun formatDelta(delta: Int): String = if (delta >= 0) "+$delta" else "$delta"

    private fun km(meters: Double): String = String.format(Locale.US, "%.1f", meters / 1000.0)

    private fun Double.roundToInt(): Int = Math.round(this).toInt()

    private fun formatTime(epochMs: Long): String =
        timeFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
