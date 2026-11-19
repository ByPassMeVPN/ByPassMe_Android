package com.wdtt.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TUNNEL_NOTIFICATION_CHANNEL_ID = "wdtt_tunnel_v4"
private const val TUNNEL_NOTIFICATION_ID = 1
private const val RJS_TEMPORARILY_DISABLED = false

/**
 * Служба Обхода — одна на всё: служба переднего плана (уведомление, локи,
 * сеть, пульс) и VPN-служба, которая сама держит TUN ([RawTunHolder]).
 * Android видит её как работающий VPN — её не выгружают отдельно от
 * туннеля, а «Постоянный VPN» поднимает Обход (через XrayVpnService).
 */
class TunnelService : VpnService() {
    /** TUN Обхода; живёт, пока жива служба. */
    val rawTun: RawTunHolder by lazy { RawTunHolder(this) }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var updateJob: Job? = null
    private var lastNotificationText: String? = null
    
    // Network Monitoring
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkChangeTime = 0L
    private val activeNetworks = mutableSetOf<Network>()
    private var isTunnelPaused = false
    private var networkRecoveryJob: Job? = null

    // Основная (физическая) сеть приложения: наш пакет исключён из VPN,
    // поэтому default-сеть для нас — Wi-Fi или мобильная, а не туннель.
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var defaultNetwork: Network? = null
    private var heartbeatJob: Job? = null

    // Накопленный сон к прошлой проверке: elapsedRealtime идёт во сне,
    // uptimeMillis — нет. Разница прироста = сколько устройство спало.
    @Volatile private var sleptAtLastCheck = 0L

    /**
     * Экран включился после глубокого сна — сразу просим Go-клиент проверить
     * потоки (ответ сервера на keepalive), не дожидаясь его детектора сна.
     * Короткие блокировки (< 1 мин) пропускаем: живые потоки не трогаем.
     */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val slept = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
            val gap = slept - sleptAtLastCheck
            sleptAtLastCheck = slept
            if (gap < DEEP_SLEEP_KICK_MS || !TunnelManager.running.value || isTunnelPaused) return
            AppLogger.bypass("Пробуждение после сна ${gap / 1000} с — проверяем потоки")
            TunnelManager.sendCommand("WAKE")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // Сразу берем лок при создании
        acquireWakeLock()
        setupNetworkCallback()
        setupDefaultNetworkCallback()
        sleptAtLastCheck = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this, screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // null — Android поднял липкую службу после убийства процесса;
        // RESTORE — «Постоянный VPN» (XrayVpnService передал управление сюда).
        if (intent == null || intent.action == ACTION_RESTORE) {
            if (intent?.action == ACTION_RESTORE) {
                // Запущены через startForegroundService — уведомление сразу,
                // иначе Android завершит приложение с ошибкой.
                startPersistentForeground(createNotification("Восстановление соединения..."))
            }
            restoreTunnel()
            return START_STICKY
        }

