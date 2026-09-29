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
 *
 * 原则：缺失与不确定都显式写出；数据不足时不暗示能做负荷或前方路线判断；
 * 已导入 GPX 时，即使没有定位也提供"静态路线资料"（全程距离与计划爬升）；
 * 默认问题按快照完整度生成，用户始终可编辑。不包含坐标、设备地址等隐私信息。
 */
object ShareTextBuilder {

    /** 兼容旧调用；新逻辑请使用 defaultQuestion(snapshot)。 */
    const val DEFAULT_QUESTION =
        "请根据现有数据分析负荷和前方路线，指出还缺什么信息；不要只凭一个心率数值判断我一定能否继续。"

    private const val QUESTION_LOAD_ONLY =
        "请根据现有数据分析我的负荷状况，指出还缺什么信息；不要只凭一个心率数值判断我一定能否继续。"

    private const val QUESTION_WARMING_UP =
        "我刚开始记录不久，数据还在积累。请说明目前哪些判断还做不了、需要继续收集什么，不用急着下结论。"

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** 默认问题按快照完整度选择：能判负荷且路线已匹配才提"前方路线"。 */
    fun defaultQuestion(snapshot: HikeSnapshot): String {
        val loadJudgeable = snapshot.notes.none { it.contains("暂不能判断负荷") }
        val routeMatched = snapshot.routeMatchStatus == RouteMatchStatus.MATCHED
        return when {
            !loadJudgeable -> QUESTION_WARMING_UP
            routeMatched -> DEFAULT_QUESTION
            else -> QUESTION_LOAD_ONLY
        }
    }

    fun build(snapshot: HikeSnapshot, routeName: String?, userQuestion: String): String {
        val lines = mutableListOf<String>()

        // 1. 时长：明确是"本 App 本次记录"，避免被当成整段徒步的时长
        val active = snapshot.activeSeconds
        if (active != null) {
            val elapsed = snapshot.elapsedSeconds
            if (elapsed != null && elapsed - active >= 60) {
                lines.add("我在徒步，本 App 本次已记录 ${formatDuration(elapsed)}，其中活动 ${formatDuration(active)}。")
            } else {
                lines.add("我在徒步，本 App 本次已记录 ${formatDuration(active)}。")
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
            append(listOfNotNull(avgPart, trendPart).joinToString("，"))
        }
        lines.add(if (statsText.isEmpty()) "$hrText。" else "$hrText；$statsText。")

        // 2.1 会话级统计（区间/均值/覆盖），有足够样本才写
        snapshot.sessionHr?.let { s ->
            lines.add(
                "本次记录心率区间 ${s.minBpm}～${s.maxBpm}、平均 ${s.avgBpm} bpm（有效样本覆盖 ${s.coverageMinutes} 分钟）。"
            )
        }

        // 3. 路线
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
                lines.add("以上路线进度是按规划路线的估算。")
            }

            RouteMatchStatus.AMBIGUOUS,
            RouteMatchStatus.LOCATION_STALE,
            RouteMatchStatus.LOCATION_LOW_ACCURACY,
            RouteMatchStatus.OUT_OF_CORRIDOR,
            RouteMatchStatus.NO_LOCATION -> {
                // 没有可信位置时仍提供静态路线资料（不依赖当前位置）
                if (snapshot.routeTotalMeters != null) {
                    val name = routeName ?: "未命名路线"
                    val climbText = snapshot.routeTotalClimbMeters
                        ?.let { "计划爬升约 ${it.roundToInt()} m" } ?: "计划爬升未知（GPX 无海拔）"
                    lines.add("导入路线（静态规划资料）：$name，全程 ${km(snapshot.routeTotalMeters)} km、$climbText。")
                }
                val reason = when (snapshot.routeMatchStatus) {
                    RouteMatchStatus.AMBIGUOUS -> "定位在路线多处附近（折返或交叉），路线位置待确认"
                    RouteMatchStatus.LOCATION_STALE -> "定位超过 30 秒未更新"
                    RouteMatchStatus.LOCATION_LOW_ACCURACY -> "定位精度不足"
                    RouteMatchStatus.OUT_OF_CORRIDOR -> "当前位置距路线过远"
                    else -> "本次未获得可用定位"
                }
                lines.add("当前$reason，未计算沿途进度。")
            }

            RouteMatchStatus.NO_ROUTE, null -> Unit
        }

        // 4. 未提供项与备注
        lines.add("未提供症状、天气和补水情况。")
        snapshot.notes.forEach { lines.add("备注：$it。") }

        // 5. 问题（默认问题按数据完整度生成，用户可改）
        val question = userQuestion.ifBlank { defaultQuestion(snapshot) }
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
