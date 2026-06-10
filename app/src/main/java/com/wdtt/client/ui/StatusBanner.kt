package com.wdtt.client.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StatusBanner(status: String, daysLeft: Int) {
    when (status) {
        "active" -> {
            when {
                daysLeft in 1..5 -> BannerRow(
                    icon       = Icons.Filled.Schedule,
                    iconTint   = Color(0xFFFFB300),
                    background = Color(0xFFFFB300).copy(alpha = 0.10f),
                    border     = Color(0xFFFFB300).copy(alpha = 0.4f),
                    text       = "Осталось $daysLeft ${dayWord(daysLeft)} · Продлите подписку"
                )
                daysLeft > 5 -> BannerRow(
                    icon       = Icons.Filled.CheckCircle,
                    iconTint   = Color(0xFF4CAF50),
                    background = Color(0xFF4CAF50).copy(alpha = 0.10f),
                    border     = Color(0xFF4CAF50).copy(alpha = 0.35f),
                    text       = "Подписка активна · $daysLeft ${dayWord(daysLeft)}"
                )
                // daysLeft == 0 — истекает сегодня
                else -> BannerRow(
                    icon       = Icons.Filled.Schedule,
                    iconTint   = Color(0xFFFFB300),
                    background = Color(0xFFFFB300).copy(alpha = 0.10f),
                    border     = Color(0xFFFFB300).copy(alpha = 0.4f),
                    text       = "Подписка истекает сегодня · Продлите"
                )
            }
        }
        "expired" -> BannerRow(
            icon       = Icons.Filled.Block,
            iconTint   = MaterialTheme.colorScheme.error,
            background = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
            border     = MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
            text       = "Подписка истекла · Продлите в боте"
        )
        "unknown" -> BannerRow(
            icon       = Icons.Filled.CloudOff,
            iconTint   = MaterialTheme.colorScheme.onSurfaceVariant,
            background = MaterialTheme.colorScheme.surfaceVariant,
            border     = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            text       = "Нет связи с сервером · Используется кэш"
        )
        else -> {}
    }
}

@Composable
private fun BannerRow(icon: ImageVector, iconTint: Color, background: Color, border: Color, text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, border, RoundedCornerShape(10.dp)),
        color = background,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = iconTint
            )
        }
    }
}

private fun dayWord(days: Int): String = when {
    days % 10 == 1 && days % 100 != 11 -> "день"
    days % 10 in 2..4 && days % 100 !in 12..14 -> "дня"
    else -> "дней"
}
