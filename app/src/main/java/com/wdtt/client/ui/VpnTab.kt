package com.wdtt.client.ui

import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.SettingsStore
import com.wdtt.client.SubscriptionChecker
import com.wdtt.client.VpnServerManager
import com.wdtt.client.XrayManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }

    val currentDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(currentDensity.density, fontScale = 1f)
    ) {
        VpnTabContent(context, scope, settingsStore)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VpnTabContent(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    settingsStore: SettingsStore
) {
    val vpnRunning by XrayManager.running.collectAsStateWithLifecycle()
    val vpnConnecting by XrayManager.connecting.collectAsStateWithLifecycle()
    val vpnError by XrayManager.lastError.collectAsStateWithLifecycle()
    val subStatus by SubscriptionChecker.status.collectAsStateWithLifecycle()
    val subDaysLeft by SubscriptionChecker.daysLeft.collectAsStateWithLifecycle()
    val savedUuid by settingsStore.vpnUuid.collectAsStateWithLifecycle(initialValue = "")
    val vpnServers by VpnServerManager.servers.collectAsStateWithLifecycle()

    var isRefreshing by remember { mutableStateOf(false) }
    var selectedServer by rememberSaveable { mutableIntStateOf(0) }
    var initialized by remember { mutableStateOf(false) }
    var showSubDialog by rememberSaveable { mutableStateOf(false) }
    var showDeviceLimit by rememberSaveable { mutableStateOf(false) }
    var pendingStartAfterVpnPermission by remember { mutableStateOf(false) }

    LaunchedEffect(vpnError) {
        if (vpnError.isNotBlank()) {
            Toast.makeText(context, vpnError, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        val savedIndex = settingsStore.vpnServerIndex.first()
        if (savedUuid.isNotBlank() && VpnServerManager.servers.value.isEmpty()) {
            VpnServerManager.fetchServers(context)
        }
        val list = VpnServerManager.servers.value
        selectedServer = when {
            list.isEmpty() -> savedIndex
            savedIndex in list.indices -> savedIndex
            else -> VpnServerManager.defaultServerIndex(list)
        }
        if (list.isNotEmpty()) {
            XrayManager.selectedIndex.value = selectedServer
            settingsStore.saveVpnServerIndex(selectedServer)
        }
        initialized = true
    }

    LaunchedEffect(vpnServers.size) {
        if (vpnServers.isEmpty()) return@LaunchedEffect
        val savedIndex = settingsStore.vpnServerIndex.first()
        val newIndex = when {
            savedIndex in vpnServers.indices -> savedIndex
            selectedServer in vpnServers.indices -> selectedServer
            else -> VpnServerManager.defaultServerIndex(vpnServers)
        }
        if (newIndex != selectedServer) {
            selectedServer = newIndex
            XrayManager.selectedIndex.value = newIndex
            settingsStore.saveVpnServerIndex(newIndex)
        }
    }

    LaunchedEffect(initialized, savedUuid) {
        if (!initialized || savedUuid.isBlank() || vpnServers.isNotEmpty() || isRefreshing) return@LaunchedEffect
        isRefreshing = true
        XrayManager.fetchServers(context)
        isRefreshing = false
    }

    fun toastForFetch(result: VpnServerManager.FetchResult) {
        val msg = when (result) {
            VpnServerManager.FetchResult.Success -> "Список серверов обновлён"
            VpnServerManager.FetchResult.NetworkError ->
                "Не удалось загрузить список серверов"
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun startVpnConnection() {
        XrayManager.startVpnAsync(context)
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingStartAfterVpnPermission) {
            pendingStartAfterVpnPermission = false
            if (VpnService.prepare(context) == null) startVpnConnection()
            else Toast.makeText(context, "VPN-разрешение не выдано", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestVpnAndStart() {
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            pendingStartAfterVpnPermission = true
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnConnection()
        }
    }

    if (showSubDialog) {
        BypassSubscriptionDialog(
            initialUrl = settingsStore.vpnSubscriptionUrl.collectAsState(initial = "").value,
            onSuccess = {
                showSubDialog = false
                scope.launch {
                    isRefreshing = true
                    val result = XrayManager.fetchServers(context)
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
            text = {
                Text(
                    "Достигнут лимит устройств для вашей подписки.\n\n" +
                    "Удалите одно из существующих устройств через Telegram бот ByPassMe, затем попробуйте снова."
                )
            },
            confirmButton = {
                TextButton(onClick = { showDeviceLimit = false }) { Text("Понятно") }
            }
        )
    }

    if (!initialized) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val vpnActive = vpnRunning || vpnConnecting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "VPN",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Row {
                IconButton(
                    onClick = {
                        scope.launch {
                            isRefreshing = true
                            val result = XrayManager.fetchServers(context)
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

        StatusBanner(status = subStatus, daysLeft = subDaysLeft)

        ConnectionStatusOrb(running = vpnRunning || vpnConnecting, ready = vpnRunning)

        AppSectionCard(contentPadding = PaddingValues(0.dp)) {
            Column {
                Text(
                    "Сервер",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp)
                )
                if (vpnServers.isEmpty()) {
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
                        serverNames = vpnServers.map { it.name },
                        selectedIndex = selectedServer,
                        disabled = vpnConnecting,
                        visibleRows = SERVER_LIST_VPN_VISIBLE_ROWS,
                        onSelect = { index ->
                            if (index == selectedServer) return@ConnectionServerList
                            selectedServer = index
                            XrayManager.selectedIndex.value = index
                            scope.launch {
                                settingsStore.saveVpnServerIndex(index)
                                if (vpnRunning || vpnConnecting) {
                                    XrayManager.switchServerAsync(context, index)
                                }
                            }
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        val buttonColor by animateColorAsState(
            targetValue = if (vpnActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            animationSpec = tween(400),
            label = "vpn_btn_color"
        )
        val btnDotsAnim = rememberInfiniteTransition(label = "vpn_btn_dots")
        val btnDotFrame by btnDotsAnim.animateFloat(
            initialValue = 0f,
            targetValue = 4f,
            animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Restart),
            label = "vpn_btn_dot_frame"
        )
        val btnDots = when (btnDotFrame.toInt()) { 0 -> ""; 1 -> "."; 2 -> ".."; else -> "..." }

        Button(
            onClick = {
                if (vpnActive) XrayManager.stopVpnAsync(context)
                else requestVpnAndStart()
            },
            enabled = (savedUuid.isNotBlank() && vpnServers.isNotEmpty()) || vpnActive,
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
                targetState = Pair(vpnActive, vpnRunning),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "vpn_btn_content"
            ) { (active, running) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (running) Icons.Default.Stop else Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = when {
                            running -> "Остановить"
                            active && !running -> "Подключение$btnDots"
                            else -> "Подключить"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}
