package com.wdtt.client.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val SERVER_ROW_HEIGHT = 48
internal const val SERVER_LIST_PEEK = 18
/** Обход Б/С: видны 2 сервера, 3-й и далее — прокрутка. */
internal const val SERVER_LIST_BYPASS_VISIBLE_ROWS = 2
/** VPN: видны 3 сервера (NL/DE/US), с 4-го — прокрутка. */
internal const val SERVER_LIST_VPN_VISIBLE_ROWS = 3

@Composable
internal fun ConnectionServerList(
    serverNames: List<String>,
    selectedIndex: Int,
    disabled: Boolean,
    onSelect: (Int) -> Unit,
    visibleRows: Int = SERVER_LIST_VPN_VISIBLE_ROWS,
) {
    val scrollState = rememberScrollState()
    val canScrollDown by remember { derivedStateOf { scrollState.canScrollForward } }
    val canScrollUp by remember { derivedStateOf { scrollState.canScrollBackward } }
    val rowHeight = SERVER_ROW_HEIGHT.dp
    val peek = SERVER_LIST_PEEK.dp
    val scrollable = serverNames.size > visibleRows
    val listHeight = when {
        serverNames.isEmpty() -> rowHeight
        !scrollable -> rowHeight * serverNames.size
        visibleRows <= SERVER_LIST_BYPASS_VISIBLE_ROWS -> rowHeight * visibleRows + peek
        else -> rowHeight * visibleRows
    }
    val cardColor = MaterialTheme.colorScheme.surface

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(listHeight)
            .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            serverNames.forEachIndexed { index, name ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
                ConnectionServerRow(
                    name = name,
                    selected = selectedIndex == index,
                    disabled = disabled,
                    onTap = { onSelect(index) }
                )
            }
        }

        if (scrollable && canScrollUp) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(20.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to cardColor,
                            1f to Color.Transparent
                        )
                    )
            )
        }

        if (scrollable && canScrollDown) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to cardColor.copy(alpha = 0.85f),
                            1f to cardColor
                        )
                    )
            )
            Text(
                "↓ листайте",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
        }
    }
}

@Composable
internal fun ConnectionServerRow(
    name: String,
    selected: Boolean,
    disabled: Boolean,
    onTap: () -> Unit
) {
    val flag = name.split(" ").firstOrNull() ?: ""
    val country = name.removePrefix(flag).trim()
    Surface(
        onClick = { if (!disabled) onTap() },
        enabled = !disabled,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .height(SERVER_ROW_HEIGHT.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
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

@Composable
internal fun ConnectionStatusOrb(running: Boolean, ready: Boolean) {
    val colors = MaterialTheme.colorScheme
    val isConnected = running && ready

    val pulseAnim = rememberInfiniteTransition(label = "connection_pulse")
    val pulse by pulseAnim.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "connection_pulse_scale"
    )

    val orbColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isConnected) colors.primary else colors.surfaceVariant,
        animationSpec = tween(600),
        label = "connection_orb_color"
    )
    val textColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isConnected) colors.onPrimary else colors.onSurfaceVariant,
        animationSpec = tween(600),
        label = "connection_text_color"
    )

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .scale(if (isConnected) pulse else 1f)
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
                        text = if (isConnected) "ON" else "OFF",
                        color = textColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp
                    )
                }
            }
        }
    }
}
