package dev.hike.dataassistant.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxProcessorTest {

    private val processor = GpxProcessor()

    /** 沿经度方向每 0.001° 一个点（北纬 30° 处约 96.5m），共 count 个点。 */
    private fun eastWestPoints(count: Int, lat: Double = 30.0, baseLon: Double = 120.0): List<GpxPoint> =
        (0 until count).map { i -> GpxPoint(lat, baseLon + i * 0.001, eleMeters = null) }

    @Test
    fun `cumulative distance matches haversine for a straight line`() {
        val model = processor.build(GpxTrackSegment(null, eastWestPoints(11)), reversed = false)
        // 北纬 30° 每个 0.001° 经度间隔约 96.49m
        assertEquals(964.9, model.totalMeters, 3.0)
        assertEquals(0.0, model.cumulativeMeters.first(), 1e-6)
        assertEquals(model.totalMeters, model.cumulativeMeters.last(), 1e-6)
    }

    @Test
    fun `elevation noise is smoothed so climb reflects real rise`() {
        // 21 个点、每 10m 一段：真实海拔从 0 线性升到 100m，叠加 ±3m 交替噪声
        val points = (0 until 21).map { i ->
            val ele = i * 5.0 + if (i % 2 == 0) 3.0 else -3.0
            GpxPoint(30.0, 120.0 + i * 0.0001, ele)
        }
        val model = processor.build(GpxTrackSegment(null, points), reversed = false)
        // 不平滑会累加出 ~160m；平滑后应接近真实 100m
        assertEquals(100.0, model.totalClimbMeters!!, 12.0)
    }

    @Test
    fun `missing elevation means climb unknown not zero`() {
        val model = processor.build(GpxTrackSegment(null, eastWestPoints(5)), reversed = false)
        assertNull(model.totalClimbMeters)
        assertNull(model.smoothedElevation)
    }

    @Test
    fun `reversed route keeps total distance but flips remaining`() {
        val model = processor.build(GpxTrackSegment(null, eastWestPoints(11)), reversed = false)
        val reversed = processor.build(GpxTrackSegment(null, eastWestPoints(11)), reversed = true)
        assertEquals(model.totalMeters, reversed.totalMeters, 1e-6)
        // 反向路线的新起点即原终点：从头走仍是全程，走到头剩余为 0
        assertEquals(model.totalMeters, processor.remainingMeters(reversed, 0.0), 1e-6)
        assertEquals(0.0, processor.remainingMeters(reversed, reversed.totalMeters), 1e-6)
    }

    @Test
    fun `remaining distance and climb from mid route position`() {
        // 前半程爬升 100m，后半程平坦：11 个点，每点步长 0.001°
        val points = (0 until 21).map { i ->
            val ele = if (i <= 10) i * 10.0 else 100.0
            GpxPoint(30.0, 120.0 + i * 0.001, ele)
        }
        val model = processor.build(GpxTrackSegment(null, points), reversed = false)
        val mid = model.totalMeters / 2
        assertEquals(mid, processor.remainingMeters(model, mid), 2.0)
        // 前半爬完后，剩余爬升接近 0
        assertEquals(0.0, processor.remainingClimbMeters(model, mid + 5.0)!!, 10.0)
        // 从起点看剩余爬升接近 100m
        assertEquals(100.0, processor.remainingClimbMeters(model, 0.0)!!, 15.0)
    }

    @Test
    fun `next climb reports distance to a significant upcoming ascent`() {
        // 500m 平路 + 300m 内爬 100m + 平路
        val points = ArrayList<GpxPoint>()
        for (i in 0 until 80) {
            val arc = i * 10.0 // 800m，共 80 点、每点 10m
            val ele = when {
                arc < 500 -> 0.0
                arc < 800 -> (arc - 500) / 300.0 * 100.0
                else -> 100.0
            }
            points.add(GpxPoint(30.0, 120.0 + i * 0.0001, ele))
        }
        val model = processor.build(GpxTrackSegment(null, points), reversed = false)
        val next = processor.nextClimb(model, 0.0)
        assertTrue("nextClimb 应当存在", next != null)
        // 平滑桶分辨率约 50m：谷底定位在最后一个平坦桶中心，允许 ±80m
        assertEquals(500.0, next!!.distanceToStartMeters, 80.0)
        assertEquals(100.0, next.climbMeters, 25.0)
        // 已越过爬升段后不再报告
        assertNull(processor.nextClimb(model, 700.0))
    }
}
