package com.wdtt.client.ui

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.BypassServerManager
import com.wdtt.client.ConnectionCoordinator
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import com.wdtt.client.TunnelService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val WORKERS_PER_GROUP = 9
private const val MAX_WORKERS = 27
private const val DEFAULT_WORKERS = 18

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }

    val currentDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(currentDensity.density, fontScale = 1f)
    ) {
        BypassTabContent(context, scope, settingsStore)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BypassTabContent(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    settingsStore: SettingsStore
) {
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val tunnelReady by TunnelManager.tunnelReady.collectAsStateWithLifecycle()
    val cooldownSeconds by TunnelManager.cooldownSeconds.collectAsStateWithLifecycle()
    val subStatus by com.wdtt.client.SubscriptionChecker.status.collectAsStateWithLifecycle()
    val subDaysLeft by com.wdtt.client.SubscriptionChecker.daysLeft.collectAsStateWithLifecycle()
    val savedUuid by settingsStore.vpnUuid.collectAsStateWithLifecycle(initialValue = "")
    val bypassServers by BypassServerManager.servers.collectAsStateWithLifecycle()

    var isRefreshing by remember { mutableStateOf(false) }
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(tunnelRunning) {
        if (wasRunning && !tunnelRunning) TunnelManager.startCooldown(5)
        wasRunning = tunnelRunning
    }

    var selectedServer by rememberSaveable { mutableIntStateOf(0) }
    var vkLinkInput by rememberSaveable { mutableStateOf("") }
    var workersInput by rememberSaveable { mutableFloatStateOf(DEFAULT_WORKERS.toFloat()) }
    var initialized by remember { mutableStateOf(false) }
    var showSubDialog by rememberSaveable { mutableStateOf(false) }
    var showDeviceLimit by rememberSaveable { mutableStateOf(false) }

    val vkHash = remember(vkLinkInput) { stripVkUrl(vkLinkInput.trim()) }
    val isVkLinkValid = vkHash.length >= 16
    val dynamicMaxWorkers = MAX_WORKERS.toFloat()

    LaunchedEffect(Unit) {
        val hashes = settingsStore.vkHashes.first()
        val workers = settingsStore.workersPerHash.first()
        val savedPeer = settingsStore.peer.first()
        val savedIndex = settingsStore.bypassServerIndex.first()
        if (savedUuid.isNotBlank() && bypassServers.isEmpty()) {
            BypassServerManager.fetchServers(context)
        }
        val firstHash = hashes.split(",").firstOrNull { it.isNotBlank() } ?: ""
        vkLinkInput = if (firstHash.isNotEmpty()) "https://vk.com/call/join/$firstHash" else ""
        workersInput = SettingsStore.snapWorkers(workers).toFloat()
        val list = BypassServerManager.servers.value
        selectedServer = when {
            list.isEmpty() -> savedIndex
            savedPeer.isNotBlank() -> {
                val byHost = list.indexOfFirst { it.host == savedPeer }
                if (byHost >= 0) byHost else BypassServerManager.defaultServerIndex(list)
            }
            savedIndex in list.indices -> savedIndex
            else -> BypassServerManager.defaultServerIndex(list)
        }
        if (list.isNotEmpty()) settingsStore.saveBypassServerIndex(selectedServer)
        initialized = true
    }

    LaunchedEffect(bypassServers.size) {
        if (bypassServers.isEmpty()) return@LaunchedEffect
        val savedPeer = settingsStore.peer.first()
        val matched = if (savedPeer.isNotBlank()) {
            bypassServers.indexOfFirst { it.host == savedPeer }
        } else -1
        val newIndex = when {
            matched >= 0 -> matched
            selectedServer in bypassServers.indices -> selectedServer
            else -> BypassServerManager.defaultServerIndex(bypassServers)
        }
        if (newIndex != selectedServer) {
            selectedServer = newIndex
            settingsStore.saveBypassServerIndex(newIndex)
        }
    }

    LaunchedEffect(initialized, savedUuid) {
        if (!initialized || savedUuid.isBlank() || bypassServers.isNotEmpty() || isRefreshing) return@LaunchedEffect
        isRefreshing = true
        BypassServerManager.fetchServers(context)
        isRefreshing = false
    }

    fun toastForFetch(result: BypassServerManager.FetchResult) {
        val msg = when (result) {
            BypassServerManager.FetchResult.Success -> "Список серверов обновлён"
            BypassServerManager.FetchResult.NoAccess ->
                "Нет доступа к обходу · проверьте подписку"
            BypassServerManager.FetchResult.NotFound ->
                "Подписка не найдена"
            BypassServerManager.FetchResult.NoSubscription ->
                "Сначала введите ссылку подписки"
            else -> "Не удалось загрузить список серверов"
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
    val currentWorkers = workersInput.coerceIn(WORKERS_PER_GROUP.toFloat(), dynamicMaxWorkers)
    val storedConnectionPassword by settingsStore.connectionPassword.collectAsStateWithLifecycle(initialValue = "")
    val connectionPassword = storedConnectionPassword.ifEmpty { "ByPassMe" }
    val isValid = isVkLinkValid

    var saveJob by remember { mutableStateOf<Job?>(null) }
    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            val peer = bypassServers.getOrNull(selectedServer)?.host ?: ""
            settingsStore.save(
                peer, vkHash, "",
                currentWorkers.toInt(), "udp", 9000, "", false
            )
        }
    }

    var pendingStartAfterVpnPermission by remember { mutableStateOf(false) }

    fun startTunnelService() {
        val server = bypassServers.getOrNull(selectedServer) ?: run {
            Toast.makeText(context, "Выберите сервер", Toast.LENGTH_SHORT).show()
            return
        }
        saveJob?.cancel()
        scope.launch {
            try {
                settingsStore.save(server.host, vkHash, "", currentWorkers.toInt(), "udp", 9000, "", false)
                settingsStore.saveConnectionPassword(connectionPassword)
                settingsStore.saveCaptchaMode("rjs")
                settingsStore.saveCaptchaSolveMethod("auto")

                ConnectionCoordinator.prepareForBypass(context)

                val intent = Intent(context, TunnelService::class.java).apply {
                    action = "START"
                    putExtra(ConnectionCoordinator.EXTRA_HANDOFF_DONE, true)
                    putExtra("peer", server.peer)
                putExtra("vk_hashes", vkHash)
                putExtra("secondary_vk_hash", "")
                putExtra("workers_per_hash", currentWorkers.toInt())
                putExtra("port", 9000)
                putExtra("sni", "")
                putExtra("connection_password", connectionPassword)
                putExtra("protocol", "udp")
                putExtra("captcha_mode", "rjs")
                putExtra("captcha_solve_method", "auto")
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    context,
                    e.message ?: "Ошибка запуска обхода",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingStartAfterVpnPermission) {
            pendingStartAfterVpnPermission = false
            if (VpnService.prepare(context) == null) startTunnelService()
            else Toast.makeText(context, "VPN-разрешение не выдано", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestVpnAndStart() {
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            pendingStartAfterVpnPermission = true
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startTunnelService()
        }
    }

    if (showSubDialog) {
        BypassSubscriptionDialog(
            initialUrl = settingsStore.vpnSubscriptionUrl.collectAsState(initial = "").value,
            onSuccess = {
                showSubDialog = false
                scope.launch {
                    isRefreshing = true
                    val result = BypassServerManager.fetchServers(context)
                    isRefreshing = false
                    toastForFetch(result)
                }
            },
            onDeviceLimitExceeded = { showSubDialog = false; showDeviceLimit = true },
            onDismiss = { showSubDialog = false }
        )
    }

    if (showDeviceLimit) {
        AlertDialog(
            onDismissRequest = { showDeviceLimit = false },
            title = { Text("Лимит устройств") },
            text = { Text("Достигнут лимит устройств для вашей подписки.\n\nУдалите одно из существующих устройств через Telegram бот ByPassMe, затем попробуйте снова.") },
            confirmButton = { TextButton(onClick = { showDeviceLimit = false }) { Text("Понятно") } }
        )
    }

    if (!initialized) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Заголовок ──────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Обход Б/С",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Row {
                IconButton(
                    onClick = {
                        scope.launch {
                            isRefreshing = true
                            val result = BypassServerManager.fetchServers(context)
                            isRefreshing = false
                            toastForFetch(result)
                        }
                    },
                    enabled = !isRefreshing
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Обновить серверы",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { showSubDialog = true }) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = "Подписка",
                        tint = if (savedUuid.isEmpty()) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ── Статус подписки ─────────────────────────────────────────
        StatusBanner(status = subStatus, daysLeft = subDaysLeft)

        // ── Статус орб ──────────────────────────────────────────────
        ConnectionStatusOrb(running = tunnelRunning, ready = tunnelReady)

        // ── Выбор сервера ───────────────────────────────────────────
        AppSectionCard(contentPadding = PaddingValues(0.dp)) {
            Column {
                Text(
                    "Сервер",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp)
                )
                if (bypassServers.isEmpty()) {
                    Text(
                        when {
                            isRefreshing -> "Загрузка серверов…"
                            savedUuid.isBlank() -> "Сначала введите ссылку подписки (🔑)"
                            else -> "Список пуст · нажмите ↻ для загрузки"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                } else {
                    ConnectionServerList(
                        serverNames = bypassServers.map { it.name },
                        selectedIndex = selectedServer,
                        disabled = tunnelRunning,
                        visibleRows = SERVER_LIST_BYPASS_VISIBLE_ROWS,
                        onSelect = { index ->
                            selectedServer = index
                            scope.launch { settingsStore.saveBypassServerIndex(index) }
                            scheduleSave()
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ── Ссылка VK ───────────────────────────────────────────────
        AppSectionCard(contentPadding = PaddingValues(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Ссылка VK звонка",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = vkLinkInput,
                    onValueChange = { vkLinkInput = it; scheduleSave() },
                    placeholder = { Text("https://vk.com/call/join/…") },
                    singleLine = true,
                    enabled = !tunnelRunning,
                    isError = vkLinkInput.isNotBlank() && !isVkLinkValid,
                    supportingText = if (vkLinkInput.isNotBlank() && !isVkLinkValid) {
                        { Text("Неверный формат ссылки", color = MaterialTheme.colorScheme.error) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                )
            }
        }

        // ── Мощность ────────────────────────────────────────────────
        AppSectionCard(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Мощность", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text("${currentWorkers.toInt()}", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(4.dp))
            CompactSteppedSlider(
                value = roundToGroup(currentWorkers.coerceIn(WORKERS_PER_GROUP.toFloat(), dynamicMaxWorkers), dynamicMaxWorkers),
                onValueChange = { workersInput = roundToGroup(it, dynamicMaxWorkers); scheduleSave() },
                valueRange = WORKERS_PER_GROUP.toFloat()..dynamicMaxWorkers,
                stepSize = WORKERS_PER_GROUP.toFloat(),
                enabled = !tunnelRunning,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── Кнопка ──────────────────────────────────────────────────
        val buttonColor by animateColorAsState(
            targetValue = if (tunnelRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            animationSpec = tween(400), label = "bypass_btn_color"
        )
        val btnDotsAnim = rememberInfiniteTransition(label = "btn_dots")
        val btnDotFrame by btnDotsAnim.animateFloat(
            initialValue = 0f, targetValue = 4f,
            animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Restart),
            label = "btn_dot_frame"
        )
        val btnDots = when (btnDotFrame.toInt()) { 0 -> ""; 1 -> "."; 2 -> ".."; else -> "..." }

        Button(
            onClick = {
                if (tunnelRunning) context.startService(Intent(context, TunnelService::class.java).apply { action = "STOP" })
                else requestVpnAndStart()
            },
            enabled = ((isValid && bypassServers.isNotEmpty() && cooldownSeconds == 0) || tunnelRunning),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonColor,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            AnimatedContent(
                targetState = Pair(tunnelRunning, tunnelReady),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "bypass_btn_content"
            ) { (running, ready) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(imageVector = if (running && ready) Icons.Default.Stop else Icons.Default.PowerSettingsNew,
                        contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = when {
                            running && ready -> "Остановить"
                            running && !ready -> "Подключение$btnDots"
                            cooldownSeconds > 0 -> "Подождите ($cooldownSeconds)"
                            else -> "Подключить"
                        },
                        fontWeight = FontWeight.Bold, fontSize = 16.sp
                    )
                }
            }
        }
    }
}

// ═══ Диалог подписки (используется в SettingsTab и VpnTab) ═══
@Composable
internal fun BypassSubscriptionDialog(
    initialUrl: String,
    onSuccess: () -> Unit,
    onDeviceLimitExceeded: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputText by rememberSaveable { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }

    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Подключение", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    if (!isLoading) IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                }
                Text("Вставьте ссылку подписки из вашей панели ByPassMe.\nСохраняется один раз — больше не потребуется.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it; errorText = "" },
                    placeholder = { Text("https://sub.bypassme.online/...") },
                    singleLine = false, minLines = 2, maxLines = 4,
                    enabled = !isLoading,
                    isError = errorText.isNotEmpty(),
                    supportingText = if (errorText.isNotEmpty()) { { Text(errorText, color = MaterialTheme.colorScheme.error) } } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                )
                Button(
                    onClick = {
                        isLoading = true; errorText = ""
                        scope.launch {
                            when (val result = com.wdtt.client.SubscriptionChecker.fetch(context, inputText.trim(), reconnect = true)) {
                                is com.wdtt.client.SubscriptionChecker.Result.Success -> onSuccess()
                                is com.wdtt.client.SubscriptionChecker.Result.DeviceLimitExceeded -> { isLoading = false; onDeviceLimitExceeded() }
                                is com.wdtt.client.SubscriptionChecker.Result.DeviceBlocked -> { isLoading = false; errorText = "Устройство заблокировано. Обратитесь в поддержку." }
                                is com.wdtt.client.SubscriptionChecker.Result.DeviceRemoved -> { isLoading = false; errorText = "Не удалось переподключить. Проверьте лимит устройств в боте." }
                                is com.wdtt.client.SubscriptionChecker.Result.Revoked -> { isLoading = false; errorText = "Подписка не найдена или истекла." }
                                is com.wdtt.client.SubscriptionChecker.Result.Error -> { isLoading = false; errorText = result.msg }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = inputText.isNotBlank() && !isLoading,
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Получение данных...")
                    } else {
                        Text("Применить", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ═══ Слайдер ═══
@Composable
private fun CompactSteppedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    stepSize: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f)
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.55f)
    val thumbStrokeColor = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { 9.dp.toPx() }
    val trackWidthPx = with(density) { 5.dp.toPx() }

    fun snap(raw: Float): Float {
        val min = valueRange.start; val max = valueRange.endInclusive
        return ((((raw - min) / stepSize).roundToInt() * stepSize) + min).coerceIn(min, max)
    }
    fun positionToValue(x: Float, width: Float): Float {
        val left = thumbRadiusPx
        val right = (width - thumbRadiusPx).coerceAtLeast(left + 1f)
        val fraction = ((x.coerceIn(left, right) - left) / (right - left)).coerceIn(0f, 1f)
        return snap(valueRange.start + fraction * (valueRange.endInclusive - valueRange.start))
    }

    Canvas(
        modifier = modifier
            .height(34.dp)
            .pointerInput(enabled, valueRange, stepSize) {
                if (!enabled) return@pointerInput
                detectTapGestures { o -> onValueChange(positionToValue(o.x, size.width.toFloat())) }
            }
            .pointerInput(enabled, valueRange, stepSize) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, _ ->
                    onValueChange(positionToValue(change.position.x, size.width.toFloat()))
                }
            }
    ) {
        val centerY = size.height / 2f
        val left = thumbRadiusPx; val right = size.width - thumbRadiusPx
        val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f)
        val fraction = ((value - valueRange.start) / range).coerceIn(0f, 1f)
        val thumbX = left + (right - left) * fraction

        drawLine(inactiveColor, Offset(left, centerY), Offset(right, centerY), trackWidthPx, StrokeCap.Round)
        drawLine(activeColor, Offset(left, centerY), Offset(thumbX, centerY), trackWidthPx, StrokeCap.Round)

        val tickCount = (((valueRange.endInclusive - valueRange.start) / stepSize).roundToInt()).coerceAtLeast(1)
        repeat(tickCount + 1) { index ->
            val tickFraction = index / tickCount.toFloat()
            val tickX = left + (right - left) * tickFraction
            drawCircle(if (tickX <= thumbX) activeColor else inactiveColor, 2.dp.toPx(), Offset(tickX, centerY))
        }
        drawCircle(activeColor, thumbRadiusPx, Offset(thumbX, centerY))
        drawCircle(thumbStrokeColor, thumbRadiusPx, Offset(thumbX, centerY),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}

// ═══ Диалог хешей ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HashesDialog(
    hash1: String, hash2: String, hash3: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var h1 by remember { mutableStateOf(hash1) }
    var h2 by remember { mutableStateOf(hash2) }
    var h3 by remember { mutableStateOf(hash3) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("VK Ссылки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null)
                    }
                }

                Text(
                    "Вставьте ссылку на звонок или хеш после /join/. Больше ссылок — больше мощности.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                listOf(
                    Triple("VK Ссылка 1 *", h1) { v: String -> h1 = v },
                    Triple("VK Ссылка 2", h2) { v: String -> h2 = v },
                    Triple("VK Ссылка 3", h3) { v: String -> h3 = v }
                ).forEachIndexed { idx, (label, value, onChange) ->
                    val isShort = value.isNotBlank() && value.length < 16
                    OutlinedTextField(
                        value = value,
                        onValueChange = { raw ->
                            onChange(stripVkUrl(raw.filter { it != ' ' && it != '\n' }))
                        },
                        label = { Text(label) },
                        placeholder = { Text("https://vk.com/call/join/...") },
                        singleLine = true,
                        isError = isShort,
                        supportingText = if (isShort) {
                            { Text("Слишком короткий (мин. 16 символов)", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )
                }

                Button(
                    onClick = { onSave(h1, h2, h3); onDismiss() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = h1.isNotBlank() && h1.length >= 16,
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    Text("Сохранить", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ═══ Диалог пароля (упрощённый) ═══
@Composable
fun BypassSecretsDialog(
    settingsStore: SettingsStore,
    initialPassword: String,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var passwordInput by rememberSaveable { mutableStateOf(initialPassword) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Пароль подключения", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null)
                    }
                }

                Text(
                    "Введите пароль, который задан на вашем сервере ByPassMe",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Пароль") },
                    placeholder = { Text("Пароль сервера") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                )

                Button(
                    onClick = {
                        scope.launch {
                            settingsStore.saveConnectionPassword(passwordInput)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = passwordInput.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    Text("Сохранить", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ═══ Утилиты ═══
private fun roundToGroup(value: Float, maxW: Float = MAX_WORKERS.toFloat()): Float =
    SettingsStore.snapWorkers(value.roundToInt()).toFloat().coerceIn(WORKERS_PER_GROUP.toFloat(), maxW)

private fun stripVkUrl(input: String): String {
    var s = input.trim()
    val prefixes = listOf(
        "https://vk.com/call/join/", "http://vk.com/call/join/",
        "https://vk.ru/call/join/",  "http://vk.ru/call/join/",
        "vk.com/call/join/", "vk.ru/call/join/"
    )
    for (prefix in prefixes) { if (s.startsWith(prefix)) { s = s.removePrefix(prefix); break } }
    val qIdx = s.indexOf('?'); if (qIdx != -1) s = s.substring(0, qIdx)
    val hIdx = s.indexOf('#'); if (hIdx != -1) s = s.substring(0, hIdx)
    return s.trimEnd('/')
}
