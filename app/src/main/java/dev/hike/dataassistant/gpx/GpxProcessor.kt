package dev.hike.dataassistant.gpx

import dev.hike.dataassistant.data.PlannedClimb
import kotlin.math.abs

/**
 * GPX 路线预计算（导入时一次完成，快照时只查表）：
 * 沿线累计里程、海拔平滑剖面、总爬升、剩余里程/爬升、前方爬升段。
 */
class GpxProcessor {

    /** 不可变路线模型：点序已按用户选择的方向排定。 */
    data class RouteModel(
        val segmentName: String?,
        val reversed: Boolean,
        val points: List<GpxPoint>,
        val totalMeters: Double,
        /** 每个点处的沿线累计里程，长度与点数一致，首点为 0。 */
        val cumulativeMeters: DoubleArray,
        /** 距离分桶平滑后的海拔剖面（桶中心里程 + 海拔）；无海拔时为 null。 */
        val smoothedElevation: List<BucketElevation>?,
        val totalClimbMeters: Double?
    )

    data class BucketElevation(val atMeters: Double, val eleMeters: Double)

    companion object {
        /** 海拔平滑桶的弧长跨度：小于该尺度的起伏视为噪声。 */
        private const val SMOOTH_BUCKET_M = 50.0
        /** 单独计为"显著爬升段"的最小上升量。 */
        private const val CLIMB_SEGMENT_MIN_M = 30.0
        /** 爬升段内允许的回落深度，超过即认为该段结束。 */
        private const val CLIMB_TOLERANCE_M = 10.0
        /** 平路推进谷底的判定容差：平滑后 ±2m 内视为同一谷底水平。 */
        private const val VALLEY_EPS_M = 2.0
        /** 前方爬升的搜索范围（避免报告 5km 后的长缓坡作为"前方爬升"）。 */
        private const val NEXT_CLIMB_LOOKAHEAD_M = 3_000.0
    }

    fun build(segment: GpxTrackSegment, reversed: Boolean): RouteModel {
        val pts = if (reversed) segment.points.asReversed() else segment.points
        require(pts.size >= 2) { "路线段至少需要 2 个点" }

        val cumulative = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            val step = Geo.haversineMeters(
                pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon
            )
            cumulative[i] = cumulative[i - 1] + step
        }
        val total = cumulative.last()

        val hasEle = pts.all { it.eleMeters != null }
        val buckets: List<BucketElevation>? = if (hasEle) {
            val result = mutableListOf<BucketElevation>()
            var bucketStartArc = 0.0
            var sum = 0.0
            var count = 0
            for (i in pts.indices) {
                sum += pts[i].eleMeters!!
                count++
                // 桶以里程跨度划分：达到 SMOOTH_BUCKET_M 或到达末尾即封桶
                if (cumulative[i] - bucketStartArc >= SMOOTH_BUCKET_M || i == pts.indices.last) {
                    val arcCenter = (bucketStartArc + cumulative[i]) / 2
                    result.add(BucketElevation(arcCenter, sum / count))
                    bucketStartArc = cumulative[i]
                    sum = 0.0
                    count = 0
                }
            }
            // 用原始首末海拔做边界锚点：平滑只作用于内部噪声，
            // 否则首末桶的"中点均值"会系统性低估全程爬升
            val anchored = mutableListOf<BucketElevation>()
            anchored.add(BucketElevation(0.0, pts.first().eleMeters!!))
            for (b in result) {
                val last = anchored.last()
                if (b.atMeters - last.atMeters > SMOOTH_BUCKET_M / 2) anchored.add(b)
            }
            val last = result.lastOrNull()
            if (last != null && total - last.atMeters > SMOOTH_BUCKET_M / 2) {
                anchored.add(BucketElevation(total, pts.last().eleMeters!!))
            } else if (last != null) {
                // 路线很短、只有一个中心桶：末锚点直接落到终点
                anchored.add(BucketElevation(total, pts.last().eleMeters!!))
            }
            anchored
        } else {
            null
        }

