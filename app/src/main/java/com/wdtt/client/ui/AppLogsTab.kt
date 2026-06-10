package com.wdtt.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.AppLogEntry
import com.wdtt.client.AppLogger
import com.wdtt.client.LogLevel
import com.wdtt.client.LogSource

@Composable
fun AppLogsTab() {
    val context = LocalContext.current
    val entries by AppLogger.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Фильтр по источнику
    var filter by remember { mutableStateOf<LogSource?>(null) }

    val filtered = remember(entries, filter) {
        if (filter == null) entries else entries.filter { it.source == filter }
    }

    // Автоскролл вниз при новых логах
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Заголовок + кнопки
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Логи",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row {
                IconButton(onClick = {
                    val text = filtered.joinToString("\n") {
                        "[${it.timestamp}][${it.source.label}] ${it.message}"
                    }
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Logs", text))
                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, null, tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { AppLogger.clear() }) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Фильтры
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        ) {
            FilterChip(
                selected = filter == null,
                onClick = { filter = null },
                label = { Text("Все", fontSize = 12.sp) }
            )
            FilterChip(
                selected = filter == LogSource.VPN,
                onClick = { filter = if (filter == LogSource.VPN) null else LogSource.VPN },
                label = { Text("🛡 VPN", fontSize = 12.sp) }
            )
            FilterChip(
                selected = filter == LogSource.BYPASS,
                onClick = { filter = if (filter == LogSource.BYPASS) null else LogSource.BYPASS },
                label = { Text("🔒 Обход", fontSize = 12.sp) }
            )
            FilterChip(
                selected = filter == LogSource.SERVICE,
                onClick = { filter = if (filter == LogSource.SERVICE) null else LogSource.SERVICE },
                label = { Text("⚙ Сервис", fontSize = 12.sp) }
            )
        }

        // Терминал
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1117)),
            elevation = CardDefaults.cardElevation(6.dp)
        ) {
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Логи появятся после запуска VPN или Обхода Б/С",
                        color = Color(0xFF8B949E),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: AppLogEntry) {
    val (sourceBg, sourceText) = when (entry.source) {
        LogSource.VPN     -> Color(0xFF1F6FEB) to Color(0xFFCBE0FF)
        LogSource.BYPASS  -> Color(0xFF238636) to Color(0xFFAFF5B4)
        LogSource.SERVICE -> Color(0xFF6E40C9) to Color(0xFFD2A8FF)
        LogSource.SYSTEM  -> Color(0xFF5B5B5B) to Color(0xFFCCCCCC)
    }

    val msgColor = when (entry.level) {
        LogLevel.ERROR -> Color(0xFFFF7B72)
        LogLevel.WARN  -> Color(0xFFE3B341)
        LogLevel.INFO  -> Color(0xFFE6EDF3)
        LogLevel.DEBUG -> Color(0xFF8B949E)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Время
        Text(
            text = entry.timestamp,
            color = Color(0xFF8B949E),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(80.dp).padding(top = 1.dp)
        )

        // Источник
        Box(
            modifier = Modifier
                .background(sourceBg.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(
                text = entry.source.label,
                color = sourceText,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        // Сообщение
        Text(
            text = entry.message,
            color = msgColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 15.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
