package com.wdtt.client.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wdtt.client.AppLogger

/**
 * Вкладка «Настройки» (вместо прежней «Логи»): работа в фоне, оформление,
 * логи (отдельным экраном — копировать/отправить), версия.
 */
@Composable
fun AppSettingsTab(
    themeMode: String,
    onThemeChange: (String) -> Unit,
    isDynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    currentPalette: String,
    onPaletteChange: (String) -> Unit,
) {
    var showLogs by rememberSaveable { mutableStateOf(false) }
    if (showLogs) {
        BackHandler { showLogs = false }
        AppLogsTab(onBack = { showLogs = false })
        return
    }

    val context = LocalContext.current
    // Перепроверяем состояние фона, когда человек возвращается из настроек Android.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh++ }
    }
    val exempt = remember(refresh) { BackgroundHelper.ignoringBatteryOptimizations(context) }
    val restricted = remember(refresh) { BackgroundHelper.backgroundRestricted(context) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Настройки",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
        )

        // ── Работа в фоне ──────────────────────────────────────────
        AppSectionCard(contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            SectionTitle("Работа в фоне")
            Text(
                "Чтобы VPN и Обход не выключались, пока телефон лежит с выключенным экраном.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            SettingsRow(
                icon = Icons.Default.BatteryChargingFull,
                title = "Экономия батареи",
                subtitle = if (exempt) "Без ограничений" else "Ограничена — нажмите и выберите «Без ограничений»",
                ok = exempt,
                onClick = {
                    AppLogger.service("Настройки: экономия батареи")
                    BackgroundHelper.openBatteryExemption(context)
                }
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Default.RestartAlt,
                title = "Автозапуск",
                subtitle = "Разрешите автозапуск и работу в фоне (${Build.MANUFACTURER})",
                onClick = {
                    AppLogger.service("Настройки: автозапуск (${Build.MANUFACTURER})")
                    BackgroundHelper.openAutostart(context)
                }
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Default.PowerSettingsNew,
                title = "Постоянный VPN",
                subtitle = "Android сам включит VPN или Обход (что было включено последним) после перезагрузки или если приложение закроют",
                onClick = { BackgroundHelper.openAlwaysOnVpn(context) }
            )
            if (restricted) {
                RowDivider()
                SettingsRow(
                    icon = Icons.Default.Warning,
                    title = "Фоновая работа ограничена",
                    subtitle = "Снимите ограничение в сведениях о приложении",
                    ok = false,
                    onClick = { BackgroundHelper.openAppDetails(context) }
                )
            }
        }

        // ── Оформление ─────────────────────────────────────────────
        AppSectionCard(contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            SectionTitle("Оформление")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("system" to "Системная", "light" to "Светлая", "dark" to "Тёмная").forEach { (id, label) ->
                    FilterChip(selected = themeMode == id, onClick = { onThemeChange(id) }, label = { Text(label) })
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Цвета обоев", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = isDynamicColor, onCheckedChange = onDynamicColorChange)
                }
            }
            if (!isDynamicColor || Build.VERSION.SDK_INT < 31) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    PaletteCircle("indigo", 0xFF5B588D, currentPalette, onPaletteChange)
                    PaletteCircle("forest", 0xFF5F5D68, currentPalette, onPaletteChange)
                    PaletteCircle("espresso", 0xFF6D4C41, currentPalette, onPaletteChange)
                }
            }
        }

        // ── Логи ───────────────────────────────────────────────────
        AppSectionCard(contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            SettingsRow(
                icon = Icons.Default.Terminal,
                title = "Логи",
                subtitle = "Посмотреть, скопировать или отправить в поддержку",
                onClick = { showLogs = true }
            )
        }

        if (version.isNotEmpty()) {
            Text(
                "ByPassMe $version",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    ok: Boolean? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        when (ok) {
            true -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            false -> Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            null -> Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)
            )
        }
    }
}
