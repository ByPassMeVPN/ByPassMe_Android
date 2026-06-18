package com.wdtt.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import com.wdtt.client.XrayManager
import com.wdtt.client.BypassServerManager
import com.wdtt.client.ui.AppLogsTab
import com.wdtt.client.ui.FloatingToolbar
import com.wdtt.client.ui.SettingsTab
import com.wdtt.client.ui.VpnTab
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // VPN permission dialog finished
    }

    private val batteryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkAndRequestVpn()
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        checkAndRequestBattery()
    }

    companion object {
        var activeActivities = 0
        var isForeground: Boolean
            get() = activeActivities > 0
            set(value) {}
    }

    override fun onStart() {
        super.onStart()
        activeActivities++
        ManlCaptchaWebViewManager.checkAndShowPendingCaptcha(this)
    }

    override fun onStop() {
        super.onStop()
        activeActivities--
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkAndRequestNotifications()

        setContent {
            val settingsStore = remember { SettingsStore(this) }
            val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val isDynamicColor by settingsStore.isDynamicColor.collectAsStateWithLifecycle(initialValue = false)
            val themePalette by settingsStore.themePalette.collectAsStateWithLifecycle(initialValue = "indigo")
            val scope = rememberCoroutineScope()
            val appContext = this

            val savedUuid by settingsStore.vpnUuid.collectAsStateWithLifecycle(initialValue = "")

            // Загружаем кеш и запускаем фоновую проверку при старте
            LaunchedEffect(Unit) {
                XrayManager.registerReceiver(appContext)
                SubscriptionChecker.loadCached(appContext)
                XrayManager.loadCached(appContext)
                BypassServerManager.loadCached(appContext)
                SubscriptionChecker.refreshInBackground(appContext, scope)
                BypassServerManager.refreshInBackground(appContext, scope)
            }

            // После ввода подписки — автоматически загружаем список серверов
            LaunchedEffect(savedUuid) {
                if (savedUuid.isBlank()) return@LaunchedEffect
                BypassServerManager.loadCached(appContext)
                if (BypassServerManager.servers.value.isEmpty()) {
                    BypassServerManager.fetchServers(appContext)
                }
            }

            // Автоотзыв: если device_blocked → рефреш очистит uuid автоматически
            // Следим за статусом и если истекла → очищаем
            val subStatus by SubscriptionChecker.status.collectAsStateWithLifecycle()
            LaunchedEffect(subStatus) {
                if (subStatus == "expired" && savedUuid.isNotBlank()) {
                    SubscriptionChecker.revokeAccess(appContext, "expired")
                }
            }

            // Опрос каждые 5 секунд — удаление устройства в боте → онбординг + стоп VPN
            LaunchedEffect(Unit) {
                while (true) {
                    val url = settingsStore.vpnSubscriptionUrl.first()
                    if (url.isNotEmpty()) {
                        SubscriptionChecker.refreshInBackground(appContext, this)
                    }
                    kotlinx.coroutines.delay(5_000)
                }
            }

            WDTTTheme(themeMode = themeMode, dynamicColor = isDynamicColor, themePalette = themePalette) {
                if (savedUuid.isBlank()) {
                    OnboardingScreen(settingsStore = settingsStore, context = appContext)
                } else {
                    MainScreen(
                        settingsStore = settingsStore,
                        themeMode = themeMode,
                        onThemeChange = { mode ->
                            scope.launch { settingsStore.saveThemeMode(mode) }
                        },
                        isDynamicColor = isDynamicColor,
                        onDynamicColorChange = { enabled ->
                            scope.launch { settingsStore.saveDynamicColor(enabled) }
                        },
                        currentPalette = themePalette,
                        onPaletteChange = { palette ->
                            scope.launch { settingsStore.saveThemePalette(palette) }
                        }
                    )
                }
            }
        }
    }

    private fun checkAndRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                checkAndRequestBattery()
            }
        } else {
            checkAndRequestBattery()
        }
    }

    private fun checkAndRequestBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batteryLauncher.launch(intent)
            } catch (e: Exception) {
                checkAndRequestVpn()
            }
        } else {
            checkAndRequestVpn()
        }
    }

    private fun checkAndRequestVpn() {
        try {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnLauncher.launch(vpnIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// ═══ Онбординг — экран до ввода ссылки подписки ═══

@Composable
private fun OnboardingScreen(settingsStore: SettingsStore, context: android.content.Context) {
    val scope = rememberCoroutineScope()
    var inputText by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var showDeviceLimitDialog by remember { mutableStateOf(false) }

    // Захватываем причину один раз при открытии экрана — чтобы не мигала при очистке
    var displayReason by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val reason = settingsStore.vpnRevokeReason.first()
        displayReason = reason
        if (reason.isNotEmpty()) settingsStore.clearRevokeReason()
    }

    if (showDeviceLimitDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceLimitDialog = false },
            title = { Text("Лимит устройств") },
            text = {
                Text(
                    "Достигнут лимит устройств для вашей подписки.\n\n" +
                    "Чтобы добавить это устройство, удалите одно из существующих через Telegram бот ByPassMe."
                )
            },
            confirmButton = {
                TextButton(onClick = { showDeviceLimitDialog = false }) { Text("Понятно") }
            }
        )
    }

    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AppBackdrop(modifier = androidx.compose.ui.Modifier.matchParentSize())

        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "ByPassMe",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold
                ),
                color = MaterialTheme.colorScheme.primary
            )

            // Сообщение зависит от причины возврата на экран онбординга
            when (displayReason) {
                "expired" -> {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "⏰ Подписка истекла",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Text(
                            "Продлите подписку в Telegram боте ByPassMe и вставьте новую ссылку",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                "blocked", "removed" -> {
                    Text(
                        "Устройство удалено из подписки. Вставьте ссылку заново, чтобы переподключиться.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                else -> {
                    Text(
                        "Вставьте ссылку подписки, чтобы начать",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it; errorText = "" },
                label = { Text("Ссылка подписки") },
                placeholder = { Text("https://sub.bypassme.online/...") },
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                enabled = !isLoading,
                isError = errorText.isNotEmpty(),
                supportingText = if (errorText.isNotEmpty()) {
                    { Text(errorText, color = MaterialTheme.colorScheme.error) }
                } else null,
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            )

            Button(
                onClick = {
                    isLoading = true
                    errorText = ""
                    scope.launch {
                        when (val result = SubscriptionChecker.fetch(context, inputText.trim())) {
                            is SubscriptionChecker.Result.Success -> {
                                BypassServerManager.fetchServers(context)
                                isLoading = false
                            }
                            is SubscriptionChecker.Result.DeviceLimitExceeded -> {
                                isLoading = false
                                showDeviceLimitDialog = true
                            }
                            is SubscriptionChecker.Result.DeviceBlocked -> {
                                isLoading = false
                                errorText = "Устройство заблокировано. Обратитесь в поддержку."
                            }
                            is SubscriptionChecker.Result.DeviceRemoved -> {
                                isLoading = false
                                errorText = "Устройство отключено. Вставьте ссылку заново."
                            }
                            is SubscriptionChecker.Result.Error -> {
                                isLoading = false
                                errorText = result.msg
                            }
                        }
                    }
                },
                enabled = inputText.isNotBlank() && !isLoading,
                modifier = androidx.compose.ui.Modifier.fillMaxWidth().height(54.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = androidx.compose.ui.Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(androidx.compose.ui.Modifier.width(10.dp))
                    Text("Получение данных...")
                } else {
                    Text("Подключить", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

// ═══ Навигация ═══

private data class NavItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val navItems = listOf(
    NavItem("Обход Б/С", Icons.Filled.Shield, Icons.Outlined.Shield),
    NavItem("Логи", Icons.Filled.Terminal, Icons.Outlined.Terminal),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settingsStore: SettingsStore,
    themeMode: String = "system",
    onThemeChange: (String) -> Unit = {},
    isDynamicColor: Boolean = false,
    onDynamicColorChange: (Boolean) -> Unit = {},
    currentPalette: String = "indigo",
    onPaletteChange: (String) -> Unit = {}
) {
    val view = LocalView.current
    val density = LocalDensity.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val safeBottomInset = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
    val navOverlayReserve = safeBottomInset + 96.dp

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackdrop(modifier = Modifier.matchParentSize())

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            containerColor = Color.Transparent,
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .pointerInput(selectedTab) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                totalDrag = 0f
                                dragTargetIndex = -1
                                dragProgress = 0f
                            },
                            onDragCancel = {
                                dragTargetIndex = -1
                                dragProgress = 0f
                            },
                            onDragEnd = {
                                if (dragTargetIndex in navItems.indices && dragProgress >= 0.5f) {
                                    selectedTab = dragTargetIndex
                                }
                                dragTargetIndex = -1
                                dragProgress = 0f
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            totalDrag += dragAmount
                            if (abs(totalDrag) < 12f) {
                                dragTargetIndex = -1
                                dragProgress = 0f
                                return@detectHorizontalDragGestures
                            }

                            val candidate = if (totalDrag < 0f) selectedTab + 1 else selectedTab - 1
                            if (candidate !in navItems.indices) {
                                dragTargetIndex = -1
                                dragProgress = 0f
                                return@detectHorizontalDragGestures
                            }

                            dragTargetIndex = candidate
                            dragProgress = (abs(totalDrag) / 180f).coerceIn(0f, 1f)
                        }
                    }
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(tween(300)) togetherWith fadeOut(tween(225))
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = navOverlayReserve),
                    label = "tab_content"
                ) { tab ->
                    when (tab) {
                        0 -> SettingsTab()
                        1 -> AppLogsTab()
                    }
                }

                ProxyNavigationBar(
                    navItems = navItems,
                    selectedTab = selectedTab,
                    dragTargetIndex = dragTargetIndex,
                    dragProgress = dragProgress,
                    onTabSelected = { index ->
                        if (selectedTab != index) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            selectedTab = index
                        }
                        dragTargetIndex = -1
                        dragProgress = 0f
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // Floating theme toolbar overlay
        FloatingToolbar(
            currentTheme = themeMode,
            onThemeChange = onThemeChange,
            isDynamicColor = isDynamicColor,
            onDynamicColorChange = onDynamicColorChange,
            currentPalette = currentPalette,
            onPaletteChange = onPaletteChange
        )
    }
}

@Composable
private fun ProxyNavigationBar(
    navItems: List<NavItem>,
    selectedTab: Int,
    dragTargetIndex: Int,
    dragProgress: Float,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val selectedColor = colors.primary
    val unselectedColor = colors.onSurfaceVariant.copy(alpha = 0.55f)
    val shellColor = if (isDark) {
        colors.surface.copy(alpha = 0.78f)
    } else {
        lerp(colors.surface, colors.surfaceVariant, 0.48f).copy(alpha = 0.95f)
    }
    val shellBorder = if (isDark) {
        colors.outlineVariant.copy(alpha = 0.42f)
    } else {
        colors.outline.copy(alpha = 0.16f)
    }
    val indicatorColor = if (isDark) {
        colors.primaryContainer.copy(alpha = 0.84f)
    } else {
        lerp(colors.primaryContainer, colors.surface, 0.18f).copy(alpha = 0.97f)
    }
    val indicatorIndex = remember { Animatable(selectedTab.toFloat()) }
    val dragVisualIndex = indicatorIndex.value

    LaunchedEffect(selectedTab) {
        if (dragTargetIndex !in navItems.indices) {
            indicatorIndex.animateTo(
                targetValue = selectedTab.toFloat(),
                animationSpec = tween(
                    durationMillis = 720,
                    easing = CubicBezierEasing(0.2f, 0.9f, 0.24f, 1f)
                )
            )
        }
    }

    LaunchedEffect(selectedTab, dragTargetIndex, dragProgress) {
        if (dragTargetIndex in navItems.indices) {
            val target = selectedTab.toFloat() + (dragTargetIndex - selectedTab) * dragProgress
            indicatorIndex.snapTo(target)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        val trackPadding = 8.dp
        val itemWidth = (maxWidth - trackPadding * 2) / navItems.size
        val indicatorOffset = trackPadding + itemWidth * dragVisualIndex

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = shellColor,
            border = BorderStroke(1.dp, shellBorder),
            tonalElevation = 0.dp,
            shadowElevation = if (isDark) 10.dp else 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = indicatorColor,
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .padding(vertical = 6.dp)
                        .width(itemWidth)
                        .fillMaxHeight()
                ) {}

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = trackPadding, vertical = 6.dp)
                ) {
                    navItems.forEachIndexed { index, item ->
                        val emphasis = (1f - abs(index - dragVisualIndex)).coerceIn(0f, 1f)
                        val iconColor = lerp(unselectedColor, selectedColor, emphasis)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(22.dp))
                                .clickable { onTabSelected(index) },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = if (emphasis > 0.55f) item.selectedIcon else item.unselectedIcon,
                                contentDescription = item.label,
                                modifier = Modifier.size(22.dp),
                                tint = iconColor
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (emphasis > 0.55f) FontWeight.SemiBold else FontWeight.Medium,
                                color = iconColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun android16OrbShape(points: Int, innerRatio: Float): Shape = GenericShape { size, _ ->
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val outerRadius = min(size.width, size.height) / 2f
    val innerRadius = outerRadius * innerRatio

    for (i in 0 until points * 2) {
        val angle = (-PI / 2.0) + (i * PI / points)
        val radius = if (i % 2 == 0) outerRadius else innerRadius
        val x = centerX + (radius * cos(angle)).toFloat()
        val y = centerY + (radius * sin(angle)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private val Android16OrbLarge: Shape = android16OrbShape(points = 18, innerRatio = 0.90f)
private val Android16OrbMedium: Shape = android16OrbShape(points = 20, innerRatio = 0.92f)
private val Android16OrbSmall: Shape = android16OrbShape(points = 16, innerRatio = 0.88f)

@Composable
private fun AppBackdrop(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val baseBrush = remember(colors.background, colors.surface, colors.surfaceVariant) {
        Brush.verticalGradient(
            colors = if (isDark) {
                listOf(
                    lerp(colors.background, colors.surface, 0.18f),
                    colors.background,
                    lerp(colors.surfaceVariant, colors.background, 0.72f)
                )
            } else {
                listOf(
                    lerp(colors.background, colors.surface, 0.78f),
                    colors.background,
                    lerp(colors.surfaceVariant, colors.background, 0.30f)
                )
            }
        )
    }
    val topGlow = colors.primary.copy(alpha = if (isDark) 0.055f else 0.09f)
    val leftGlow = if (isDark) {
        colors.tertiary.copy(alpha = 0.045f)
    } else {
        lerp(colors.tertiary, colors.secondaryContainer, 0.74f).copy(alpha = 0.24f)
    }
    val bottomGlow = if (isDark) {
        colors.primary.copy(alpha = 0.04f)
    } else {
        lerp(colors.secondary, colors.primaryContainer, 0.70f).copy(alpha = 0.22f)
    }
    val lightOrbOutline = colors.outlineVariant.copy(alpha = 0.26f)
    val topOrbGlow = if (isDark) {
        topGlow
    } else {
        lerp(colors.primary, colors.primaryContainer, 0.72f).copy(alpha = 0.32f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseBrush)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-86).dp, y = (-126).dp)
                .size(258.dp)
                .clip(Android16OrbLarge)
                .background(topOrbGlow)
                .then(
                    if (isDark) Modifier else Modifier.border(1.dp, lightOrbOutline, Android16OrbLarge)
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-44).dp, y = 28.dp)
                .size(146.dp)
                .clip(Android16OrbSmall)
                .background(leftGlow)
                .then(
                    if (isDark) Modifier else Modifier.border(1.dp, lightOrbOutline.copy(alpha = 0.22f), Android16OrbSmall)
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 62.dp, y = (-208).dp)
                .size(198.dp)
                .clip(Android16OrbMedium)
                .background(bottomGlow)
                .then(
                    if (isDark) Modifier else Modifier.border(1.dp, lightOrbOutline.copy(alpha = 0.20f), Android16OrbMedium)
                )
        )
    }
}