        val totalClimb = buckets?.let { sumPositiveDeltas(it) }
        return RouteModel(
            segmentName = segment.name,
            reversed = reversed,
            points = pts,
            totalMeters = total,
            cumulativeMeters = cumulative,
            smoothedElevation = buckets,
            totalClimbMeters = totalClimb
        )
    }

    fun remainingMeters(model: RouteModel, atMeters: Double): Double =
        (model.totalMeters - atMeters.coerceIn(0.0, model.totalMeters))

    /** 从 atMeters 沿行进方向到终点的计划爬升；无海拔返回 null。 */
    fun remainingClimbMeters(model: RouteModel, atMeters: Double): Double? {
        val buckets = model.smoothedElevation ?: return null
        var climb = 0.0
        var prev: Double? = null
        for (b in buckets) {
            if (b.atMeters < atMeters) continue
            if (prev != null && b.eleMeters > prev) climb += b.eleMeters - prev
            prev = b.eleMeters
        }
        return climb
    }

    /**
     * 前方第一段显著爬升：只报告起点在 atMeters 之后的段。
     * 谷底（谷底会随平路推进，避免把前方平路算进爬升段）；爬升段内
     * 允许不超过 CLIMB_TOLERANCE_M 的回落，超过即视为段结束。
     * 已身处爬升段内时该段起点在身后、不报告，剩余爬升由 remainingClimb 覆盖。
     */
    fun nextClimb(model: RouteModel, atMeters: Double): PlannedClimb? {
        val buckets = model.smoothedElevation ?: return null
        val forward = buckets.filter { it.atMeters >= atMeters }
        if (forward.size < 2) return null

        var valleyEle = forward.first().eleMeters
        var valleyArc = forward.first().atMeters
        var peakEle = valleyEle
        var peakArc = valleyArc

        fun closeSegment(): PlannedClimb? {
            val rise = peakEle - valleyEle
            if (rise < CLIMB_SEGMENT_MIN_M) return null
            val distanceToStart = (valleyArc - atMeters).coerceAtLeast(0.0)
            if (distanceToStart > NEXT_CLIMB_LOOKAHEAD_M) return null
            return PlannedClimb(
                distanceToStartMeters = distanceToStart,
                climbMeters = rise,
                lengthMeters = (peakArc - valleyArc).coerceAtLeast(0.0)
            )
        }

        for (b in forward) {
            val ele = b.eleMeters
            when {
                ele <= valleyEle + VALLEY_EPS_M -> {
                    // 仍处于（或回到）谷底水平：谷底随平路推进
                    valleyEle = ele
                    valleyArc = b.atMeters
                    peakEle = ele
                    peakArc = b.atMeters
                }

                ele > peakEle -> {
                    peakEle = ele
                    peakArc = b.atMeters
                }

                peakEle - ele > CLIMB_TOLERANCE_M -> {
                    // 明显回落：尝试关闭当前爬升段，从新谷底重新开始
                    closeSegment()?.let { return it }
                    valleyEle = ele
                    valleyArc = b.atMeters
                    peakEle = ele
                    peakArc = b.atMeters
                }
                // 段内小幅回落：容忍，继续等新高点
            }
        }
        return closeSegment()
    }

    /** 给定里程处的平滑海拔（用于手动选段时的参考展示）。 */
    fun elevationAt(model: RouteModel, atMeters: Double): Double? {
        val buckets = model.smoothedElevation ?: return null
        if (buckets.isEmpty()) return null
        return buckets.minByOrNull { abs(it.atMeters - atMeters) }?.eleMeters
    }

    private fun sumPositiveDeltas(buckets: List<BucketElevation>): Double {
        var climb = 0.0
        var prev: BucketElevation? = null
        for (b in buckets) {
            val p = prev
            if (p != null && b.eleMeters > p.eleMeters) climb += b.eleMeters - p.eleMeters
            prev = b
        }
        return climb
    }
}