        when (intent.action) {
            "START" -> {
                val notification = createNotification("Запуск...")
                startPersistentForeground(notification)

                // Пароль сохраняет интерфейс: процесс :bg DataStore не пишет.
                val connectionPassword = intent.getStringExtra("connection_password") ?: ""

                val params = TunnelParams(
                    peer = intent.getStringExtra("peer") ?: "",
                    vkHashes = intent.getStringExtra("vk_hashes") ?: "",
                    secondaryVkHash = intent.getStringExtra("secondary_vk_hash") ?: "",
                    workersPerHash = intent.getIntExtra("workers_per_hash", SettingsStore.DEFAULT_WORKERS_BYPASS),
                    port = intent.getIntExtra("port", 9000),
                    sni = intent.getStringExtra("sni") ?: "",
                    connectionPassword = connectionPassword,
                    protocol = intent.getStringExtra("protocol") ?: "udp",
                    captchaMode = sanitizeCaptchaMode(intent.getStringExtra("captcha_mode")),
                    captchaSolveMethod = intent.getStringExtra("captcha_solve_method") ?: "auto",
                    vkAnonPath = sanitizeVkAnonPath(
                        intent.getStringExtra("vk_anon_path")?.takeIf { it.isNotEmpty() }
                    ),
                    peerArg = intent.getStringExtra("peer_arg") ?: "",
                    obfsVoice = if (intent.hasExtra("obfs_voice"))
                        intent.getBooleanExtra("obfs_voice", false) else null
                )
                BypassRestorePrefs.saveParams(applicationContext, params)
                TunnelManager.scope.launch {
                    val handoffDone = intent.getBooleanExtra(ConnectionCoordinator.EXTRA_HANDOFF_DONE, false)
                    if (!handoffDone) {
                        ConnectionCoordinator.prepareForBypass(applicationContext)
                    }
                    startTunnel(params)
                }
            }
            "STOP" -> {
                // Пользователь выключил Обход — не восстанавливать его потом
                // ни липкой службой, ни «Постоянным VPN».
                BypassRestorePrefs.setWanted(applicationContext, false)
                stopTunnel()
            }
            "DEPLOY_START" -> {
                val notification = createNotification("Установка на сервер...", "DEPLOY_CANCEL", "Отменить")
                startPersistentForeground(notification)
                acquireWakeLock()
            }
            "DEPLOY_CANCEL" -> {
                com.wdtt.client.DeployManager.writeError("[!] ❌ Установка отменена пользователем")
                com.wdtt.client.DeployManager.stopDeploy("error: Отменена пользователем")
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            "DEPLOY_STOP" -> {
                if (!TunnelManager.running.value) {
                    stopTunnel()
                } else {
                    updateNotification("Туннель активен")
                }
            }
        }
        return START_STICKY
    }

