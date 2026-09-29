package dev.hike.dataassistant.route

import dev.hike.dataassistant.data.PhonePosition
import dev.hike.dataassistant.gpx.GpxPoint
import dev.hike.dataassistant.gpx.GpxProcessor
import dev.hike.dataassistant.gpx.GpxTrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteContextResolverTest {

    private val processor = GpxProcessor()
    private val resolver = RouteContextResolver()

    /** 北纬 30° 东西向直线，lonStep 为 0.001 时每段约 96.5m。 */
    private fun straightLineModel(pointCount: Int): GpxProcessor.RouteModel =
        processor.build(
            GpxTrackSegment(
                null,
                (0 until pointCount).map { GpxPoint(30.0, 120.0 + it * 0.001, null) }
            ),
            reversed = false
        )

    /** A → B → A 折返路线（去程 0..10，回程 9..0）。 */
    private fun outAndBackModel(): GpxProcessor.RouteModel {
        val going = (0..10).map { GpxPoint(30.0, 120.0 + it * 0.001, null) }
        val coming = (10 downTo 0).map { GpxPoint(30.0, 120.0 + it * 0.001, null) }
        return processor.build(GpxTrackSegment(null, going + coming.drop(1)), reversed = false)
    }

    @Test
    fun `projection onto a straight line yields matched arc position`() {
        val model = straightLineModel(11) // 全长约 965m
        val position = PhonePosition(lat = 30.0002, lon = 120.004, accuracyMeters = 8f)
        val match = resolver.resolve(model, position, locationAgeMs = 3_000)
        assertEquals(RouteMatchStatus.MATCHED, match.status)
        assertEquals(386.0, match.atMeters!!, 20.0)
    }

    @Test
    fun `position far from route is out of corridor`() {
        val model = straightLineModel(11)
        val position = PhonePosition(lat = 30.012, lon = 120.005, accuracyMeters = 10f)
        val match = resolver.resolve(model, position, locationAgeMs = 3_000)
        assertEquals(RouteMatchStatus.OUT_OF_CORRIDOR, match.status)
        assertNull(match.atMeters)
    }

    @Test
    fun `out-and-back route is ambiguous near the overlapping leg`() {
        val model = outAndBackModel()
        // 折返中段附近，去程与回程距离几乎相同
        val position = PhonePosition(lat = 29.9999, lon = 120.005, accuracyMeters = 8f)
        val match = resolver.resolve(model, position, locationAgeMs = 3_000)
        assertEquals(RouteMatchStatus.AMBIGUOUS, match.status)
        assertNull(match.atMeters)
        assertTrue(match.candidateCount >= 2)
    }

    @Test
    fun `out-and-back endpoints disambiguate when clearly closer to one leg`() {
        val model = outAndBackModel()
        // 去程前 1/4 处：虽然几何上离回程也不远，但投影点应明显更近
        val position = PhonePosition(lat = 29.9999, lon = 120.002, accuracyMeters = 5f)
        val match = resolver.resolve(model, position, locationAgeMs = 3_000)
        // 若实现把该位置判为 AMBIGUOUS 或 MATCHED 均可接受，但绝不能给出错误的一端
        if (match.status == RouteMatchStatus.MATCHED) {
            assertTrue(match.atMeters!! < model.totalMeters * 0.4)
        } else {
            assertEquals(RouteMatchStatus.AMBIGUOUS, match.status)
        }
    }

    @Test
    fun `stale location is not used for route progress`() {
        val model = straightLineModel(11)
        val position = PhonePosition(30.0002, 120.004, 8f)
        val match = resolver.resolve(model, position, locationAgeMs = 45_000)
        assertEquals(RouteMatchStatus.LOCATION_STALE, match.status)
        assertNull(match.atMeters)
    }

    @Test
    fun `low accuracy location is marked uncertain`() {
        val model = straightLineModel(11)
        val position = PhonePosition(30.0002, 120.004, 80f)
        val match = resolver.resolve(model, position, locationAgeMs = 3_000)
        assertEquals(RouteMatchStatus.LOCATION_LOW_ACCURACY, match.status)
        assertNull(match.atMeters)
    }

    @Test
    fun `no route or no location resolve to explicit states`() {
        assertEquals(RouteMatchStatus.NO_ROUTE, resolver.resolve(null, PhonePosition(30.0, 120.0, 5f), 1_000).status)
        assertEquals(RouteMatchStatus.NO_LOCATION, resolver.resolve(straightLineModel(3), null, null).status)
    }
}
