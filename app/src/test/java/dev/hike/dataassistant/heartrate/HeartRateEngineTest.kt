package dev.hike.dataassistant.heartrate

import dev.hike.dataassistant.data.ReadingStatus
import dev.hike.dataassistant.session.FakeMonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateEngineTest {

    private val clock = FakeMonotonicClock()
    private val engine = HeartRateEngine(clock)

    /** 每 10 秒一个样本至 minutes 分钟（不含端点），bpm 从 startBpm 起。 */
    private fun feed(minutes: Int, startBpm: Int = 120, step: Int = 1, atMinuteOffset: Int = 0) {
        val total = minutes * 60
        var t = atMinuteOffset * 60
        var bpm = startBpm
        while (t < total) {
            clock.now = t * 1000L
            engine.onSample(bpm = bpm, wallMs = clock.now)
            t += 10
            bpm += step
        }
    }

    @Test
    fun `sample within 15s is FRESH`() {
        clock.now = 0
        engine.onSample(142, wallMs = 0)
        val reading = engine.currentReading(nowWallMs = 10_000)
        assertEquals(ReadingStatus.FRESH, reading.status)
        assertEquals(142, reading.value)
        assertFalse(reading.isSimulated)
    }

    @Test
    fun `sample older than 15s is STALE but keeps value`() {
        clock.now = 0
        engine.onSample(138, wallMs = 0)
        val reading = engine.currentReading(nowWallMs = 20_000)
        assertEquals(ReadingStatus.STALE, reading.status)
        assertEquals(138, reading.value)
    }

    @Test
    fun `sample older than 60s means no realtime data`() {
        clock.now = 0
        engine.onSample(138, wallMs = 0)
        val reading = engine.currentReading(nowWallMs = 61_000)
        assertEquals(ReadingStatus.MISSING, reading.status)
        assertNull(reading.value)
    }

    @Test
    fun `no samples at all is MISSING`() {
        val reading = engine.currentReading(nowWallMs = 0)
        assertEquals(ReadingStatus.MISSING, reading.status)
        assertNull(reading.value)
    }

    @Test
    fun `avg5 needs at least 3 samples spread over 2 minutes`() {
        clock.now = 0
        engine.onSample(100, wallMs = 0)
        assertNull(engine.avg5MinBpm(nowMonoMs = 0))
        // 3 个样本但都挤在 20 秒内
        engine.onSample(100, wallMs = 5_000)
        engine.onSample(110, wallMs = 20_000)
        assertNull(engine.avg5MinBpm(nowMonoMs = 20_000))
    }

    @Test
    fun `avg5 averages samples in the last 5 minutes only`() {
        // 0-4 分钟样本 bpm=100；第 6 分钟起样本 bpm=140
        feed(4, startBpm = 100, step = 0)
        clock.now = 6 * 60 * 1000L
        var t = 6 * 60
        while (t <= 8 * 60) {
            clock.now = t * 1000L
            engine.onSample(140, wallMs = clock.now)
            t += 10
        }
        // now = 8 分钟：5 分钟窗口 [3,8] 内 3-4 分钟 6 个样本 100，6-8 分钟 13 个样本 140
        val avg = engine.avg5MinBpm(nowMonoMs = 8 * 60 * 1000L)
        val expected = (6 * 100 + 13 * 140) / 19
        assertEquals(expected, avg)
    }

    @Test
    fun `trend is Accumulating before 10 minutes of data`() {
        feed(6)
        assertTrue(engine.trend10Min(nowMonoMs = 6 * 60 * 1000L) is HeartRateTrend.Accumulating)
    }

    @Test
    fun `trend computes change across the 10 minute boundary`() {
        // 0-5 分钟 bpm=135 恒定，5-10 分钟 bpm=140 恒定（t=300 属于新窗口）
        feed(5, startBpm = 135, step = 0)
        var t = 5 * 60
        while (t <= 10 * 60) {
            clock.now = t * 1000L
            engine.onSample(140, wallMs = clock.now)
            t += 10
        }
        val trend = engine.trend10Min(nowMonoMs = 10 * 60 * 1000L)
        assertTrue(trend is HeartRateTrend.Change)
        assertEquals(5, (trend as HeartRateTrend.Change).deltaBpm)
    }

    @Test
    fun `trend stays InsufficientCoverage when early window is empty`() {
        // 只有前 3 分钟的数据，到 12 分钟时早期窗口为空
        feed(3)
        val trend = engine.trend10Min(nowMonoMs = 12 * 60 * 1000L)
        assertTrue(trend is HeartRateTrend.InsufficientCoverage)
    }

    @Test
    fun `samples received during pause are excluded from trend and average`() {
        val paused = 5 * 60 * 1000L..8 * 60 * 1000L
        val engineWithPause = HeartRateEngine(clock, isRecordingAt = { mono -> mono !in paused })
        clock.now = 0
        // 0-5 分钟活动期 bpm=120
        var t = 0
        while (t <= 5 * 60) {
            clock.now = t * 1000L
            engineWithPause.onSample(120, wallMs = clock.now)
            t += 10
        }
        // 5-8 分钟暂停期手表仍在广播 bpm=180（不应计入）
        t = 5 * 60 + 10
        while (t <= 8 * 60) {
            clock.now = t * 1000L
            engineWithPause.onSample(180, wallMs = clock.now)
            t += 10
        }
        // 8-11 分钟恢复 bpm=120
        t = 8 * 60 + 10
        while (t <= 11 * 60) {
            clock.now = t * 1000L
            engineWithPause.onSample(120, wallMs = clock.now)
            t += 10
        }
        val trend = engineWithPause.trend10Min(nowMonoMs = 11 * 60 * 1000L)
        // 暂停样本被排除后两窗口均值一致，变化为 0；关键是不会把 180 算进去
        if (trend is HeartRateTrend.Change) {
            assertEquals(0, trend.deltaBpm)
        } else {
            throw AssertionError("expected Change but was $trend")
        }
    }

    @Test
    fun `simulated samples never mix into real statistics`() {
        clock.now = 0
        engine.onSample(150, wallMs = 0, isSimulated = true)
        engine.onSample(150, wallMs = 5_000, isSimulated = true)
        // 模拟样本不构成实时真实读数
        val reading = engine.currentReading(nowWallMs = 6_000)
        assertEquals(ReadingStatus.MISSING, reading.status)
        assertNull(reading.value)
        assertNull(engine.avg5MinBpm(nowMonoMs = 6_000))
    }

    @Test
    fun `simulated current reading only when explicitly enabled and clearly flagged`() {
        engine.simulatedEnabled = true
        clock.now = 0
        engine.onSample(150, wallMs = 0, isSimulated = true)
        val reading = engine.currentReading(nowWallMs = 3_000)
        assertEquals(150, reading.value)
        assertTrue(reading.isSimulated)
    }

    @Test
    fun `clear resets everything for a new session`() {
        feed(12) // 足够产生均值与趋势的数据
        assertEquals(ReadingStatus.FRESH, engine.currentReading(nowWallMs = clock.now).status)
        engine.clear()
        // 新会话在收到新样本前：当前值缺失、无均值、趋势重新积累
        val reading = engine.currentReading(nowWallMs = clock.now)
        assertEquals(ReadingStatus.MISSING, reading.status)
        assertNull(reading.value)
        assertNull(engine.avg5MinBpm(nowMonoMs = clock.now))
        assertTrue(engine.trend10Min(nowMonoMs = clock.now + 20 * 60_000) is HeartRateTrend.Accumulating)
    }
}
