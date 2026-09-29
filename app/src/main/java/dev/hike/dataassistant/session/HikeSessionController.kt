package dev.hike.dataassistant.session

import android.content.Context
import android.content.Intent
import dev.hike.dataassistant.BuildConfig
import dev.hike.dataassistant.heartrate.BleHeartRateProvider
import dev.hike.dataassistant.heartrate.HeartRateEngine
import dev.hike.dataassistant.heartrate.HeartRateProvider
import dev.hike.dataassistant.heartrate.SimulatedHeartRateProvider
import dev.hike.dataassistant.service.HikeSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 会话总控：把会话计时、心率引擎、数据源与持久化接在一起。
 * 心率接收在后台由前台服务保活（服务与本控制器同进程）。
 */
class HikeSessionController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    val session = HikeSession(AndroidMonotonicClock)
    val engine = HeartRateEngine(AndroidMonotonicClock, isRecordingAt = session::isRecordingAt)
    val bleProvider: BleHeartRateProvider = BleHeartRateProvider(context)
    val simulatedProvider: SimulatedHeartRateProvider? =
        if (BuildConfig.DEBUG) SimulatedHeartRateProvider(scope) else null

    val repository = SessionRepository(context)

    private var sampleJob: Job? = null
    private var activeProvider: HeartRateProvider? = null

    /** 当前实际使用的数据源（真实 BLE 或 debug 模拟）。 */
    var useSimulatedSource: Boolean = false
        private set

    fun attachProvider(provider: HeartRateProvider) {
        sampleJob?.cancel()
        activeProvider = provider
        sampleJob = scope.launch {
            provider.samples.collect { sample ->
                engine.onSample(
                    bpm = sample.bpm,
                    wallMs = sample.receivedAtEpochMs,
                    contactDetected = sample.contactDetected,
                    isSimulated = sample.isSimulated
                )
                repository.appendSample(sample)
            }
        }
    }

    fun startScan() = bleProvider.startScan()

    fun stopScan() = bleProvider.stopScan()

    fun connectDevice(address: String) {
        useSimulatedSource = false
        engine.simulatedEnabled = false
        engine.clear() // 换源不沿用前一源的读数
        attachProvider(bleProvider)
        bleProvider.connect(address)
        maybeStartForegroundService()
    }

    /** 仅 debug 构建：启用模拟心率源。 */
    fun connectSimulated() {
        val sim = simulatedProvider ?: return
        useSimulatedSource = true
        engine.simulatedEnabled = true
        engine.clear()
        attachProvider(sim)
        sim.connect("simulated")
    }

    fun startSession() {
        if (repository.hasSessionData()) repository.clearSessionLog() // UI 在调用前已向用户确认覆盖
        session.reset()
        session.start()
        repository.appendEvent("start")
        engine.clear() // 新会话不继承上一会话的心率读数
        maybeStartForegroundService()
    }

    fun pauseSession() {
        session.pause()
        repository.appendEvent("pause")
    }

    fun resumeSession() {
        session.resume()
        repository.appendEvent("resume")
    }

    fun stopSession() {
        if (session.state == SessionState.RECORDING || session.state == SessionState.PAUSED) {
            session.stop()
            repository.appendEvent("stop")
        }
        activeProvider?.shutdown()
        activeProvider = null
        sampleJob?.cancel()
        context.stopService(Intent(context, HikeSessionService::class.java))
    }

    /**
     * 前台服务（connectedDevice 类型）只在同时满足以下条件时启动：
     * 会话记录中、数据源为真实 BLE、蓝牙运行时权限已授予。
     * Android 14+ 在未授予相应权限时以该类型 startForeground 会抛安全异常，
     * 因此无权限时退化为前台运行（无后台保活），不崩溃。
     * 模拟源（仅 debug）不启动前台服务。
     */
    private fun maybeStartForegroundService() {
        if (session.state != SessionState.RECORDING) return
        if (useSimulatedSource) return
        if (activeProvider !== bleProvider) return
        if (!bleProvider.hasRequiredPermission()) return
        context.startForegroundService(Intent(context, HikeSessionService::class.java))
    }

    companion object {
        /** 进程级单例：Activity/Service 重建与通知操作共享同一会话状态。 */
        fun getOrCreate(context: Context): HikeSessionController =
            controllerRef ?: HikeSessionController(
                context.applicationContext,
                CoroutineScope(SupervisorJob() + Dispatchers.Default)
            ).also { controllerRef = it }

        @Volatile
        private var controllerRef: HikeSessionController? = null
    }
}
