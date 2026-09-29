package dev.hike.dataassistant.data

/**
 * 统一数据源标识：开发文档 §8 约定的 source 取值。
 */
object DataSources {
    const val GT5_BLE = "gt5_ble"
    const val PHONE_LOCATION = "phone_location"
    const val IMPORTED_GPX = "imported_gpx"
    const val USER = "user"
}

/** 数据质量状态：新鲜 / 过期 / 缺失 / 不确定。 */
enum class ReadingStatus { FRESH, STALE, MISSING, UNCERTAIN }

/**
 * 所有外部数据统一包装：值可能缺失，但来源、采样时间与状态永远明确。
 * 模拟数据必须携带 isSimulated=true，展示与导出时醒目标注。
 */
data class Reading<T>(
    val value: T?,
    val source: String,
    val sampledAtEpochMs: Long?,
    val status: ReadingStatus,
    val note: String? = null,
    val isSimulated: Boolean = false
)

/** 手机定位原始值（分享文案中不出现坐标本身）。 */
data class PhonePosition(
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double? = null
)

/** GPX 剖面上前方一段显著爬升。 */
data class PlannedClimb(
    val distanceToStartMeters: Double,
    val climbMeters: Double,
    val lengthMeters: Double
)