    private fun restoreTunnel() {
        // Этот процесс (:bg) — здесь живёт сама VPN-служба; XrayManager —
        // объект интерфейса, в :bg он ничего не знает.
        if (XrayVpnService.isSessionActive) {
            stopSelf()
            return
        }
        if (TunnelManager.running.value) return
        if (!BypassRestorePrefs.isWanted(applicationContext)) {
            Log.i("TunnelService", "restore: Обход выключен пользователем — не поднимаем")
            stopSelf()
            return
        }
        // Защита от вечного вылета: если процесс падает сразу после
        // восстановления, Android перезапускает липкую службу снова и снова,
        // и приложение не открывается. Больше 3 восстановлений за минуту — стоп.
        val prefs = getSharedPreferences("tunnel_restore", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val windowStart = prefs.getLong("window_start", 0L)
        val count = if (now - windowStart < 60_000L) prefs.getInt("count", 0) + 1 else 1
        prefs.edit()
            .putLong("window_start", if (count == 1) now else windowStart)
            .putInt("count", count)
            .apply()
        if (count > 3) {
            Log.w("TunnelService", "restore loop — не восстанавливаем")
            AppLogger.bypassErr("Обход не восстановлен автоматически: повторные сбои подряд")
            stopSelf()
            return
        }

        val notification = createNotification("Восстановление соединения...")
        startPersistentForeground(notification)

        val appContext = applicationContext
        TunnelManager.scope.launch {
            try {
                val params = BypassRestorePrefs.loadParams(appContext) ?: paramsFromStore(appContext)
                if (params.peer.isNotEmpty() && params.vkHashes.isNotEmpty()) {
                    ConnectionCoordinator.prepareForBypass(appContext)
                    launch(Dispatchers.Main) { startTunnel(params, restored = true) }
                } else {
                    launch(Dispatchers.Main) { stopTunnel() }
                }
            } catch (_: Exception) {
                launch(Dispatchers.Main) { stopTunnel() }
            }
        }
    }

    /**
     * Запасной путь: параметры ещё не запомнены (обновление со старой
     * версии) — читаем настройки интерфейса. Только чтение.
     */
    private suspend fun paramsFromStore(appContext: Context): TunnelParams {
        val store = SettingsStore(appContext)
        val basePeer = store.peer.first()
        if (BypassServerManager.servers.value.isEmpty()) {
            BypassServerManager.loadCached(appContext)
        }
        val rawPort = BypassServerManager.servers.value
            .firstOrNull { it.host == basePeer }?.rawPort ?: SettingsStore.BYPASS_RAW_PORT
        val peerWithPort = if (basePeer.isBlank() || basePeer.contains(":")) basePeer
            else "$basePeer:$rawPort"
        return TunnelParams(
            peer = peerWithPort,
            vkHashes = store.vkHashes.first(),
            secondaryVkHash = store.secondaryVkHash.first(),
            workersPerHash = store.workersPerHash.first(),
            port = store.listenPort.first(),
            sni = store.sni.first(),
            connectionPassword = store.connectionPassword.first().ifBlank { "ByPassMe" },
            captchaMode = sanitizeCaptchaMode(store.captchaMode.first()),
            captchaSolveMethod = store.captchaSolveMethod.first(),
            vkAnonPath = sanitizeVkAnonPath(store.vkAnonPath.first())
        )
    }

    /** @param restored служба поднята системой заново — без 2-минутного таймаута. */
    private fun startTunnel(params: TunnelParams, restored: Boolean = false) {
        updateNotification("Подключение...")
        acquireWakeLock()
        acquireWifiLock()
        BypassRestorePrefs.setWanted(applicationContext, true)
        logEnvironment(if (restored) "восстановление" else "запуск")
        startHeartbeat()

        // Подготавливаем CaptchaWebViewManager (не создаёт WebView — просто сохраняет контекст)
        // Вызываем всегда — дёшево, а WebView создаётся на лету при каждом запросе капчи
        CaptchaWebViewManager.onTunnelStart(applicationContext)

        TunnelManager.start(this, params, withConnectTimeout = !restored)
        startStatsUpdater()
    }

    private fun stopTunnel() {
        updateJob?.cancel()
        heartbeatJob?.cancel()
        CaptchaWebViewManager.onTunnelStop()
        TunnelManager.scope.launch {
            TunnelManager.stopAndWait()
            releaseWakeLock()
            releaseWifiLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun setupNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        activeNetworks.clear()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val wasEmpty = activeNetworks.isEmpty()
                activeNetworks.add(network)
                if (wasEmpty) {
                    if (isTunnelPaused) {
                        isTunnelPaused = false
                        Log.d("TunnelService", "Сеть появилась, возобновляем туннель")
                        TunnelManager.resume()
                        updateNotification("Подключение...")
                    } else {
                        handleNetworkChange()
                    }
                } else {
                    handleNetworkChange()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                activeNetworks.remove(network)
                if (activeNetworks.isEmpty() && TunnelManager.running.value && !isTunnelPaused) {
                    isTunnelPaused = true
                    Log.d("TunnelService", "Сеть потеряна, приостанавливаем туннель")
                    TunnelManager.pause()
                    updateNotification("Ожидание сети (Фоновый сон)")
                }
            }
        }

        // ВАЖНО: Слушаем только реальные (не VPN) сети с доступом в интернет.
        // Иначе интерфейс VPN (tun0) считается активной сетью, и при "Режиме полёта" activeNetworks не падает до 0.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
            
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }
    
    /**
     * Смена основной сети (Wi-Fi ↔ LTE, другая Wi-Fi): сокеты к TURN VK
     * привязаны к старой сети и мертвы. Новый Go-клиент поднимает все потоки
     * заново по команде RECONNECT — сразу, без перезапуска процесса и TUN.
     * Старый клиент (без команды) — как раньше, через handleNetworkChange.
     */
    private fun setupDefaultNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Сеть самого VPN не считаем сменой: после подъёма TUN она
                // приходит сюда же, и потоки зря переподключались сразу после
                // «готов» (в логе «Сменилась сеть (мобильная)»).
                if (isVpnNetwork(cm, network)) return
                val previous = defaultNetwork
                defaultNetwork = network
                applyUnderlyingNetwork()
                if (previous == null || previous == network) return
                if (!TunnelManager.running.value || isTunnelPaused) return
                val type = describeNetwork(cm, network)
                AppLogger.bypass("Сменилась сеть ($type) — переподключаем потоки")
                if (!TunnelManager.sendCommand("RECONNECT|сменилась сеть: $type")) {
                    handleNetworkChange()
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network == defaultNetwork) applyUnderlyingNetwork()
            }

