package com.wdtt.client.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal const val SERVER_ROW_HEIGHT = 48
/** Строка с подписью протокола (VPN) — чуть выше. */
internal const val SERVER_ROW_HEIGHT_WITH_SUBTITLE = 56
internal const val SERVER_LIST_PEEK = 18
/** Обход: видны 2 сервера, 3-й и далее — прокрутка. */
internal const val SERVER_LIST_BYPASS_VISIBLE_ROWS = 2
/** VPN: видны 6 серверов, с 7-го — прокрутка. */
internal const val SERVER_LIST_VPN_VISIBLE_ROWS = 6

@Composable
internal fun ConnectionServerList(
    serverNames: List<String>,
    selectedIndex: Int,
    disabled: Boolean,
    onSelect: (Int) -> Unit,
    visibleRows: Int = SERVER_LIST_VPN_VISIBLE_ROWS,
    subtitles: List<String>? = null,
    trailing: (@Composable (Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Занять всю высоту, которую даёт родитель (VPN), вместо N строк. */
    fillAvailable: Boolean = false,
    showCheck: Boolean = true,
) {
    val scrollState = rememberScrollState()
    // Полоса прокрутки: появляется при листании, гаснет через ~0,6 с после остановки.
    var indicatorVisible by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState.isScrollInProgress) {
        if (scrollState.isScrollInProgress) indicatorVisible = true
        else { delay(600); indicatorVisible = false }
    }
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (indicatorVisible) 1f else 0f,
        animationSpec = tween(if (indicatorVisible) 120 else 350),
        label = "scrollIndicator"
    )
    val indicatorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val rowHeightDp = if (subtitles != null) SERVER_ROW_HEIGHT_WITH_SUBTITLE else SERVER_ROW_HEIGHT
    val rowHeight = rowHeightDp.dp
    val peek = SERVER_LIST_PEEK.dp
    val scrollable = fillAvailable || serverNames.size > visibleRows
    val listHeight = when {
        serverNames.isEmpty() -> rowHeight
        !scrollable -> rowHeight * serverNames.size
        visibleRows <= SERVER_LIST_BYPASS_VISIBLE_ROWS -> rowHeight * visibleRows + peek
        else -> rowHeight * visibleRows
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fillAvailable) Modifier.fillMaxHeight() else Modifier.height(listHeight))
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .iosScrollIndicator(scrollState, { indicatorAlpha }, indicatorColor)
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
                    onTap = { onSelect(index) },
                    subtitle = subtitles?.getOrNull(index),
                    height = rowHeightDp,
                    showCheck = showCheck,
                    trailing = trailing?.let { slot -> { slot(index) } }
                )
            }
        }
    }
}

/**
 * Полоса прокрутки как в iOS: тонкая, видна только пока список листают,
 * после остановки плавно исчезает.
 */
private fun Modifier.iosScrollIndicator(state: ScrollState, alpha: () -> Float, color: Color): Modifier =
    drawWithContent {
        drawContent()
        val a = alpha()
        val max = state.maxValue
        if (a <= 0f || max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
        val viewport = size.height
        val total = viewport + max
        val inset = 3.dp.toPx()
        val width = 2.5.dp.toPx()
        val track = viewport - inset * 2
        val thumb = (track * viewport / total).coerceAtLeast(28.dp.toPx()).coerceAtMost(track)
        val top = inset + (track - thumb) * (state.value.toFloat() / max)
        drawRoundRect(
            color = color.copy(alpha = color.alpha * a),
            topLeft = Offset(size.width - inset - width, top),
            size = Size(width, thumb),
            cornerRadius = CornerRadius(width / 2, width / 2),
        )
    }

@Composable
internal fun ConnectionServerRow(
    name: String,
    selected: Boolean,
    disabled: Boolean,
    onTap: () -> Unit,
    subtitle: String? = null,
    height: Int = SERVER_ROW_HEIGHT,
    trailing: (@Composable () -> Unit)? = null,
    showCheck: Boolean = true,
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
            .height(height.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(flag, fontSize = 26.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    country,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            trailing?.invoke()
            if (selected && showCheck) {
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

/**
 * Размер индикатора ON/OFF под высоту экрана: на невысоких телефонах он
 * уменьшается, чтобы списку серверов оставалось место (всё остальное закреплено).
 */
@Composable
internal fun adaptiveOrbSize(): Dp {
    val h = LocalConfiguration.current.screenHeightDp
    return when {
        h >= 820 -> 110.dp
        h >= 720 -> 92.dp
        h >= 640 -> 76.dp
        else -> 60.dp
    }
}

@Composable
internal fun ConnectionStatusOrb(
    running: Boolean,
    ready: Boolean,
    size: Dp = 110.dp,
    /** Название сервера — показывается под ON после подключения. */
    serverName: String? = null,
) {
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .scale(if (isConnected) pulse else 1f)
                .clip(CircleShape)
                .background(orbColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(size * 0.745f)
                    .clip(CircleShape)
                    .background(orbColor.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(size * 0.527f)
                        .clip(CircleShape)
                        .background(orbColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isConnected) "ON" else "OFF",
                        color = textColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = (size.value * 0.164f).sp
                    )
                }
            }
        }
        // Строка всегда занимает место — после подключения блоки ниже не сдвигаются.
        Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
            if (isConnected && !serverName.isNullOrBlank()) {
                Text(
                    serverName,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.primary,
                    maxLines = 1
                )
            }
        }
    }
}
