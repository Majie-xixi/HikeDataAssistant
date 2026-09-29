package dev.hike.dataassistant.session

/** 单调时钟抽象，便于测试注入。生产实现用 SystemClock.elapsedRealtime()。 */
interface MonotonicClock {
    fun nowMs(): Long
}

enum class SessionState { IDLE, RECORDING, PAUSED, STOPPED }

/**
 * 会话计时状态机：elapsed 为自首次开始的经过时间（含暂停），
 * active 为各记录区间之和；全部基于单调时钟，不受系统时间调整影响。
 */
class HikeSession(private val clock: MonotonicClock) {

    private val lock = Any()
    private var segments = mutableListOf<Pair<Long, Long>>() // 活动区间 [start, end)
    private var currentSegmentStart: Long? = null
    private var firstStartMs: Long? = null

    var state: SessionState = SessionState.IDLE
        private set

    /** 自首次开始（含暂停）的经过毫秒；未开始返回 null。 */
    fun elapsedDurationMs(): Long? {
        synchronized(lock) {
            val start = firstStartMs ?: return null
            return when (state) {
                SessionState.STOPPED -> segments.lastOrNull()?.second?.minus(start)
                else -> clock.nowMs() - start
            }
        }
    }

    /** 实际活动毫秒（不含暂停区间）；未开始返回 null。 */
    fun activeDurationMs(): Long? {
        synchronized(lock) {
            if (firstStartMs == null) return null
            return segments.sumOf { it.second - it.first } + runningExtra()
        }
    }

    /** 指定单调时刻是否处于记录中（供心率引擎过滤暂停期样本）。 */
    fun isRecordingAt(monotonicMs: Long): Boolean {
        synchronized(lock) {
            if (monotonicMs < (firstStartMs ?: Long.MAX_VALUE)) return false
            return segments.any { monotonicMs >= it.first && monotonicMs < it.second } ||
                (currentSegmentStart != null && monotonicMs >= currentSegmentStart!!)
        }
    }

    /** 活动区间列表（拷贝），用于持久化与恢复。 */
    fun activeWindowsMs(): List<Pair<Long, Long>> {
        synchronized(lock) {
            val result = segments.toMutableList()
            currentSegmentStart?.let { result.add(it to clock.nowMs()) }
            return result
        }
    }

    fun start() {
        synchronized(lock) {
            check(state == SessionState.IDLE) { "会话只能在初始状态开始，当前：$state" }
            val now = clock.nowMs()
            firstStartMs = now
            currentSegmentStart = now
            state = SessionState.RECORDING
        }
    }

    fun pause() {
        synchronized(lock) {
            check(state == SessionState.RECORDING) { "只有记录中可以暂停，当前：$state" }
            val now = clock.nowMs()
            segments.add(currentSegmentStart!! to now)
            currentSegmentStart = null
            state = SessionState.PAUSED
        }
    }

    fun resume() {
        synchronized(lock) {
            check(state == SessionState.PAUSED) { "只有暂停中可以继续，当前：$state" }
            currentSegmentStart = clock.nowMs()
            state = SessionState.RECORDING
        }
    }

    fun stop() {
        synchronized(lock) {
            check(state == SessionState.RECORDING || state == SessionState.PAUSED) {
                "会话未在运行，当前：$state"
            }
            if (currentSegmentStart != null) {
                segments.add(currentSegmentStart!! to clock.nowMs())
                currentSegmentStart = null
            }
            state = SessionState.STOPPED
        }
    }

    /** 结束后回到初始状态，允许开始新会话。 */
    fun reset() {
        synchronized(lock) {
            segments = mutableListOf()
            currentSegmentStart = null
            firstStartMs = null
            state = SessionState.IDLE
        }
    }

    private fun runningExtra(): Long {
        val start = currentSegmentStart ?: return 0L
        return (clock.nowMs() - start).coerceAtLeast(0L)
    }
}
