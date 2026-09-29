package dev.hike.dataassistant.snapshot

import dev.hike.dataassistant.data.HikeSnapshot
import dev.hike.dataassistant.data.PhonePosition
import dev.hike.dataassistant.data.PlannedClimb
import dev.hike.dataassistant.data.Reading
import dev.hike.dataassistant.data.ReadingStatus
import dev.hike.dataassistant.heartrate.HeartRateTrend
import dev.hike.dataassistant.route.RouteMatchStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTextBuilderTest {

    private val baseSnapshot = HikeSnapshot(
        generatedAtEpochMs = 1_700_000_000_000,
        elapsedSeconds = 8_100,
        activeSeconds = 8_100,
        heartRate = Reading(
            value = 142,
            source = "gt5_ble",
            sampledAtEpochMs = 1_700_000_000_000 - 2_000,
            status = ReadingStatus.FRESH
        ),
        heartRateAvg5Min = 138,
        heartRateTrend = HeartRateTrend.Change(+5),
        location = Reading(
            value = PhonePosition(30.0, 120.0, 8f),
            source = "phone_location",
            sampledAtEpochMs = 1_700_000_000_000 - 4_000,
            status = ReadingStatus.FRESH
        ),
        routeMatchStatus = RouteMatchStatus.MATCHED,
        routePositionMeters = 5_500.0,
        routeTotalMeters = 10_000.0,
        plannedRemainingMeters = 4_500.0,
        plannedRemainingClimbMeters = 260.0,
        nextClimb = PlannedClimb(distanceToStartMeters = 400.0, climbMeters = 100.0, lengthMeters = 800.0),
        notes = emptyList()
    )

    private fun build(snapshot: HikeSnapshot = baseSnapshot, question: String = ShareTextBuilder.DEFAULT_QUESTION) =
        ShareTextBuilder.build(snapshot, routeName = "黄山环线", userQuestion = question)

    @Test
    fun `full data renders like the documented example`() {
        val text = build()
        assertTrue(text.contains("已活动 2 小时 15 分"))
        assertTrue(text.contains("142 bpm（2 秒前）"))
        assertTrue(text.contains("最近 5 分钟均值 138 bpm"))
        assertTrue(text.contains("最近 10 分钟变化约 +5 bpm"))
        assertTrue(text.contains("黄山环线"))
        assertTrue(text.contains("8 米精度"))
        assertTrue(text.contains("5.5/10.0 km"))
        assertTrue(text.contains("剩余约 4.5 km"))
        assertTrue(text.contains("爬升约 260 m"))
        assertTrue(text.contains("以上路线数据是规划估算"))
        assertTrue(text.contains("未提供症状、天气和补水情况"))
        assertTrue(text.contains("请根据现有数据分析负荷和前方路线"))
    }

    @Test
    fun `stale heart rate keeps value but flagged as possibly outdated`() {
        val text = build(
            baseSnapshot.copy(
                heartRate = baseSnapshot.heartRate.copy(
                    value = 138,
                    sampledAtEpochMs = 1_700_000_000_000 - 45_000,
                    status = ReadingStatus.STALE
                )
            )
        )
        assertTrue(text.contains("45 秒前"))
        assertTrue(text.contains("数据可能过期"))
    }

    @Test
    fun `missing heart rate states no realtime data with reason`() {
        val text = build(
            baseSnapshot.copy(
                heartRate = Reading(null, "gt5_ble", null, ReadingStatus.MISSING, note = "超过 60 秒未收到更新"),
                heartRateAvg5Min = null,
                heartRateTrend = null,
            )
        )
        assertTrue(text.contains("无实时心率"))
        assertTrue(text.contains("超过 60 秒未收到更新"))
        assertFalse(text.contains("bpm（"))
    }

    @Test
    fun `accumulating trend is spelled out instead of a fake number`() {
        val text = build(
            baseSnapshot.copy(heartRateTrend = HeartRateTrend.Accumulating)
        )
        assertTrue(text.contains("数据积累中"))
    }

    @Test
    fun `simulated heart rate is prominently labeled`() {
        val text = build(
            baseSnapshot.copy(
                heartRate = baseSnapshot.heartRate.copy(isSimulated = true)
            )
        )
        assertTrue(text.contains("模拟"))
    }

    @Test
    fun `ambiguous route position asks for confirmation instead of guessing`() {
        val text = build(
            baseSnapshot.copy(
                routeMatchStatus = RouteMatchStatus.AMBIGUOUS,
                routePositionMeters = null,
                plannedRemainingMeters = null,
                plannedRemainingClimbMeters = null,
                nextClimb = null,
            )
        )
        assertTrue(text.contains("路线位置待确认"))
        assertFalse(text.contains("剩余约"))
    }

    @Test
    fun `missing location omits route progress but keeps session and heart rate`() {
        val text = build(
            baseSnapshot.copy(
                location = Reading(null, "phone_location", null, ReadingStatus.MISSING),
                routeMatchStatus = RouteMatchStatus.NO_LOCATION,
                routePositionMeters = null,
                routeTotalMeters = 10_000.0,
                plannedRemainingMeters = null,
                plannedRemainingClimbMeters = null,
                nextClimb = null,
            )
        )
        assertTrue(text.contains("142 bpm"))
        assertTrue(text.contains("2 小时 15 分"))
        assertFalse(text.contains("km 处"))
        assertTrue(text.contains("路线进度未计算"))
    }

    @Test
    fun `unknown climb is stated as unknown not zero`() {
        val text = build(
            baseSnapshot.copy(plannedRemainingClimbMeters = null)
        )
        assertTrue(text.contains("爬升未知"))
        assertFalse(text.contains("爬升约 0 m"))
    }

    @Test
    fun `user question replaces the default one`() {
        val text = build(question = "我现在心率是不是偏高，要不要休息？")
        assertTrue(text.contains("我现在心率是不是偏高，要不要休息？"))
    }

    @Test
    fun `session not started is stated explicitly`() {
        val text = build(baseSnapshot.copy(elapsedSeconds = null, activeSeconds = null))
        assertTrue(text.contains("尚未开始记录"))
    }

    @Test
    fun `paused time distinction appears when elapsed exceeds active`() {
        val text = build(baseSnapshot.copy(elapsedSeconds = 9_000, activeSeconds = 8_100))
        assertTrue(text.contains("累计经过"))
        assertTrue(text.contains("活动 2 小时 15 分"))
    }

    @Test
    fun `no privacy sensitive raw coordinates in text`() {
        val text = build()
        assertFalse(text.contains("30.0"))
        assertFalse(text.contains("120.0"))
        assertFalse(text.contains("纬"))
    }
}
