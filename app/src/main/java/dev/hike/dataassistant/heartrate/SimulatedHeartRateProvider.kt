package dev.hike.dataassistant.heartrate

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 调试模拟心率源：仅 debug 构建可被创建（由调用方保证），
 * 每个样本都带 isSimulated=true，UI 与分享文案醒目标注。
 */
class SimulatedHeartRateProvider(
    private val scope: CoroutineScope
) : HeartRateProvider {

    private val _connectionState = MutableStateFlow(HrConnectionState.IDLE)
    override val connectionState: StateFlow<HrConnectionState> = _connectionState.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredHrDevice>>(emptyList())
    override val discovered: StateFlow<List<DiscoveredHrDevice>> = _discovered.asStateFlow()

    private val _samples = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 64)
    override val samples: SharedFlow<HeartRateSample> = _samples.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var job: Job? = null
    private var bpm = 118

    override fun startScan() {
        // 模拟源没有扫描；直接连接"模拟手表"
        _lastError.value = "模拟源无需扫描"
    }

    override fun stopScan() = Unit

    override fun connect(address: String) {
        job?.cancel()
        _connectionState.value = HrConnectionState.CONNECTED
        job = scope.launch {
            while (isActive) {
                bpm = (bpm + Random.nextInt(-4, 5)).coerceIn(96, 158)
                _samples.tryEmit(
                    HeartRateSample(
                        bpm = bpm,
                        receivedAtEpochMs = System.currentTimeMillis(),
                        receivedAtMonotonicMs = SystemClock.elapsedRealtime(),
                        contactDetected = true,
                        isSimulated = true
                    )
                )
                delay(3_000)
            }
        }
    }

    override fun shutdown() {
        job?.cancel()
        job = null
        _connectionState.value = HrConnectionState.STOPPED
    }
}
