package dev.hike.dataassistant.session

import android.content.Context
import android.os.SystemClock
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
    val routeName: String?,
    /** true = 事件流没有 stop，说明进程在记录中被杀。 */
    val interrupted: Boolean
)

/**
 * 会话日志持久化：行式追加文件，进程意外退出后已保存数据仍在。
 * 格式：
 *   v1|hr|epochMs|bpm|sim(0/1)|contact(0/1/2)
 *   v1|ev|epochMs|start|pause|resume|stop
 *   v1|route|路线名
 * 恢复时把 epoch 差值折算为单调时钟差值，保留相对时间关系。
 */
class SessionRepository(context: Context) {

    private val file: File = File(context.filesDir, "hike_session_log.txt")

    fun hasData(): Boolean = file.exists() && file.length() > 0L

    @Synchronized
    fun appendSample(sample: HeartRateSample) {
        val contact = when (sample.contactDetected) {
            true -> 1
            false -> 0
            null -> 2
        }
        appendLine("v1|hr|${sample.receivedAtEpochMs}|${sample.bpm}|${if (sample.isSimulated) 1 else 0}|$contact")
    }

    @Synchronized
    fun appendEvent(event: String) {
        require(event in setOf("start", "pause", "resume", "stop")) { "未知事件：$event" }
        appendLine("v1|ev|${System.currentTimeMillis()}|$event")
    }

    @Synchronized
    fun appendRoute(name: String) {
        appendLine("v1|route|${name.replace('\n', ' ').replace('|', '/')}")
    }

    @Synchronized
    fun readAll(): RestoredSession? {
        if (!file.exists()) return null
        val nowWall = System.currentTimeMillis()
        val nowMono = SystemClock.elapsedRealtime()
        val samples = mutableListOf<HeartRateSample>()
        var lastEvent: String? = null
        var routeName: String? = null
        file.forEachLine { line ->
            val parts = line.split("|")
            when (parts.getOrNull(0)) {
                "v1" -> when (parts.getOrNull(1)) {
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
                    "route" -> routeName = parts.getOrNull(2)
                }
            }
        }
        if (samples.isEmpty() && lastEvent == null) return null
        return RestoredSession(
            samples = samples.filter { !it.isSimulated },
            lastEvent = lastEvent,
            routeName = routeName,
            interrupted = lastEvent != null && lastEvent != "stop"
        )
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun appendLine(line: String) {
        try {
            FileOutputStream(file, true).use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) {
            // 持久化失败不中断记录；下次写入重试
        }
    }
}
