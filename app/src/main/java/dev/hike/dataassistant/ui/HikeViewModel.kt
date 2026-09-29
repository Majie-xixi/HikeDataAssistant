package dev.hike.dataassistant.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hike.dataassistant.data.HikeSnapshot
import dev.hike.dataassistant.data.PhonePosition
import dev.hike.dataassistant.data.PlannedClimb
import dev.hike.dataassistant.data.Reading
import dev.hike.dataassistant.data.ReadingStatus
import dev.hike.dataassistant.gpx.BoundedInputStream
import dev.hike.dataassistant.gpx.GpxParseException
import dev.hike.dataassistant.gpx.GpxParser
import dev.hike.dataassistant.gpx.GpxProcessor
import dev.hike.dataassistant.gpx.GpxTrackSegment
import dev.hike.dataassistant.heartrate.DiscoveredHrDevice
import dev.hike.dataassistant.heartrate.HeartRateTrend
import dev.hike.dataassistant.heartrate.HrConnectionState
import dev.hike.dataassistant.location.LocationClient
import dev.hike.dataassistant.route.RouteContextResolver
import dev.hike.dataassistant.route.RouteMatchStatus
import dev.hike.dataassistant.session.AndroidMonotonicClock
import dev.hike.dataassistant.session.HikeSessionController
import dev.hike.dataassistant.session.SessionState
import dev.hike.dataassistant.snapshot.ShareTextBuilder
import dev.hike.dataassistant.snapshot.SnapshotBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update

/** 主页面状态：全部由该状态渲染，字段含义见开发文档 §9。 */
data class UiState(
    val sessionState: SessionState = SessionState.IDLE,
    val activeSeconds: Long? = null,
    val elapsedSeconds: Long? = null,
    val hrConnection: HrConnectionState = HrConnectionState.IDLE,
    val hrError: String? = null,
    val hrBpm: Int? = null,
    val hrStatus: ReadingStatus = ReadingStatus.MISSING,
    val hrAgeSeconds: Long? = null,
    val hrSimulated: Boolean = false,
    val hrAvg5: Int? = null,
    val hrTrend: HeartRateTrend? = null,
    val discovered: List<DiscoveredHrDevice> = emptyList(),
    val routeName: String? = null,
    val routeTotalKm: Double? = null,
    val routeHasElevation: Boolean = false,
    val routeReversed: Boolean = false,
    val routeMatch: RouteMatchStatus? = null,
    val routeAtKm: Double? = null,
    val routeRemainingKm: Double? = null,
    val routeRemainingClimbM: Int? = null,
    val nextClimb: PlannedClimb? = null,
    val locationNote: String? = null,
    val locationPermissionNeeded: Boolean = false,
    val busyMessage: String? = null,
    val toastMessage: String? = null,
    val restoreNotice: String? = null,
    val pendingSegments: List<GpxTrackSegment> = emptyList(),
    /** 多段 GPX 选段时的名称回退（metadata 名/文件名），选中后仍有效。 */
    val pendingSegmentFallbackName: String? = null,
    val debugSimAvailable: Boolean = false
) {
    val scanning: Boolean get() = hrConnection == HrConnectionState.SCANNING
}

class HikeViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = HikeSessionController.getOrCreate(app)

    private val processor = GpxProcessor()
    private val resolver = RouteContextResolver()
    private val snapshotBuilder = SnapshotBuilder(processor, resolver)
    private val locationClient = LocationClient(app)

    /** 当前使用的路线模型与其分段原数据（方向切换时重建）。 */
    private var routeSegment: GpxTrackSegment? = null
    private var routeModel: GpxProcessor.RouteModel? = null

    private var latestLocation: Reading<PhonePosition>? = null

    private val _uiState = MutableStateFlow(UiState(debugSimAvailable = controller.simulatedProvider != null))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 分享预览（打开对话框时非空）。 */
    private val _sharePreview = MutableStateFlow<String?>(null)
    val sharePreview: StateFlow<String?> = _sharePreview.asStateFlow()

    init {
        restoreIfInterrupted()
        restoreRoute()
        observeHeartRate()
        observeTicker()
        viewModelScope.launch {
            controller.bleProvider.lastError.collect { err ->
                _uiState.update { it.copy(hrError = err, toastMessage = err) }
            }
        }
        viewModelScope.launch {
            controller.bleProvider.discovered.collect { list ->
                _uiState.update { it.copy(discovered = list) }
            }
        }
        if (locationClient.hasPermission()) locationClient.startPassive()
        viewModelScope.launch {
            locationClient.lastPassive.collect { reading ->
                if (reading != null) {
                    latestLocation = reading
                    refreshRouteContext()
                }
            }
        }
    }

    // ---------- 会话 ----------

    fun startSession() {
        controller.startSession()
        if (locationClient.hasPermission()) locationClient.startPassive()
        _uiState.update { it.copy(restoreNotice = null) }
        refreshUi()
    }

    fun pauseSession() {
        controller.pauseSession()
        refreshUi()
    }

    fun resumeSession() {
        controller.resumeSession()
        refreshUi()
    }

    fun stopSession() {
        controller.stopSession()
        locationClient.stopPassive()
        refreshUi()
    }

    // ---------- 手表连接 ----------

    fun startScan() {
        controller.startScan()
    }

    fun dismissScanDialog() {
        controller.stopScan()
        _uiState.update { it.copy(discovered = emptyList()) }
    }

    fun onBluetoothPermissionDenied() {
        _uiState.update { it.copy(toastMessage = "未授予蓝牙权限，无法扫描或连接手表") }
    }

    fun connectDevice(address: String) {
        controller.connectDevice(address)
        _uiState.update { it.copy(discovered = emptyList(), hrError = null, toastMessage = null) }
    }

    fun connectSimulated() {
        controller.connectSimulated()
        _uiState.update { it.copy(toastMessage = "已启用模拟心率源（仅 debug，分享文案会醒目标注）") }
    }

    fun clearToast() = _uiState.update { it.copy(toastMessage = null) }

    fun clearRestoreNotice() = _uiState.update { it.copy(restoreNotice = null) }

    // ---------- GPX ----------

    fun importGpx(uri: Uri) {
        _uiState.update { it.copy(busyMessage = "正在解析 GPX…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { parseGpxFile(uri) }
            _uiState.update { it.copy(busyMessage = null) }
            result.fold(
                onSuccess = { parsed ->
                    when {
                        parsed.segments.isEmpty() ->
                            _uiState.update {
                                it.copy(toastMessage = "GPX 中没有可用轨迹段（只支持 trk/trkseg 或 rte）")
                            }

                        parsed.segments.size == 1 ->
                            applySegment(parsed.segments[0], parsed.trackName ?: queryDisplayName(uri))

                        else -> {
                            _uiState.update {
                                it.copy(
                                    pendingSegments = parsed.segments,
                                    pendingSegmentFallbackName = parsed.trackName ?: queryDisplayName(uri),
                                    toastMessage = "该 GPX 含 ${parsed.segments.size} 个轨迹段，请选择一个连续段"
                                )
                            }
                        }
                    }
                },
                onFailure = { cause ->
                    // 带原因的失败提示：文件选择器返回异常大小时也会走到具体解析或上限错误
                    _uiState.update {
                        it.copy(toastMessage = "GPX 导入失败：${cause.message ?: cause.javaClass.simpleName}")
                    }
                }
            )
        }
    }

    fun pickSegment(index: Int) {
        val segment = _uiState.value.pendingSegments.getOrNull(index) ?: return
        val fallback = _uiState.value.pendingSegmentFallbackName
        _uiState.update { it.copy(pendingSegments = emptyList(), pendingSegmentFallbackName = null) }
        applySegment(segment, segment.name ?: fallback)
    }

    fun cancelSegmentPick() =
        _uiState.update { it.copy(pendingSegments = emptyList(), pendingSegmentFallbackName = null) }

    fun toggleRouteDirection() {
        val segment = routeSegment ?: return
        val newReversed = !_uiState.value.routeReversed
        routeModel = processor.build(segment, newReversed)
        controller.repository.saveRoute(segment, newReversed)
        refreshRouteContext()
        _uiState.update { it.copy(routeReversed = newReversed) }
    }

    // ---------- 分享 ----------

    /**
     * 整理并问 ChatGPT：主动取一次新定位（短暂等待），再生成可编辑文案。
     * 定位权限缺失/被拒时仍生成"仅心率与会话"的预览（文档 §7），
     * 同时发起一次权限申请，授权后用户可再次点击获取带位置的版本。
     */
    fun prepareShareText(question: String) {
        if (!locationClient.hasPermission()) {
            _uiState.update { it.copy(locationPermissionNeeded = true) }
            _sharePreview.value = buildShareText(question, location = null)
            return
        }
        _uiState.update { it.copy(busyMessage = "正在获取新位置（最多 12 秒）…") }
        viewModelScope.launch {
            latestLocation = locationClient.getCurrentPosition(timeoutMs = 12_000)
            _uiState.update { it.copy(busyMessage = null) }
            _sharePreview.value = buildShareText(question, location = latestLocation)
        }
    }

    /** 定位授权结果：拒绝不重试（预览已用无定位版打开）；授权后允许被动监听。 */
    fun retryShareAfterPermission() {
        _uiState.update { it.copy(locationPermissionNeeded = false) }
    }

    fun dismissSharePreview() {
        _sharePreview.value = null
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(locationPermissionNeeded = !granted) }
        if (granted) locationClient.startPassive()
    }

    // ---------- 内部 ----------

    private fun restoreIfInterrupted() {
        val restored = controller.repository.readSession() ?: return
        if (restored.interrupted) {
            restored.samples.forEach { s ->
                controller.engine.onSample(s.bpm, s.receivedAtEpochMs, s.contactDetected, s.isSimulated)
            }
            val minutes = restored.samples.lastOrNull()?.let { s ->
                (s.receivedAtEpochMs - (restored.samples.firstOrNull()?.receivedAtEpochMs ?: s.receivedAtEpochMs)) / 60_000
            } ?: 0
            _uiState.update {
                it.copy(
                    restoreNotice = "检测到未结束的上次会话：已保留 ${restored.samples.size} 个心率样本" +
                        "（约 $minutes 分钟）。计时不可续接；开始新会话会覆盖这些数据。"
                )
            }
        } else if (restored.samples.isEmpty()) {
            controller.repository.clearSessionLog()
        }
    }

    /** 进程重启后恢复上次导入的路线与方向（跨会话保留）。 */
    private fun restoreRoute() {
        val restored = controller.repository.readRoute() ?: return
        routeSegment = restored.segment
        routeModel = processor.build(restored.segment, restored.reversed)
        _uiState.update {
            it.copy(
                routeName = restored.segment.name,
                routeTotalKm = routeModel?.totalMeters?.div(1000),
                routeHasElevation = routeModel?.totalClimbMeters != null,
                routeReversed = restored.reversed
            )
        }
        refreshRouteContext()
    }

    private fun observeHeartRate() {
        viewModelScope.launch {
            // 心率读数按秒刷新（新鲜度年龄变化）
            flow {
                while (isActive) {
                    emit(Unit)
                    delay(1_000)
                }
            }.collect {
                refreshUi()
            }
        }
    }

    private fun observeTicker() {
        viewModelScope.launch {
            controller.bleProvider.connectionState.collect { state ->
                _uiState.update { it.copy(hrConnection = state) }
            }
        }
    }

    private fun refreshUi() {
        val nowWall = System.currentTimeMillis()
        val nowMono = AndroidMonotonicClock.nowMs()
        val engine = controller.engine
        val hr = engine.currentReading(nowWall)
        _uiState.update { state ->
            state.copy(
                sessionState = controller.session.state,
                activeSeconds = controller.session.activeDurationMs()?.let { it / 1000 },
                elapsedSeconds = controller.session.elapsedDurationMs()?.let { it / 1000 },
                hrBpm = hr.value,
                hrStatus = hr.status,
                hrAgeSeconds = hr.sampledAtEpochMs?.let { (nowWall - it) / 1000 },
                hrSimulated = hr.isSimulated,
                hrAvg5 = engine.avg5MinBpm(nowMono),
                hrTrend = engine.trend10Min(nowMono),
                locationNote = latestLocation?.let { describeLocation(it, nowWall) }
            )
        }
        refreshRouteContext()
    }

    private fun refreshRouteContext() {
        val model = routeModel
        val nowWall = System.currentTimeMillis()
        val location = latestLocation
        val match = resolver.resolve(model, location?.value, location?.sampledAtEpochMs?.let { nowWall - it })
        _uiState.update { state ->
            state.copy(
                routeMatch = match.status,
                routeAtKm = match.atMeters?.let { it / 1000 },
                routeRemainingKm = if (match.status == RouteMatchStatus.MATCHED && match.atMeters != null && model != null) {
                    processor.remainingMeters(model, match.atMeters) / 1000
                } else null,
                routeRemainingClimbM = if (match.status == RouteMatchStatus.MATCHED && match.atMeters != null && model != null) {
                    processor.remainingClimbMeters(model, match.atMeters)?.toInt()
                } else null,
                nextClimb = if (match.status == RouteMatchStatus.MATCHED && match.atMeters != null && model != null) {
                    processor.nextClimb(model, match.atMeters)
                } else null
            )
        }
    }

    private fun applySegment(segment: GpxTrackSegment, fallbackName: String?) {
        val model = try {
            processor.build(segment, reversed = false)
        } catch (e: IllegalArgumentException) {
            _uiState.update { it.copy(toastMessage = "该轨迹段无法使用：${e.message}") }
            return
        }
        // 有效名称 = trk/rte 名 > metadata 名 > 文件名；都没有才显示"未命名路线"
        val effectiveName = (segment.name ?: fallbackName?.trim()?.ifEmpty { null })
        // 用有效名称持久化，进程重启后仍能显示
        val namedSegment = if (effectiveName != null && segment.name == null) {
            segment.copy(name = effectiveName)
        } else {
            segment
        }
        routeSegment = namedSegment
        routeModel = model
        val saved = controller.repository.saveRoute(namedSegment, reversed = false)
        _uiState.update {
            it.copy(
                routeName = effectiveName,
                routeTotalKm = model.totalMeters / 1000,
                routeHasElevation = model.totalClimbMeters != null,
                routeReversed = false,
                restoreNotice = null,
                toastMessage = ("路线已导入：${effectiveName ?: "未命名"}，" +
                    "总长 ${String.format(java.util.Locale.US, "%.1f", model.totalMeters / 1000)} km" +
                    if (saved) "" else "（本地保存失败，App 重启后需重新导入）")
            )
        }
        refreshRouteContext()
    }

    /** 读取所选文件的显示名（去掉扩展名）——两步路部分导出路线名只在文件名里。 */
    private fun queryDisplayName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver
            .query(uri, null, null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
            ?.substringBeforeLast('.')
            ?.trim()
            ?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    private fun parseGpxFile(uri: Uri): Result<dev.hike.dataassistant.gpx.ParsedGpx> = runCatching {
        val resolver = getApplication<Application>().contentResolver
        // 部分文档提供器返回 UNKNOWN_LENGTH(-1)，不据此判失败；
        // 大小上限在读取时流式强制（超过 20MB 抛出）
        val descriptor = resolver.openAssetFileDescriptor(uri, "r")
            ?: throw GpxParseException("无法打开所选文件")
        descriptor.use { fd ->
            resolver.openInputStream(uri)?.use { raw ->
                val bounded = BoundedInputStream(raw, maxBytes = 20L * 1024 * 1024)
                GpxParser().parse(bounded)
            } ?: throw GpxParseException("无法读取所选文件")
        }
    }

    private fun buildShareText(question: String, location: Reading<PhonePosition>? = latestLocation): String {
        val snapshot = buildSnapshot(location)
        return ShareTextBuilder.build(snapshot, _uiState.value.routeName, question)
    }

    private fun buildSnapshot(location: Reading<PhonePosition>? = latestLocation): HikeSnapshot =
        snapshotBuilder.build(
            session = controller.session,
            engine = controller.engine,
            routeModel = routeModel,
            location = location,
            nowWallMs = System.currentTimeMillis(),
            nowMonoMs = AndroidMonotonicClock.nowMs()
        )

    private fun describeLocation(reading: Reading<PhonePosition>, nowWall: Long): String {
        val value = reading.value ?: return reading.note ?: "无定位"
        val age = reading.sampledAtEpochMs?.let { (nowWall - it) / 1000 } ?: -1
        return "位置 $age 秒前更新（精度 ${value.accuracyMeters.toInt()} 米）"
    }

    override fun onCleared() {
        // 会话存续时控制器由进程级作用域继续运行；VM 销毁只停止 UI 刷新
        super.onCleared()
    }
}