            override fun onLost(network: Network) {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    applyUnderlyingNetwork()
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { defaultNetworkCallback = cb }
    }

    /**
     * Сообщаем Android, через какую сеть идёт VPN Обхода (как в VPN-режиме):
     * система правильно показывает, есть ли интернет, лимитная ли сеть
     * (VPN лимитный только поверх лимитной), и быстрее переключает приложения
     * при смене Wi-Fi ↔ мобильная. Вызывается и сразу после подъёма TUN.
     */
    fun applyUnderlyingNetwork() {
        if (rawTun.currentTunFd() == null) return
        val net = defaultNetwork
        runCatching { setUnderlyingNetworks(if (net != null) arrayOf(net) else null) }
    }

    private fun isVpnNetwork(cm: ConnectivityManager, network: Network): Boolean =
        runCatching { cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            .getOrNull() == true

    private fun describeNetwork(cm: ConnectivityManager, network: Network): String {
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return "сеть"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "сеть"
        }
    }

    /**
     * Строка-пульс раз в минуту: по её обрыву в логе видно точный момент,
     * когда процесс заморозили или убили (остальной лог в этот момент молчит).
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = TunnelManager.scope.launch {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            // От запуска туннеля, а не от включения телефона (раньше в пульсе
            // были дни работы устройства).
            val since = SystemClock.elapsedRealtime()
            val sleptAtStart = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
            while (isActive) {
                delay(HEARTBEAT_MS)
                if (!TunnelManager.running.value) continue
                val slept = (SystemClock.elapsedRealtime() - SystemClock.uptimeMillis() - sleptAtStart) / 1000
                AppLogger.service(
                    "пульс: работает ${(SystemClock.elapsedRealtime() - since) / 1000} с, из них сон $slept с, " +
                        "doze=${pm.isDeviceIdleMode}, потоков ${TunnelManager.activeWorkers.value}, " +
                        "готов=${TunnelManager.tunnelReady.value}"
                )
            }
        }
    }

    /** Окружение в начале сессии: без исключения из экономии батареи прошивка режет фон. */
    private fun logEnvironment(reason: String) {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val restricted = if (Build.VERSION.SDK_INT >= 28) am.isBackgroundRestricted else false
            AppLogger.service(
                "Обход: $reason. ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
                    "исключение из экономии батареи=${pm.isIgnoringBatteryOptimizations(packageName)}, " +
                    "фон ограничен=$restricted, энергосбережение=${pm.isPowerSaveMode}, doze=${pm.isDeviceIdleMode}"
            )
        }
    }

    private fun handleNetworkChange() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkChangeTime < 5000) return
        lastNetworkChangeTime = now

        if (!TunnelManager.running.value || isTunnelPaused) return

        // Не рвём живой туннель на каждый VALIDATED flap — даём самовосстановление.
        if (TunnelManager.isTunnelHealthy()) {
            Log.d("TunnelService", "Сеть изменилась, туннель жив — без рестарта")
            return
        }

        networkRecoveryJob?.cancel()
        networkRecoveryJob = TunnelManager.scope.launch {
            val changedAt = System.currentTimeMillis()
            Log.d("TunnelService", "Сеть изменилась — ждём 15с самовосстановления")
            delay(15_000)
            if (!TunnelManager.running.value || isTunnelPaused) return@launch
            if (TunnelManager.hasDownlinkTrafficSince(changedAt) || TunnelManager.isTunnelHealthy()) {
                Log.d("TunnelService", "Туннель сам восстановился после смены сети")
                return@launch
            }
            Log.d("TunnelService", "Нет downlink — мягкий перезапуск Go-клиента")
            TunnelManager.restartTransport("смена сети")
        }
    }

    private fun sanitizeCaptchaMode(mode: String?): String {
        val normalized = mode?.lowercase()?.trim() ?: "auto"
        if (RJS_TEMPORARILY_DISABLED && normalized == "rjs") return "auto"
        return when (normalized) {
            "auto", "wv", "rjs" -> normalized
            else -> "auto"
        }
    }

    private fun sanitizeVkAnonPath(path: String?): String =
        if (path.equals("legacy", ignoreCase = true)) "legacy" else "vkcalls"

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "wdtt:tunnel_cpu"
        ).apply { 
            setReferenceCounted(false)
            acquire() 
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        
        // Используем WIFI_MODE_FULL_LOW_LATENCY для Android 10+, 
        // это предотвращает отключение радиомодуля при выключенном экране
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        
        wifiLock = wm.createWifiLock(mode, "wdtt:wifi_perf").apply { 
            setReferenceCounted(false)
            acquire() 
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun releaseWifiLock() {
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        wifiLock = null
    }

    private fun startStatsUpdater() {
        updateJob?.cancel()
        updateJob = TunnelManager.scope.launch(Dispatchers.Main) {
            delay(1000)
            // Запуск может идти дольше секунды (DNS хостов VK — до нескольких
            // секунд). Раньше служба через 1 с без running сама себя
            // останавливала: туннель поднимался без службы, а TUN — это служба
            // («служба Обхода не запущена»). Стоп — только если туннель уже
            // работал и остановился, или так и не запустился за 45 с.
            val startedAt = SystemClock.elapsedRealtime()
            var seenRunning = false
            while (isActive) {
                if (TunnelManager.running.value) seenRunning = true
                if (!TunnelManager.running.value && !isTunnelPaused &&
                    (seenRunning || SystemClock.elapsedRealtime() - startedAt > 45_000)
                ) {
                    // Туннель полностью остановлен (не на паузе) — убиваем сервис
                    stopSelf()
                    break
                }
                if (!isTunnelPaused) {
                    updateNotification(buildTunnelNotificationText())
                }
                delay(2000)
            }
        }
    }

    private fun buildTunnelNotificationText(): String {
        if (!TunnelManager.tunnelReady.value) return "Подключение..."
        val statsText = TunnelManager.stats.value.trim()
        return when {
            statsText.isEmpty() || statsText == "Ожидание данных..." -> "Подключено"
            else -> statsText
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            TUNNEL_NOTIFICATION_CHANNEL_ID,
            "Обход",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Уведомление о работе туннеля"
            setShowBadge(false)
            // ВАЖНО: Разрешаем показывать на экране блокировки
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(text: String, actionName: String = "STOP", actionTitle: String = "Отключить"): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = PendingIntent.getService(
            this, if (actionName == "STOP") 1 else 2,
            Intent(this, TunnelService::class.java).apply { action = actionName },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, TUNNEL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Обход")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setOngoing(true)
            .setLocalOnly(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, actionTitle, stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFAULT)
            // ВАЖНО: Делаем уведомление публичным (видимым на локскрине)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Категория SERVICE помогает системе понять важность
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true) // Не издавать звук и не будить экран при обновлении статистики!
            .setSilent(true) // Делаем тихим само уведомление
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setWhen(0L)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startPersistentForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(TUNNEL_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TUNNEL_NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (lastNotificationText == text) return
        lastNotificationText = text
        val notification = createNotification(text)
        getSystemService(NotificationManager::class.java).notify(TUNNEL_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            connectivityManager?.unregisterNetworkCallback(it)
        }
        defaultNetworkCallback?.let { cb ->
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
            }
        }
        runCatching { unregisterReceiver(screenOnReceiver) }
        heartbeatJob?.cancel()
        stopTunnel()
        rawTun.closeTun()
        if (instance === this) instance = null
    }

    // VPN-службе система привязывается через onBind — свой ответ ломал бы VPN.
    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    /** VPN забрало другое приложение (или наш же VPN-режим) — Обход стоп. */
    override fun onRevoke() {
        Log.w("TunnelService", "VPN Обхода отозван системой")
        TunnelManager.onRawTunRevoked(applicationContext)
    }

    companion object {
        /** Работающая служба Обхода (процесс :bg) — для TUN ([RawTunEngine]). */
        @Volatile
        var instance: TunnelService? = null
            private set

        /** Сон дольше минуты — после включения экрана проверяем потоки. */
        const val ACTION_RESTORE = "RESTORE"
        private const val DEEP_SLEEP_KICK_MS = 60_000L
        private const val HEARTBEAT_MS = 60_000L
    }
}
