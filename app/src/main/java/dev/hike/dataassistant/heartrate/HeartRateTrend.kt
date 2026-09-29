package dev.hike.dataassistant.heartrate

/**
 * 10 分钟心率趋势：只有数据积累足够时才给出数值变化，否则显式说明原因。
 */
sealed interface HeartRateTrend {
    /** 首个真实样本后未满 10 分钟。 */
    data object Accumulating : HeartRateTrend

    /** 已超过 10 分钟但窗口内样本覆盖不足，不算趋势。 */
    data object InsufficientCoverage : HeartRateTrend

    /** 最近 5 分钟均值相对之前 5 分钟的变化（bpm，正为上升）。 */
    data class Change(val deltaBpm: Int) : HeartRateTrend
}
