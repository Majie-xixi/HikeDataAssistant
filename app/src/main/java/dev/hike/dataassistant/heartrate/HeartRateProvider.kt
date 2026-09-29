package dev.hike.dataassistant.heartrate

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** BLE 心率源连接状态。 */
enum class HrConnectionState {
    IDLE,               // 未启动
    SCANNING,           // 扫描暴露 0x180D 的设备
    CONNECTING,         // 用户已选设备，建立 GATT
    CONNECTED,          // 已订阅 0x2A37，正在接收
    RECONNECTING,       // 意外断开，等待重试
    STOPPED             // 会话结束，已释放
}

/** 扫描发现的候选设备。 */
data class DiscoveredHrDevice(
    val name: String?,
    val address: String,
    val rssi: Int
)

/**
 * 心率源抽象（开发文档 §10）：连接状态、样本流、断连重试。
 * BLE 与模拟源是两个实现；模拟源仅 debug 构建允许启用。
 */
interface HeartRateProvider {
    val connectionState: StateFlow<HrConnectionState>
    val discovered: StateFlow<List<DiscoveredHrDevice>>
    val samples: SharedFlow<HeartRateSample>
    val lastError: StateFlow<String?>

    /** 开始扫描（需要已授予蓝牙权限）。 */
    fun startScan()

    fun stopScan()

    /** 连接指定设备并订阅心率通知。 */
    fun connect(address: String)

    /** 主动断开并释放（会话结束时调用）。 */
    fun shutdown()
}
