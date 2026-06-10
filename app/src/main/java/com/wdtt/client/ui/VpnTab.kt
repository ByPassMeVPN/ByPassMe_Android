package com.wdtt.client.ui

import android.content.Intent
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.SubscriptionChecker
import com.wdtt.client.XrayManager
import com.wdtt.client.XrayVpnService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnTab() {
    val context = LocalContext.current
    val currentDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(currentDensity.density, fontScale = 1f)
    ) {
        VpnTabContent()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VpnTabContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val servers by XrayManager.servers.collectAsStateWithLifecycle()
    val selectedIndex by XrayManager.selectedIndex.collectAsStateWithLifecycle()
    val isRunning by XrayManager.running.collectAsStateWithLifecycle()
    val subStatus by SubscriptionChecker.status.collectAsStateWithLifecycle()
    val subDaysLeft by SubscriptionChecker.daysLeft.collectAsStateWithLifecycle()

    var isRefreshing by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    var showSubDialog by remember { mutableStateOf(false) }
    var showDeviceLimit by remember { mutableStateOf(false) }

    val savedUuid by com.wdtt.client.SettingsStore(context).vpnUuid.collectAsStateWithLifecycle(initialValue = "")
    val lastError by XrayManager.lastError.collectAsStateWithLifecycle()

    val vpnPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingStart) {
            pendingStart = false
            if (VpnService.prepare(context) == null) XrayManager.startVpn(context)
            else Toast.makeText(context, "VPN-разрешение не выдано", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestVpnAndStart() {
        val intent = VpnService.prepare(context)
        if (intent != null) {
            pendingStart = true
            vpnPermLauncher.launch(intent)
        } else {
            XrayManager.startVpn(context)
        }
    }

    if (showSubDialog) {
        BypassSubscriptionDialog(
            initialUrl = com.wdtt.client.SettingsStore(context).vpnSubscriptionUrl
                .collectAsState(initial = "").value,
            onSuccess = {
                showSubDialog = false
                scope.launch {
                    isRefreshing = true
                    XrayManager.fetchServers(context)
                    isRefreshing = false
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

    // Загрузить серверы при первом открытии
    LaunchedEffect(Unit) {
        if (servers.isEmpty() && subStatus == "active") {
            isRefreshing = true
            XrayManager.fetchServers(context)
            isRefreshing = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Заголовок ────────────────────────────────────────────────
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
                            XrayManager.fetchServers(context)
                            isRefreshing = false
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

        // ── Статус подписки ──────────────────────────────────────────
        StatusBanner(status = subStatus, daysLeft = subDaysLeft)

        // ── Статус orb ───────────────────────────────────────────────
        VpnStatusOrb(running = isRunning)

        // ── Выбор сервера ────────────────────────────────────────────
        AppSectionCard(contentPadding = PaddingValues(0.dp)) {
            Column {
                Text(
                    "Сервер",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp)
                )
                if (servers.isEmpty()) {
                    Text(
                        if (isRefreshing) "Загрузка серверов…"
                        else if (subStatus != "active") "Требуется активная подписка"
                        else "Нажмите ↻ для загрузки серверов",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                } else {
                    servers.forEachIndexed { index, server ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
                        VpnServerRow(
                            name = server.name,
                            selected = selectedIndex == index,
                            disabled = isRunning,
                            onTap = { XrayManager.selectedIndex.value = index }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ── Кнопка ───────────────────────────────────────────────────
        val buttonColor by animateColorAsState(
            targetValue = if (isRunning) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
            animationSpec = tween(400), label = "vpn_btn_color"
        )

        Button(
            onClick = {
                if (isRunning) XrayVpnService.stop(context)
                else requestVpnAndStart()
            },
            enabled = subStatus == "active" && (isRunning || servers.isNotEmpty()),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonColor,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            AnimatedContent(
                targetState = isRunning,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "vpn_btn_content"
            ) { running ->
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
                        text = if (running) "Отключить VPN" else "Подключить VPN",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }

        // Ошибка из xray процесса — показывает точную причину если VPN не запустился
        if (lastError.isNotEmpty()) {
            AppSectionCard(contentPadding = PaddingValues(12.dp)) {
                Text(
                    "⚠️ $lastError",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ═══ Строка сервера (такой же стиль как BypassServerRow) ═══
@Composable
private fun VpnServerRow(name: String, selected: Boolean, disabled: Boolean, onTap: () -> Unit) {
    val flag = name.split(" ").firstOrNull() ?: ""
    val country = name.removePrefix(flag).trim()
    Surface(
        onClick = { if (!disabled) onTap() },
        enabled = !disabled,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(flag, fontSize = 26.sp)
            Text(
                country,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ═══ Orb статуса VPN (такой же стиль как BypassStatusOrb) ═══
@Composable
private fun VpnStatusOrb(running: Boolean) {
    val colors = MaterialTheme.colorScheme

    val pulseAnim = rememberInfiniteTransition(label = "vpn_pulse")
    val pulse by pulseAnim.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "vpn_pulse_scale"
    )

    val orbColor by animateColorAsState(
        targetValue = if (running) colors.primary else colors.surfaceVariant,
        animationSpec = tween(600), label = "vpn_orb_color"
    )
    val textColor by animateColorAsState(
        targetValue = if (running) colors.onPrimary else colors.onSurfaceVariant,
        animationSpec = tween(600), label = "vpn_text_color"
    )

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .scale(if (running) pulse else 1f)
                .clip(CircleShape)
                .background(orbColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .clip(CircleShape)
                    .background(orbColor.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(orbColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (running) "ON" else "OFF",
                        color = textColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp
                    )
                }
            }
        }
    }
}
