package dev.hike.dataassistant.gpx

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 球面几何工具（haversine 与局部平面投影）。 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_008.8

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * 点到线段的最近距离与投影参数 t∈[0,1]。
     * 使用局部等距圆柱近似（徒步尺度误差可忽略），避免逐点三角函数开销。
     */
    fun projectOntoSegment(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Projection {
        val cosLat = cos(Math.toRadians((aLat + bLat) / 2))
        fun x(lon: Double) = Math.toRadians(lon) * cosLat * EARTH_RADIUS_M
        fun y(lat: Double) = Math.toRadians(lat) * EARTH_RADIUS_M

        val ax = x(aLon); val ay = y(aLat)
        val bx = x(bLon); val by = y(bLat)
        val px = x(pLon); val py = y(pLat)

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0
        else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        val distance = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
        return Projection(t, distance)
    }

    data class Projection(val t: Double, val distanceMeters: Double)
}
