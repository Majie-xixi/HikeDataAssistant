package dev.hike.dataassistant.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.hike.dataassistant.data.ReadingStatus
import dev.hike.dataassistant.heartrate.HeartRateTrend
import dev.hike.dataassistant.heartrate.HrConnectionState
import dev.hike.dataassistant.route.RouteMatchStatus
import dev.hike.dataassistant.session.SessionState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HikeApp(viewModel: HikeViewModel) {
    val state by viewModel.uiState.collectAsState()
    val sharePreview by viewModel.sharePreview.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // ---- 权限请求 ----
    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }
    var pendingScan by remember { mutableStateOf(false) }
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            if (pendingScan) viewModel.startScan()
        } else {
            viewModel.onBluetoothPermissionDenied()
        }
        pendingScan = false
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        viewModel.onLocationPermissionResult(granted.values.any { it })
        if (granted.values.any { it }) viewModel.retryShareAfterPermission()
    }
    LaunchedEffect(state.locationPermissionNeeded) {
        if (state.locationPermissionNeeded) {
            locationPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // ---- GPX 导入 ----
    val gpxImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importGpx) }

    // ---- 消息 ----
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    // ---- 会话开始前：如存在未结束的上次会话，先确认覆盖 ----
    var confirmOverwrite by remember { mutableStateOf(false) }
    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("开始新会话？") },
            text = { Text(state.restoreNotice ?: "上次会话的已保存数据将被覆盖。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmOverwrite = false
                    viewModel.startSession()
                }) { Text("覆盖并开始") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverwrite = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("徒步数据助手") },
                actions = {
                    TextButton(onClick = {
                        gpxImporter.launch(arrayOf("application/gpx+xml", "application/xml", "application/octet-stream", "text/xml"))
                    }) { Text("导入 GPX") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.restoreNotice?.let { RestoreNoticeCard(it) }

            RouteCard(state = state, onToggleDirection = viewModel::toggleRouteDirection)

            HeartRateCard(
                state = state,
                onScan = {
                    pendingScan = true
                    btPermissionLauncher.launch(bluetoothPermissions)
                },
                onSimulate = viewModel::connectSimulated
            )

            SessionCard(state = state)

            RoutePositionCard(state = state)

            Spacer(Modifier.height(8.dp))

            if (state.busyMessage != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(
                        state.busyMessage!!,
                        Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            SessionButtons(
                state = state,
                onStart = {
                    if (state.restoreNotice != null) confirmOverwrite = true else viewModel.startSession()
                },
                onPause = viewModel::pauseSession,
                onResume = viewModel::resumeSession,
                onStop = viewModel::stopSession
            )

            Button(
                onClick = { viewModel.prepareShareText(ShareQuestionEditor.DEFAULT) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.sessionState != SessionState.IDLE
            ) { Text("整理并问 ChatGPT") }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- 对话框 ----
    if (state.discovered.isNotEmpty() || state.scanning) {
        DeviceScanDialog(
            state = state,
            onDismiss = viewModel::dismissScanDialog,
            onPick = viewModel::connectDevice
        )
    }

    if (state.pendingSegments.isNotEmpty()) {
        SegmentPickDialog(
            segments = state.pendingSegments,
            onPick = viewModel::pickSegment,
            onDismiss = viewModel::cancelSegmentPick
        )
    }

    sharePreview?.let { preview ->
        SharePreviewDialog(
            initialText = preview,
            onDismiss = viewModel::dismissSharePreview,
            onShare = { text ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(intent, "分享给"))
            },
            onCopy = { text ->
                clipboard.setText(AnnotatedString(text))
            }
        )
    }
}

@Composable
private fun RestoreNoticeCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("上次会话中断", fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RouteCard(state: UiState, onToggleDirection: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (state.routeName == null) {
                Text("路线：未导入（右上角导入两步路 GPX）", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("路线：${state.routeName}", fontWeight = FontWeight.Bold)
                        Text(
                            "总长 ${fmt1(state.routeTotalKm)} km" +
                                (if (state.routeHasElevation) "" else " · 无海拔数据") +
                                (if (state.routeReversed) " · 已反向" else ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(onClick = onToggleDirection) { Text(if (state.routeReversed) "改正向" else "改反向") }
                }
            }
        }
    }
}

@Composable
private fun HeartRateCard(
    state: UiState,
    onScan: () -> Unit,
    onSimulate: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("GT5 心率", fontWeight = FontWeight.Bold)
                    Text(connectionLabel(state), style = MaterialTheme.typography.bodySmall)
                }
                val bpmText = when (state.hrStatus) {
                    ReadingStatus.FRESH, ReadingStatus.STALE -> "${state.hrBpm ?: "--"} bpm"
                    else -> "无实时数据"
                }
                Text(
                    bpmText + (if (state.hrSimulated) "（模拟）" else ""),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            state.hrAgeSeconds?.let { age ->
                Text(
                    when (state.hrStatus) {
                        ReadingStatus.FRESH -> "$age 秒前更新"
                        ReadingStatus.STALE -> "$age 秒前（已过期）"
                        else -> state.hrError ?: "等待数据"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.hrStatus == ReadingStatus.STALE) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } ?: Text(state.hrError ?: "等待数据", style = MaterialTheme.typography.bodySmall)
            Text(
                "5 分钟均值：${state.hrAvg5 ?: "数据积累中"}" +
                    trendText(state.hrTrend, state.hrAvg5),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onScan, enabled = state.sessionState != SessionState.STOPPED) {
                    Text(if (state.scanning) "扫描中…" else "连接手表")
                }
                if (state.debugSimAvailable) {
                    TextButton(onClick = onSimulate) { Text("模拟心率(debug)") }
                }
            }
        }
    }
}

private fun trendText(trend: HeartRateTrend?, avg5: Int?): String {
    val trendPart = when (trend) {
        is HeartRateTrend.Change -> "，10 分钟趋势：${if (trend.deltaBpm >= 0) "+" else ""}${trend.deltaBpm}"
        HeartRateTrend.Accumulating, HeartRateTrend.InsufficientCoverage ->
            if (avg5 != null) "，10 分钟趋势：数据积累中" else ""
        null -> ""
    }
    return trendPart
}

private fun connectionLabel(state: UiState): String = when (state.hrConnection) {
    HrConnectionState.IDLE -> "未连接"
    HrConnectionState.SCANNING -> "扫描心率设备（0x180D）…"
    HrConnectionState.CONNECTING -> "连接中…"
    HrConnectionState.CONNECTED -> "已连接，接收中"
    HrConnectionState.RECONNECTING -> "连接断开，自动重试中"
    HrConnectionState.STOPPED -> "已断开"
}

@Composable
private fun SessionCard(state: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("活动时间", fontWeight = FontWeight.Bold)
            Text(formatDuration(state.activeSeconds), style = MaterialTheme.typography.headlineSmall)
            val elapsed = state.elapsedSeconds
            if (elapsed != null && state.activeSeconds != null && elapsed - state.activeSeconds >= 60) {
                Text(
                    "累计经过 ${formatDuration(elapsed)}（含暂停）",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun RoutePositionCard(state: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("路线位置", fontWeight = FontWeight.Bold)
            when (state.routeMatch) {
                RouteMatchStatus.MATCHED -> {
                    Text(
                        "约 ${fmt1(state.routeAtKm)} / ${fmt1(state.routeTotalKm)} km",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    val climbText = state.routeRemainingClimbM
                        ?.let { " / 爬升 $it m" } ?: " / 爬升未知"
                    Text(
                        "计划剩余：${fmt1(state.routeRemainingKm)} km$climbText",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    state.nextClimb?.let {
                        Text(
                            "前方爬升：约 ${it.distanceToStartMeters.toInt()} m 后开始，约 ${it.climbMeters.toInt()} m",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    state.locationNote?.let {
                        HorizontalDivider()
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }

                RouteMatchStatus.AMBIGUOUS -> Text(
                    "路线位置待确认（折返或交叉路段，多处匹配）",
                    style = MaterialTheme.typography.bodyMedium
                )

                RouteMatchStatus.LOCATION_STALE -> Text(
                    "位置过旧（>30 秒），路线进度未计算",
                    style = MaterialTheme.typography.bodyMedium
                )

                RouteMatchStatus.LOCATION_LOW_ACCURACY -> Text(
                    "位置不确定（精度不足），路线进度未计算",
                    style = MaterialTheme.typography.bodyMedium
                )

                RouteMatchStatus.OUT_OF_CORRIDOR -> Text(
                    "距路线过远，路线进度未计算",
                    style = MaterialTheme.typography.bodyMedium
                )

                RouteMatchStatus.NO_LOCATION ->
                    Text(
                        if (state.routeName != null) "待定位（点击\"整理并问 ChatGPT\"会先更新位置）" else "未导入路线",
                        style = MaterialTheme.typography.bodyMedium
                    )

                RouteMatchStatus.NO_ROUTE, null -> Text(
                    "未导入路线",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SessionButtons(
    state: UiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.sessionState) {
            SessionState.IDLE, SessionState.STOPPED -> Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                Text(if (state.sessionState == SessionState.STOPPED) "开始新会话" else "开始记录")
            }

            SessionState.RECORDING -> {
                Button(onClick = onPause, modifier = Modifier.weight(1f)) { Text("暂停") }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("结束") }
            }

            SessionState.PAUSED -> {
                Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("继续") }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("结束") }
            }
        }
    }
}

@Composable
private fun DeviceScanDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择心率设备") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.discovered.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Text("  正在扫描暴露心率服务（0x180D）的设备…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.discovered.forEach { device ->
                    TextButton(onClick = { onPick(device.address) }) {
                        Column {
                            Text(device.name ?: "未知设备")
                            Text("${device.address} · ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                state.hrError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun SegmentPickDialog(
    segments: List<dev.hike.dataassistant.gpx.GpxTrackSegment>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择一个连续轨迹段") },
        text = {
            Column {
                Text("该 GPX 含多个轨迹段，为避免错误拼接请选择其一：", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                segments.forEachIndexed { index, segment ->
                    TextButton(onClick = { onPick(index) }) {
                        Text("段 ${index + 1}：${segment.points.size} 个点" + (segment.name?.let { " · $it" } ?: ""))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 分享预览：可编辑文案 + 问题，再分享或复制。 */
@Composable
private fun SharePreviewDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    val copied = remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("数据预览与提问") },
        text = {
            Column {
                Text("可删除任何不想发送的字段后分享：", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                if (copied.value) {
                    Text("已复制到剪贴板", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onShare(text) }) { Text("分享") }
        },
        dismissButton = {
            TextButton(onClick = {
                onCopy(text)
                copied.value = true
            }) { Text("复制") }
        }
    )
}

object ShareQuestionEditor {
    const val DEFAULT = ""
}

private fun formatDuration(totalSeconds: Long?): String {
    val s = totalSeconds ?: return "未开始"
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> if (m > 0) "$h 小时 $m 分" else "$h 小时"
        m > 0 -> "$m 分"
        else -> "${s % 60} 秒"
    }
}

private fun fmt1(value: Double?): String =
    value?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
