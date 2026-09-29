package dev.hike.dataassistant.route

import dev.hike.dataassistant.data.PhonePosition
import dev.hike.dataassistant.gpx.Geo
import dev.hike.dataassistant.gpx.GpxProcessor

enum class RouteMatchStatus {
    MATCHED,             // 已投影到路线
    AMBIGUOUS,           // 折返/交叉：多处匹配无法判定
    OUT_OF_CORRIDOR,     // 距路线过远
    LOCATION_STALE,      // 定位超过 30 秒
    LOCATION_LOW_ACCURACY, // 定位精度差于 30 米
    NO_LOCATION,         // 没有可用定位
    NO_ROUTE             // 没有导入路线
}

data class RouteMatch(
    val status: RouteMatchStatus,
    val atMeters: Double?,
    val candidateCount: Int
)

/**
 * 把最新有效定位投影到 GPX，给出沿线路线位置。
 *
 * 规则（开发文档 §6）：只在匹配明确时给出进度；折返、交叉、环线
 * 起终点重合导致多处可信匹配时返回 AMBIGUOUS，绝不猜一段。
 */
class RouteContextResolver {

    companion object {
        /** 定位时效与精度门槛（开发文档 §4）。 */
        const val MAX_LOCATION_AGE_MS: Long = 30_000
        const val MAX_ACCURACY_M = 30.0
        /** 基础匹配走廊宽度。 */
        private const val BASE_CORRIDOR_M = 30.0
        private const val CORRIDOR_CAP_M = 80.0
        /** 弧向相距超过该值的两个候选视为不同路段。 */
        private const val CANDIDATE_SEPARATION_M = 200.0
        /** 候选间垂直距离差小于该值视为同等可信。 */
        private const val CANDIDATE_DISTANCE_TIE_M = 50.0
    }

    fun resolve(
        model: GpxProcessor.RouteModel?,
        position: PhonePosition?,
        locationAgeMs: Long?
    ): RouteMatch {
        if (model == null) return RouteMatch(RouteMatchStatus.NO_ROUTE, null, 0)
        if (position == null) return RouteMatch(RouteMatchStatus.NO_LOCATION, null, 0)
        if (locationAgeMs != null && locationAgeMs > MAX_LOCATION_AGE_MS) {
            return RouteMatch(RouteMatchStatus.LOCATION_STALE, null, 0)
        }
        if (position.accuracyMeters > MAX_ACCURACY_M) {
            return RouteMatch(RouteMatchStatus.LOCATION_LOW_ACCURACY, null, 0)
        }

        val corridor = (BASE_CORRIDOR_M + position.accuracyMeters).coerceAtMost(CORRIDOR_CAP_M)

        // 逐段投影，收集走廊内候选（局部最优：每段最多一个）
        val candidates = mutableListOf<Pair<Double, Double>>() // (arcMeters, distanceMeters)
        val pts = model.points
        val cum = model.cumulativeMeters
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val proj = Geo.projectOntoSegment(position.lat, position.lon, a.lat, a.lon, b.lat, b.lon)
            if (proj.distanceMeters > corridor) continue
            val arc = cum[i] + proj.t * (cum[i + 1] - cum[i])
            candidates.add(arc to proj.distanceMeters)
        }
        if (candidates.isEmpty()) return RouteMatch(RouteMatchStatus.OUT_OF_CORRIDOR, null, 0)

        // 去掉局部劣势候选：同一弧向邻域（<200m）内只保留距离最近者
        val sorted = candidates.sortedBy { it.first }
        val clusters = mutableListOf<Pair<Double, Double>>()
        for (c in sorted) {
            val last = clusters.lastOrNull()
            if (last != null && c.first - last.first < CANDIDATE_SEPARATION_M) {
                if (c.second < last.second) clusters[clusters.size - 1] = c
            } else {
                clusters.add(c)
            }
        }

        val best = clusters.minByOrNull { it.second }!!
        val tied = clusters.count {
            it.second <= best.second + CANDIDATE_DISTANCE_TIE_M
        }
        return if (clusters.size >= 2 && tied >= 2) {
            RouteMatch(RouteMatchStatus.AMBIGUOUS, null, clusters.size)
        } else {
            RouteMatch(RouteMatchStatus.MATCHED, best.first, clusters.size)
        }
    }
}
