package dev.hike.dataassistant.heartrate

import dev.hike.dataassistant.data.DataSources
import dev.hike.dataassistant.data.Reading
import dev.hike.dataassistant.data.ReadingStatus
import dev.hike.dataassistant.session.MonotonicClock

/** 一次接收的心率样本；记录的是手机收到数据的时间，不是手表测量时间。 */
data class HeartRateSample(
    val bpm: Int,
    val receivedAtEpochMs: Long,
    val receivedAtMonotonicMs: Long,
    val contactDetected: Boolean? = null,
    val isSimulated: Boolean = false
)

/**
 * 心率统计引擎：新鲜度判定、5 分钟均值、10 分钟趋势。
 *
 * 规则（开发文档 §4/§8）：
 * - 接收时间距现在 >15 秒 STALE，>60 秒视为无实时数据；
 * - 5 分钟均值需窗口内 ≥3 个样本且覆盖跨度 ≥2 分钟；
 * - 首个真实样本后未满 10 分钟趋势为"数据积累中"；覆盖不足不算趋势；
 * - 暂停期间收到的样本不计入均值与趋势；
 * - 模拟样本永不混入真实统计，仅在显式开启时作为醒目标注的当前值展示。
 */
class HeartRateEngine(
    private val clock: MonotonicClock,
    private val isRecordingAt: (monotonicMs: Long) -> Boolean = { true },
    private val capacity: Int = 4320 // 约 12 小时、每 10 秒一个样本
) {
    companion object {
        const val STALE_AFTER_MS: Long = 15_000
        const val MISSING_AFTER_MS: Long = 60_000
        const val AVG_WINDOW_MS: Long = 5 * 60_000
        const val TREND_SPAN_MS: Long = 10 * 60_000
        private const val MIN_WINDOW_SAMPLES = 3
        private const val MIN_WINDOW_SPAN_MS: Long = 2 * 60_000
    }

    /** 仅供 debug 构建由 UI 打开；打开后当前值可显示模拟样本，统计仍只用真实样本。 */
    @Volatile
    var simulatedEnabled: Boolean = false

    private val realSamples = ArrayDeque<HeartRateSample>(capacity)
    private val simulatedSamples = ArrayDeque<HeartRateSample>(128)

    @Synchronized
    fun onSample(bpm: Int, wallMs: Long, contactDetected: Boolean? = null, isSimulated: Boolean = false) {
        val sample = HeartRateSample(
            bpm = bpm,
            receivedAtEpochMs = wallMs,
            receivedAtMonotonicMs = clock.nowMs(),
            contactDetected = contactDetected,
            isSimulated = isSimulated
        )
        val target = if (isSimulated) simulatedSamples else realSamples
        target.addLast(sample)
        while (target.size > (if (isSimulated) 128 else capacity)) target.removeFirst()
    }

    /** 当前心率读数：按真实样本新鲜度判定；无真实样本时按规则返回缺失。 */
    @Synchronized
    fun currentReading(nowWallMs: Long): Reading<Int> {
        val last = realSamples.lastOrNull()
        if (last != null) {
            val age = nowWallMs - last.receivedAtEpochMs
            return when {
                age <= STALE_AFTER_MS -> Reading(
                    last.bpm, DataSources.GT5_BLE, last.receivedAtEpochMs, ReadingStatus.FRESH,
                    note = "${age / 1000} 秒前接收"
                )

                age <= MISSING_AFTER_MS -> Reading(
                    last.bpm, DataSources.GT5_BLE, last.receivedAtEpochMs, ReadingStatus.STALE,
                    note = "距上次接收 ${age / 1000} 秒，数据可能过期"
                )

                else -> Reading(
                    null, DataSources.GT5_BLE, last.receivedAtEpochMs, ReadingStatus.MISSING,
                    note = "超过 60 秒未收到更新"
                )
            }
        }
        if (simulatedEnabled) {
            val sim = simulatedSamples.lastOrNull()
            if (sim != null) {
                val age = nowWallMs - sim.receivedAtEpochMs
                if (age <= STALE_AFTER_MS) {
                    return Reading(
                        sim.bpm, DataSources.GT5_BLE, sim.receivedAtEpochMs, ReadingStatus.FRESH,
                        note = "${age / 1000} 秒前接收（模拟）", isSimulated = true
                    )
                }
            }
        }
        return Reading(
            null, DataSources.GT5_BLE, null, ReadingStatus.MISSING,
            note = if (simulatedSamples.isNotEmpty() && !simulatedEnabled) "模拟源未启用且无真实数据" else "尚未收到心率数据"
        )
    }

    /** 最近 5 分钟均值（仅真实且处于记录期的样本）；覆盖不足返回 null。 */
    @Synchronized
    fun avg5MinBpm(nowMonoMs: Long): Int? =
        windowAverage(nowMonoMs, nowMonoMs - AVG_WINDOW_MS)

    /** 10 分钟趋势；规则见类注释。 */
    @Synchronized
    fun trend10Min(nowMonoMs: Long): HeartRateTrend {
        val first = realSamples.firstOrNull() ?: return HeartRateTrend.Accumulating
        if (nowMonoMs - first.receivedAtMonotonicMs < TREND_SPAN_MS) {
            return HeartRateTrend.Accumulating
        }
        val recent = windowAverage(nowMonoMs, nowMonoMs - AVG_WINDOW_MS)
        val previous = windowAverage(nowMonoMs - AVG_WINDOW_MS, nowMonoMs - TREND_SPAN_MS)
        if (recent == null || previous == null) return HeartRateTrend.InsufficientCoverage
        return HeartRateTrend.Change(recent - previous)
    }

    @Synchronized
    fun realSampleCount(): Int = realSamples.size

    @Synchronized
    fun clear() {
        realSamples.clear()
        simulatedSamples.clear()
    }

    private fun windowAverage(nowMonoMs: Long, fromMonoMs: Long): Int? {
        if (fromMonoMs >= nowMonoMs) return null
        var sum = 0L
        var count = 0
        var oldest = Long.MAX_VALUE
        var newest = Long.MIN_VALUE
        for (s in realSamples) {
            if (s.receivedAtMonotonicMs < fromMonoMs || s.receivedAtMonotonicMs > nowMonoMs) continue
            if (!isRecordingAt(s.receivedAtMonotonicMs)) continue
            sum += s.bpm
            count++
            oldest = minOf(oldest, s.receivedAtMonotonicMs)
            newest = maxOf(newest, s.receivedAtMonotonicMs)
        }
        if (count < MIN_WINDOW_SAMPLES) return null
        if (newest - oldest < MIN_WINDOW_SPAN_MS) return null
        return (sum / count).toInt()
    }
}
