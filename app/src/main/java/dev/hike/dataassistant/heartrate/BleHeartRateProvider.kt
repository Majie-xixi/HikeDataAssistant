package dev.hike.dataassistant.heartrate

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 标准 BLE 心率源（HRS 0x180D / Heart Rate Measurement 0x2A37）。
 * 适用于任何开启"心率广播"的手表（含华为 GT5，需实机验证，见开发文档 §5）。
 *
 * 线程模型：所有 GATT 操作经主线程 Handler 串行化；样本回调在 binder 线程
 * 只做解析与发射。断开后在会话存续期间自动重连（退避 5s/10s/30s，最多 5 次）。
 */
class BleHeartRateProvider(
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) : HeartRateProvider {

    companion object {
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SCAN_PERIOD_MS = 15_000L
        private val RECONNECT_DELAYS_MS = longArrayOf(5_000, 10_000, 30_000, 30_000, 30_000)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val btManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager?.adapter

    private val _connectionState = MutableStateFlow(HrConnectionState.IDLE)
    override val connectionState: StateFlow<HrConnectionState> = _connectionState.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredHrDevice>>(emptyList())
    override val discovered: StateFlow<List<DiscoveredHrDevice>> = _discovered.asStateFlow()

    private val _samples = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 256)
    override val samples: SharedFlow<HeartRateSample> = _samples.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var targetAddress: String? = null
    private var reconnectAttempt = 0
    private var userRequestedShutdown = AtomicBoolean(false)
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.name else @Suppress("DEPRECATION") device.name
            val current = _discovered.value.toMutableList()
            val entry = DiscoveredHrDevice(name, device.address, result.rssi)
            current.removeAll { it.address == device.address }
            current.add(0, entry)
            _discovered.value = current.take(20)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _connectionState.value = HrConnectionState.IDLE
            _lastError.value = "扫描失败（code=$errorCode）"
            onLog("BLE scan failed: $errorCode")
        }
    }

    override fun startScan() {
        val adapter = adapter ?: run {
            _lastError.value = "本机没有蓝牙"
            return
        }
        if (!hasBluetoothPermission()) {
            _lastError.value = "缺少蓝牙权限（扫描/连接）"
            return
        }
        if (!adapter.isEnabled) {
            _lastError.value = "蓝牙未开启"
            return
        }
        if (scanning) return
        scanning = true
        userRequestedShutdown.set(false)
        _discovered.value = emptyList()
        _lastError.value = null
        _connectionState.value = HrConnectionState.SCANNING
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(HR_SERVICE)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        adapter.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            ?: run {
                scanning = false
                _connectionState.value = HrConnectionState.IDLE
                _lastError.value = "无法启动 BLE 扫描"
            }
        mainHandler.postDelayed({
            if (scanning) stopScan()
        }, SCAN_PERIOD_MS)
    }

    override fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            onLog("stopScan SecurityException: ${e.message}")
        }
        if (_connectionState.value == HrConnectionState.SCANNING) {
            _connectionState.value = HrConnectionState.IDLE
        }
    }

    override fun connect(address: String) {
        if (!hasBluetoothPermission()) {
            _lastError.value = "缺少蓝牙权限（连接）"
            return
        }
        stopScan()
        targetAddress = address
        reconnectAttempt = 0
        userRequestedShutdown.set(false)
        connectInternal(address)
    }

    private fun connectInternal(address: String) {
        val adapter = adapter ?: return
        if (!adapter.isEnabled) {
            scheduleReconnect()
            return
        }
        _connectionState.value = HrConnectionState.CONNECTING
        try {
            val device: BluetoothDevice = adapter.getRemoteDevice(address)
            gatt?.close()
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            _lastError.value = "连接失败：缺少蓝牙权限"
            _connectionState.value = HrConnectionState.IDLE
        } catch (e: IllegalArgumentException) {
            _lastError.value = "连接失败：设备地址非法"
            _connectionState.value = HrConnectionState.IDLE
        }
    }

    override fun shutdown() {
        userRequestedShutdown.set(true)
        stopScan()
        mainHandler.removeCallbacksAndMessages(null)
        closeGatt()
        targetAddress = null
        _connectionState.value = HrConnectionState.STOPPED
    }

    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            onLog("closeGatt SecurityException: ${e.message}")
        }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = HrConnectionState.CONNECTING
                    try {
                        g.discoverServices()
                    } catch (e: SecurityException) {
                        _lastError.value = "发现服务失败：缺少蓝牙权限"
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (userRequestedShutdown.get()) {
                        _connectionState.value = HrConnectionState.STOPPED
                        return
                    }
                    closeGattQuietly(g)
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("onServicesDiscovered status=$status")
                closeGattQuietly(g)
                scheduleReconnect()
                return
            }
            val service = g.getService(HR_SERVICE)
            val characteristic = service?.getCharacteristic(HR_MEASUREMENT)
            if (characteristic == null) {
                _lastError.value = "设备未暴露标准心率服务（0x180D/0x2A37）"
                onLog("device lacks HRS")
                closeGattQuietly(g)
                _connectionState.value = HrConnectionState.IDLE
                return
            }
            try {
                g.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(descriptor)
                    }
                } else {
                    onLog("CCCD 缺失，依赖 setCharacteristicNotification")
                }
            } catch (e: SecurityException) {
                _lastError.value = "订阅心率通知失败：缺少蓝牙权限"
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotification(characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = HrConnectionState.CONNECTED
                reconnectAttempt = 0
                _lastError.value = null
            } else {
                onLog("CCCD write status=$status")
            }
        }
    }

    /** binder 线程回调：只做解析与发射，不触碰 GATT。 */
    private fun handleNotification(raw: ByteArray?) {
        if (raw == null) return
        val parsed = HeartRateMeasurementParser.parse(raw) ?: run {
            onLog("无法解析心率帧：${raw.joinToString(",") { (it.toInt() and 0xFF).toString() }}")
            return
        }
        val sample = HeartRateSample(
            bpm = parsed.bpm,
            receivedAtEpochMs = System.currentTimeMillis(),
            receivedAtMonotonicMs = SystemClock.elapsedRealtime(),
            contactDetected = parsed.contactDetected,
            isSimulated = false
        )
        _samples.tryEmit(sample)
    }

    private fun scheduleReconnect() {
        val address = targetAddress ?: return
        if (userRequestedShutdown.get()) return
        if (reconnectAttempt >= RECONNECT_DELAYS_MS.size) {
            _connectionState.value = HrConnectionState.STOPPED
            _lastError.value = "多次重连失败，已停止（可手动重试）"
            return
        }
        val delay = RECONNECT_DELAYS_MS[reconnectAttempt++]
        _connectionState.value = HrConnectionState.RECONNECTING
        onLog("将在 ${delay}ms 后重连（第 $reconnectAttempt 次）")
        mainHandler.postDelayed({ connectInternal(address) }, delay)
    }

    private fun closeGattQuietly(g: BluetoothGatt) {
        try {
            g.close()
        } catch (_: SecurityException) {
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
