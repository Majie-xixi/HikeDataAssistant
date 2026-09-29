package dev.hike.dataassistant.session

import android.content.Context
import android.os.SystemClock
import dev.hike.dataassistant.gpx.GpxPoint
import dev.hike.dataassistant.gpx.GpxTrackSegment
import dev.hike.dataassistant.heartrate.HeartRateSample
import java.io.File
import java.io.FileOutputStream

/** 生产用单调时钟。 */
object AndroidMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/** 一次进程退出前的已保存会话数据。 */
data class RestoredSession(
    val samples: List<HeartRateSample>,
    /** 最后一个事件；null 表示文件里只有样本没有事件。 */
    val lastEvent: String?,
    /** true = 事件流没有 stop，说明进程在记录中被杀。 */
    val interrupted: Boolean
)

/** 已保存的导入路线（跨会话保留，直至导入新路线）。 */
data class RestoredRoute(
    val segment: GpxTrackSegment,
    val reversed: Boolean
)

/**
 * 本地持久化，两个独立文件：
 * - hike_session_log.txt：本次会话的心率样本与事件（新会话开始时清空）
 * - last_route.txt：用户导入的路线分段与方向（跨会话保留）
 *
 * 行式追加，进程意外退出后已写入内容仍在。恢复样本时把 epoch 差值
 * 折算为单调时钟差值，保留相对时间关系。
 * 格式：
 *   会话日志：v1|hr|epochMs|bpm|sim(0/1)|contact(0/1/2) / v1|ev|epochMs|event
 *   路线：v1|rt|name|reversed(0/1) / v1|pt|lat|lon|ele(可空)
 */
class SessionRepository(context: Context) {

    private val sessionFile: File = File(context.filesDir, "hike_session_log.txt")
    private val routeFile: File = File(context.filesDir, "last_route.txt")

    // ---------- 会话日志 ----------

    fun hasSessionData(): Boolean = sessionFile.exists() && sessionFile.length() > 0L

    @Synchronized
    fun appendSample(sample: HeartRateSample) {
        val contact = when (sample.contactDetected) {
            true -> 1
            false -> 0
            null -> 2
        }
        appendLine(
            sessionFile,
            "v1|hr|${sample.receivedAtEpochMs}|${sample.bpm}|${if (sample.isSimulated) 1 else 0}|$contact"
        )
    }

    @Synchronized
    fun appendEvent(event: String) {
        require(event in setOf("start", "pause", "resume", "stop")) { "未知事件：$event" }
        appendLine(sessionFile, "v1|ev|${System.currentTimeMillis()}|$event")
    }

    @Synchronized
    fun readSession(): RestoredSession? {
        if (!sessionFile.exists()) return null
        val nowWall = System.currentTimeMillis()
        val nowMono = SystemClock.elapsedRealtime()
        val samples = mutableListOf<HeartRateSample>()
        var lastEvent: String? = null
        sessionFile.forEachLine { line ->
            val parts = line.split("|")
            when (parts.getOrNull(1)) {
                "hr" -> {
                    val epoch = parts.getOrNull(2)?.toLongOrNull() ?: return@forEachLine
                    val bpm = parts.getOrNull(3)?.toIntOrNull() ?: return@forEachLine
                    val sim = parts.getOrNull(4) == "1"
                    val contact = when (parts.getOrNull(5)) {
                        "1" -> true
                        "0" -> false
                        else -> null
                    }
                    samples.add(
                        HeartRateSample(
                            bpm = bpm,
                            receivedAtEpochMs = epoch,
                            receivedAtMonotonicMs = (nowMono - (nowWall - epoch)).coerceAtLeast(0L),
                            contactDetected = contact,
                            isSimulated = sim
                        )
                    )
                }

                "ev" -> lastEvent = parts.getOrNull(3)
            }
        }
        if (samples.isEmpty() && lastEvent == null) return null
        return RestoredSession(
            samples = samples.filter { !it.isSimulated },
            lastEvent = lastEvent,
            interrupted = lastEvent != null && lastEvent != "stop"
        )
    }

    /** 仅清会话日志；导入的路线保留。 */
    @Synchronized
    fun clearSessionLog() {
        if (sessionFile.exists()) sessionFile.delete()
    }

    // ---------- 路线 ----------

    @Synchronized
    fun saveRoute(segment: GpxTrackSegment, reversed: Boolean) {
        try {
            routeFile.outputStream().use { out ->
                out.write(
                    ("v1|rt|${(segment.name ?: "").replace('|', '/')}|${if (reversed) 1 else 0}\n")
                        .toByteArray(Charsets.UTF_8)
                )
                segment.points.forEach { p ->
                    val ele = p.eleMeters?.let { "%.3f".format(java.util.Locale.US, it) } ?: ""
                    out.write(("v1|pt|${p.lat}|${p.lon}|$ele\n").toByteArray(Charsets.UTF_8))
                }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun readRoute(): RestoredRoute? {
        if (!routeFile.exists()) return null
        var name: String? = null
        var reversed = false
        val points = mutableListOf<GpxPoint>()
        routeFile.forEachLine { line ->
            val parts = line.split("|")
            when (parts.getOrNull(1)) {
                "rt" -> {
                    name = parts.getOrNull(2)?.ifEmpty { null }
                    reversed = parts.getOrNull(3) == "1"
                }

                "pt" -> {
                    val lat = parts.getOrNull(2)?.toDoubleOrNull() ?: return@forEachLine
                    val lon = parts.getOrNull(3)?.toDoubleOrNull() ?: return@forEachLine
                    val ele = parts.getOrNull(4)?.toDoubleOrNull()
                    points.add(GpxPoint(lat, lon, ele))
                }
            }
        }
        if (points.size < 2) return null
        return RestoredRoute(GpxTrackSegment(name, points), reversed)
    }

    private fun appendLine(file: File, line: String) {
        try {
            FileOutputStream(file, true).use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) {
            // 持久化失败不中断记录；下次写入重试
        }
    }
}
