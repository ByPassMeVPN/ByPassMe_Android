package com.wdtt.client

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

import androidx.compose.runtime.Stable

@Stable
data class LogEntry(
    val key: String,
    val message: String,
    val count: Int = 1,
    val priority: Int = 99, // 0 - Creds, 1 - DTLS, 2 - Ready, 3 - Stats, 99 - Errors/Other
    val isError: Boolean = false
)

object TunnelManager {
    // 100% защита от утечек: единый управляемый глобальный Scope
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var process: Process? = null
    private var readerJob: Job? = null
    private var watchdogJob: Job? = null
    private var wgHelper: WireGuardHelper? = null

    // Error counters for circuit breaker
    private var floodCount = 0
    private var mismatchCount = 0
    private var refusedCount = 0
    private var currentHashErrorCount = 0
    private var activeHashIndex = 0 // 0: primary, 1: secondary
    private var currentParams: TunnelParams? = null
    private var lastContext: Context? = null
    private var forceRegenerateUA = false // принудительная перегенерация UA при ошибках
    private var currentCaptchaMode = "auto"
    private var currentCaptchaSolveMethod = "auto"
    private var currentVkAnonPath = "vkcalls"
    /** Unix-сокет, через который go_client получает TUN fd (режим без WireGuard). */
    private var rawTunSockName = ""

    val running = MutableStateFlow(false)
    val tunnelReady = MutableStateFlow(false) // true когда WireGuard успешно поднят
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val unreadErrorCount = MutableStateFlow(0)
    val config = MutableStateFlow<String?>(null)
    val stats = MutableStateFlow("Ожидание данных...")
    val activeWorkers = MutableStateFlow(0)
    
    val cooldownSeconds = MutableStateFlow(0)
    private var cooldownJob: Job? = null

    // Здоровье туннеля (как в qWDTT 1.4.2, без thrash)
    private const val STALE_STATS_MS = 90_000L
    private const val HEALTH_CHECK_GRACE_MS = 120_000L
    private const val STALL_DOWNLINK_MS = 90_000L
    private const val MIN_RESTART_INTERVAL_MS = 15_000L
    @Volatile private var lastStatsReceivedAtMs = 0L
    @Volatile private var lastUplinkBytes = -1.0
    @Volatile private var lastDownlinkBytes = -1.0
    @Volatile private var lastUplinkChangedAtMs = 0L
    @Volatile private var lastDownlinkChangedAtMs = 0L
    @Volatile private var processStartedAtMs = 0L
    @Volatile private var lastTransportRestartAtMs = 0L
    @Volatile private var transportRestartInProgress = false

    // Держим туннель до явного «Отключить»: после первого успешного подключения
    // сбои (сон телефона, сеть, VK) не выключают Обход, а переподключают его
    // с нарастающей паузой. Полная остановка — только по кнопке, при неверном
    // пароле или если самое первое подключение не удалось.
    @Volatile private var everReady = false
    @Volatile private var recoveryAttempts = 0
    private var recoveryJob: Job? = null
    private const val RECOVERY_BASE_MS = 15_000L
    private const val RECOVERY_MAX_MS = 300_000L
    private val VK_CALL_DEAD = Regex("error_code=(9\\d{3}|951|954)\\b")

    // Флаг: капча сейчас решается (авто или вручную) — блокирует circuit breaker
    @Volatile private var isCaptchaSolving = false

    fun isTunnelHealthy(maxStatsAgeMs: Long = 25_000L): Boolean {
        val now = System.currentTimeMillis()
        return running.value &&
            activeWorkers.value > 0 &&
            lastStatsReceivedAtMs > 0L &&
            now - lastStatsReceivedAtMs <= maxStatsAgeMs
    }

    fun hasDownlinkTrafficSince(sinceMs: Long): Boolean = lastDownlinkChangedAtMs >= sinceMs

    fun clearUnreadErrors() {
        unreadErrorCount.value = 0
    }

    // Добавляем лог с Деплоя
    fun addDeployErrorLog(message: String) {
        val hash = message.hashCode().toString()
        updateLog("deploy_err_$hash", "[ДЕПЛОЙ] $message", 99, true, toAppLog = true)
    }

    fun addDeploySuccessLog(message: String) {
        val hash = message.hashCode().toString() + System.currentTimeMillis()
        updateLog("deploy_ok_$hash", message, 2, false, toAppLog = true)
    }

    private fun updateLog(
        key: String,
        message: String,
        priority: Int,
        isError: Boolean = false,
        toAppLog: Boolean = isError
    ) {
        var isNewEntry = false
        if (isError) {
            unreadErrorCount.update { it + 1 }
        }
        logs.update { currentList ->
            val current = currentList.toMutableList()
            val index = current.indexOfFirst { it.key == key }

            if (index != -1) {
                val entry = current[index]
                current[index] = entry.copy(count = entry.count + 1, message = message, priority = priority, isError = isError)
            } else {
                isNewEntry = true
                current.add(LogEntry(key, message, 1, priority, isError))
            }

            val sorted = current.sortedWith(compareBy({ it.priority }, { if (it.isError) 1 else 0 }, { it.key }))
            if (sorted.size > 100) sorted.takeLast(100) else sorted
        }
        if (toAppLog && isNewEntry) {
            if (isError) AppLogger.bypassErr(message) else AppLogger.bypass(message)
        }
    }

    /** Ошибки капчи — всегда в AppLogger с текстом причины. */
    private fun logCaptchaError(message: String) {
        AppLogger.bypassErr(message)
        updateLog("captcha_err_${message.hashCode()}", message, 5, true)
    }

    private fun isCaptchaFailureLine(line: String): Boolean {
        if (!line.contains("[КАПЧА", ignoreCase = true) && !line.contains("[Captcha]", ignoreCase = true)) {
            return false
        }
        if (line.contains("решил капчу", ignoreCase = true) || line.contains("solve succeeded", ignoreCase = true)) {
            return false
        }
        return line.contains("Solve failed", ignoreCase = true) ||
            line.contains("ошибка", ignoreCase = true) ||
            line.contains("не решил", ignoreCase = true) ||
            line.contains("chain failed", ignoreCase = true) ||
            line.contains("капча не решена", ignoreCase = true) ||
            line.contains("failed:", ignoreCase = true) ||
            line.contains("ошибка решения капчи", ignoreCase = true)
    }

    private fun formatCaptchaErrorFromGo(line: String): String {
        val cleaned = line.replace(Regex("\\[STREAM\\s+\\d+\\]\\s*"), "").trim()
        val detail = when {
            cleaned.contains("Solve failed:", ignoreCase = true) ->
                cleaned.substringAfter("Solve failed:", "").trim()
            cleaned.contains("не решил", ignoreCase = true) ->
                cleaned.substringAfter("не решил", "").trim()
                    .removePrefix("за 2 попытки:").trim()
            cleaned.contains("ошибка", ignoreCase = true) -> {
                val tail = cleaned.substringAfterLast("ошибка", "").removePrefix(":").trim()
                tail.ifEmpty { cleaned.substringAfter(": ", "").trim() }
            }
            cleaned.contains("failed:", ignoreCase = true) ->
                cleaned.substringAfter("failed:", "").trim()
            else -> cleaned
        }
        val prefix = when {
            cleaned.contains("RJS", ignoreCase = true) -> "[КАПЧА RJS]"
            cleaned.contains("WBV", ignoreCase = true) -> "[КАПЧА WBV]"
            else -> "[КАПЧА]"
        }
        val text = detail.ifBlank { cleaned }
        return if (text.startsWith("[")) text else "$prefix $text"
    }

    /**
     * @param withConnectTimeout false — восстановление службы системой: без
     *   2-минутного таймаута (сети может не быть, пока телефон спит).
     */
    fun start(
        context: Context,
        params: TunnelParams,
        isSwitching: Boolean = false,
        withConnectTimeout: Boolean = true,
    ) {
        if (running.value && !isSwitching) return

        val appContext = context.applicationContext // Защита от Memory Leak

        if (!isSwitching) {
            recoveryJob?.cancel()
            everReady = !withConnectTimeout // восстановление = туннель уже был поднят
            recoveryAttempts = 0
            clearLogs()
            config.value = null
            stats.value = "Ожидание данных..."
            floodCount = 0
            mismatchCount = 0
            refusedCount = 0
            currentHashErrorCount = 0
            activeHashIndex = 0
            currentParams = params
            lastContext = appContext
            forceRegenerateUA = false
            currentCaptchaMode = params.captchaMode
            currentCaptchaSolveMethod = params.captchaSolveMethod
            currentVkAnonPath = params.vkAnonPath
        }
        
        // IP доменов «напрямую» — в фоне сразу, параллельно с кредами VK и
        // конфигом сервера: подъём туннеля их не ждёт.
        BypassDirectDomains.prefetch()

        // Обход без WireGuard: GoBackend не создаём. Он грузит в процесс вторую
        // Go-библиотеку (libwg-go) рядом с ядром xray (libgojni) — два рантайма
        // Go в одном процессе роняли приложение при переходе Обход → VPN, а
        // выключение WG в главном потоке подвешивало интерфейс.
        wgHelper = null

        scope.launch {
            try {
                val targetHash = if (activeHashIndex == 0) params.vkHashes else params.secondaryVkHash
                
                // Robust hash parsing: split by comma, newline, or whitespace
                val hashList = targetHash
                    .split(Regex("[,\\s\\n]+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(3)

                if (hashList.isEmpty()) {
                    updateLog("hash_error", "Ошибка: Хеш не указан", 99, true)
                    running.value = false
                    return@launch
                }

                val hashCount = hashList.size.coerceIn(1, 3)
                val totalWorkers = params.workersPerHash.coerceIn(1, 128) // Максимум ограничивается UI (80), но тут ставим хард-лимит побольше на случай запаса
                
                val hashMode = if (activeHashIndex == 0) "Основной" else "Запасной"
                updateLog("config_info", "[$hashMode] Хешей=$hashCount, Потоков=$totalWorkers", 1, toAppLog = true)


                // CRITICAL FIX: Use nativeLibraryDir with extractNativeLibs="true"
                val binaryPath = context.applicationInfo.nativeLibraryDir + "/libclient.so"
                val binaryFile = File(binaryPath)
                
                if (!binaryFile.exists()) {
                    updateLog("binary_error", "Ошибка: Бинарный файл не найден", 99, true)
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    VkCaptchaProfile.writeForGo(appContext)
                }

                val androidId = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ) ?: "unknown"

                val cmd = mutableListOf(
                    binaryPath,
                    // Основной адрес + запасные входные адреса страны (если есть).
                    "-peer", params.peerArg.ifBlank { BypassServerManager.peerArgFor(params.peer) },
                    "-vk", hashList.joinToString(","),
                    "-n", totalWorkers.toString(),
                    "-listen", "127.0.0.1:${params.port}",
                    "-device-id", androidId
                )

                val password = params.connectionPassword.ifBlank { "ByPassMe" }
                cmd.add("-password")
                cmd.add(password)

                cmd.add("-captcha-mode")
                cmd.add(params.captchaMode)

                // «Голосовой» RTP-заголовок — только для серверов, которые его
                // понимают (voice в списке серверов).
                if (params.obfsVoice ?: BypassServerManager.voiceFor(params.peer)) {
                    cmd.add("-obfs-voice")
                }

                cmd.add("-vk-auth")
                cmd.add("anonymous")

                cmd.add("-vk-anon-path")
                cmd.add(params.vkAnonPath)

                // Обход всегда без WireGuard и с TURN по TCP: сырые IP-пакеты
                // идут из TUN прямо в go_client, до TURN VK — по TCP (UDP до
                // relay душат некоторые провайдеры). Сервер: -listen-raw :56003.
                cmd.add("-turn-tcp")
                // DNS для запросов к VK: сначала DNS текущей сети (оператор/Wi-Fi),
                // Яндекс — запасной. При глушении мобильного интернета оператор
                // пропускает только свой DNS — прямой 77.88.8.8 молчал, и Обход
                // не мог найти login.vk.ru / api.vk.me.
                // Как в iOS (идея anton48): IP хостов VK получает само приложение
                // системным резолвером Android (Частный DNS, IPv6-сети), клиент
                // берёт их первыми; остальное — системный DNS, запасной — DNS
                // сети и Яндекс параллельно.
                cmd.add("-go-dns")
                cmd.add("system")
                cmd.add("-dns-fallback")
                cmd.add(networkDnsFallback(appContext))
                val hostIPs = resolveVkHosts()
                if (hostIPs.isNotEmpty()) {
                    cmd.add("-vk-host-ips")
                    cmd.add(org.json.JSONObject(hostIPs.mapValues { org.json.JSONArray(it.value) }).toString())
                }
                updateLog(
                    "vk_hosts_info",
                    "[СЕТЬ] IP хостов VK: ${hostIPs.size} из ${VK_HOSTS.size}",
                    1,
                    toAppLog = true
                )
                if (isSwitching) RawTunEngine.keepTunForRestart()
                rawTunSockName = TunFdBridge.newSocketName()
                cmd.add("-mode")
                cmd.add("rawtun")
                cmd.add("-tun-fd-sock")
                cmd.add(TunFdBridge.goSockPath(rawTunSockName))

                updateLog(
                    "cmd",
                    "Запуск libclient.so (пир ${params.peer}, $totalWorkers потоков, ${params.vkAnonPath})",
                    1,
                    toAppLog = true
                )

                val pb = ProcessBuilder(cmd)
                pb.directory(context.filesDir) // Устанавливаем рабочую директорию
                pb.redirectErrorStream(true)
                
                // Set LD_LIBRARY_PATH
                val env = pb.environment()
                env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir

                // Пока шла подготовка (DNS VK — до 3 с), пользователь мог нажать
                // «Отключить»: не запускаем процесс после остановки.
                if (isSwitching && !running.value) return@launch
                val newProcess = pb.start()
                process = newProcess
                running.value = true
                processStartedAtMs = System.currentTimeMillis()
                lastStatsReceivedAtMs = 0L
                lastUplinkBytes = -1.0
                lastDownlinkBytes = -1.0
                lastUplinkChangedAtMs = 0L
                lastDownlinkChangedAtMs = 0L
                transportRestartInProgress = false
                startLogReader(newProcess)
                startWatchdog(appContext, params)
                if (!isSwitching && withConnectTimeout) scheduleConnectTimeout()

            } catch (e: Exception) {
                updateLog("critical_start_error", "Критическая ошибка запуска: ${e.message}", 99, true)
                e.printStackTrace()
                if (isSwitching && running.value) {
                    // Перезапуск во время работы — не выключаем Обход, пробуем ещё.
                    scheduleRecovery("ошибка запуска клиента")
                } else {
                    running.value = false
                }
            }
        }
    }

    private fun startLogReader(proc: Process) {
        readerJob = scope.launch {
            val reader = proc.inputStream.bufferedReader()
            var collectingConfig = false
            val configBuilder = StringBuilder()
            var collectingRawConfig = false
            val rawConfigBuilder = StringBuilder()

            try {
                var lastResetTime = System.currentTimeMillis()

                reader.forEachLine { line ->
                    // Периодический сброс счетчиков ошибок (раз в 60 сек)
                    val now = System.currentTimeMillis()
                    if (now - lastResetTime > 60000) {
                        refusedCount = 0
                        floodCount = 0
                        mismatchCount = 0
                        currentHashErrorCount = 0
                        lastResetTime = now
                    }

                    // Чистим лог от даты из Go (например, "2023/10/24 12:34:56.123456 [ВОРКЕР...")
                    val msgPrefixReplaced = line.replace(Regex("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\s"), "")
                    val lineTrim = msgPrefixReplaced.trim()

                    val isError = lineTrim.contains("Ошибка", true) || lineTrim.contains("error", true) || lineTrim.contains("FAIL", true) || lineTrim.contains("timeout", true) || lineTrim.contains("refused", true) || lineTrim.contains("FATAL_AUTH", true)

                    // 0. FATAL AUTH — мгновенная остановка
                    if (lineTrim.contains("FATAL_AUTH")) {
                        val reason = when {
                            lineTrim.contains("неверный пароль") -> "Неверный пароль подключения"
                            lineTrim.contains("истёк") -> "Срок действия пароля истёк"
                            lineTrim.contains("другому устройству") -> "Пароль привязан к другому устройству"
                            else -> "Ошибка авторизации"
                        }
                        handleCriticalError("\uD83D\uDD12 $reason. Воркеры остановлены.")
                        return@forEachLine
                    }

                    // 0b. CAPTCHA_SOLVE — запрос от Go для WBV или fallback из RJS
                    if (lineTrim.startsWith("CAPTCHA_SOLVE|")) {
                        val payload = lineTrim.substringAfter("CAPTCHA_SOLVE|")
                        val parts = payload.split("|", limit = 3)
                        when (parts.size) {
                            3 -> {
                                val requestMode = parts[0]
                                val redirectUri = parts[1]
                                val sessionToken = parts[2]
                                scope.launch {
                                    handleCaptchaSolve(requestMode, redirectUri, sessionToken)
                                }
                            }
                            2 -> {
                                val redirectUri = parts[0]
                                val sessionToken = parts[1]
                                scope.launch {
                                    handleCaptchaSolve("auto", redirectUri, sessionToken)
                                }
                            }
                            else -> writeCaptchaResult("error:invalid CAPTCHA_SOLVE format")
                        }
                        return@forEachLine
                    }

                    // 1. ПРЕДОХРАНИТЕЛЬ (Circuit Breaker)
                    if (isError) {
                        when {
                            lineTrim.contains("Flood control", true) -> {
                                floodCount++
                                if (floodCount >= 5 && !isCaptchaSolving) {
                                    floodCount = 0
                                    recoverOrStop("Flood Control (ВК ограничил ваш IP). Попробуйте позже.", 60_000L)
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("ip mismatch", true) -> {
                                mismatchCount++
                                if (mismatchCount >= 5 && !isCaptchaSolving) {
                                    mismatchCount = 0
                                    recoverOrStop("IP Mismatch (IP утерян). Попробуйте переподключиться.", 15_000L)
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("connection refused", true) || lineTrim.contains("timeout", true) -> {
                                // Огромный лимит, потому что каждый воркер кидает эту ошибку при смене сети
                                refusedCount++
                                if (refusedCount >= 400 && !isCaptchaSolving) {
                                    refusedCount = 0
                                    recoverOrStop("Критическое отсутствие сети (400+ таймаутов). Отключение.", 30_000L)
                                    return@forEachLine
                                }
                            }
                            // Только ошибка VK «звонок не найден» (коды 951/954/9xxx).
                            // Раньше ловилась любая строка с «9000» — это ещё и
                            // локальный порт 127.0.0.1:9000.
                            VK_CALL_DEAD.containsMatchIn(lineTrim) || lineTrim.contains("Call not found", true) -> {
                                currentHashErrorCount++
                                // Нужно больше попыток, так как 1 воркер может спамить
                                if (currentHashErrorCount >= 10 && !isCaptchaSolving) {
                                    handleHashError()
                                    return@forEachLine
                                }
                            }
                        }
                    }

                    // 1. Ошибки капчи от Go — с текстом причины
                    if (isCaptchaFailureLine(lineTrim)) {
                        logCaptchaError(formatCaptchaErrorFromGo(lineTrim))
                        return@forEachLine
                    }

                    // 2. Статистика (Обновляемая строка)
                    if (lineTrim.contains("[СТАТИСТИКА]")) {
                        val msg = lineTrim.substringAfter("[СТАТИСТИКА]").trim()
                        stats.value = msg

                        val match = Regex("Активных:\\s*(\\d+)").find(msg)
                        if (match != null) {
                            activeWorkers.value = match.groupValues[1].toIntOrNull() ?: 0
                        }

                        val nowStats = System.currentTimeMillis()
                        lastStatsReceivedAtMs = nowStats
                        val upMatch = Regex("↑\\s*([0-9]+(?:\\.[0-9]+)?)").find(msg)
                        val downMatch = Regex("↓\\s*([0-9]+(?:\\.[0-9]+)?)").find(msg)
                        val upMb = upMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                        val downMb = downMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                        if (upMb != null && upMb != lastUplinkBytes) {
                            lastUplinkBytes = upMb
                            lastUplinkChangedAtMs = nowStats
                        }
                        if (downMb != null && downMb != lastDownlinkBytes) {
                            lastDownlinkBytes = downMb
                            lastDownlinkChangedAtMs = nowStats
                        }

                        updateLog("stats", "[СТАТИСТИКА] $msg", 3, false)
                        return@forEachLine
                    }

                    // 2. Этапы подключения и Ошибки
                    when {

                        // ═══ RJS капча логи: [КАПЧА RJS] со стабильными ключами-шагами ═══
                        lineTrim.contains("[КАПЧА] RJS:") -> {
                            // Удаляем тайминги и лишние скобки: (123мс), (diff=2), (общее время...)
                            var text = lineTrim.substringAfter("[КАПЧА] RJS:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()
                            
                            val stableKey = when {
                                text.contains("Загрузка") || text.contains("fetch") -> "captcha_rjs_1"
                                text.contains("PoW") -> "captcha_rjs_2"
                                text.contains("осматривает") || text.contains("человек") -> "captcha_rjs_3"
                                text.contains("captchaNotRobot") || text.contains("Отправка") -> "captcha_rjs_4"
                                text.contains("endSession") -> "captcha_rjs_5"
                                text.contains("решена") -> "captcha_rjs_6"
                                else -> "captcha_rjs_${text.take(15).hashCode()}"
                            }
                            if (text.contains("Ошибка", ignoreCase = true) || text.contains("ошибка", ignoreCase = true)) {
                                logCaptchaError("[КАПЧА RJS] $text")
                            } else {
                                updateLog(stableKey, "[КАПЧА RJS] $text", 5, false)
                            }
                        }

                        // ═══ WV капча логи от Go: [КАПЧА WBV] со стабильными ключами ═══
                        lineTrim.contains("[КАПЧА] WBV:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] WBV:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()
                            
                            val isErr = text.contains("Ошибка", ignoreCase = true) || text.contains("ошибка", ignoreCase = true)
                            if (isErr) {
                                logCaptchaError("[КАПЧА WBV] $text")
                            } else {
                                val stableKey = when {
                                    text.contains("Запрос") -> "captcha_wv_step_2"
                                    text.contains("Токен") -> "captcha_wv_step_5"
                                    else -> "captcha_wv_go_other"
                                }
                                updateLog(stableKey, "[КАПЧА WBV] $text", 5, false)
                            }
                        }

                        lineTrim.contains("Старт") || lineTrim.contains("Ожидайте") ->
                            updateLog("creds_start", "[ВК] Получение учетных данных...", 2, false)
                        lineTrim.contains("Креды получены") ->
                            updateLog("creds_lifetime", lineTrim, 2, false)
                        lineTrim.contains("Креды OK") || lineTrim.contains("Первые креды") ->
                            updateLog("creds_ok", "[ВК] Учетные данные проверены ✓", 2, toAppLog = true)
                        lineTrim.contains("Решаю VK Smart Captcha") ->
                            updateLog("captcha_start", "[КАПЧА] Решение капчи...", 5, toAppLog = true)
                        lineTrim.contains("Smart Captcha решена") ->
                            updateLog("captcha_done", "[КАПЧА] Капча решена ✓", 5, toAppLog = true)
                        lineTrim.contains("капча не решена", true) || lineTrim.contains("ошибка решения капчи", true) ->
                            logCaptchaError(formatCaptchaErrorFromGo(lineTrim))
                        lineTrim.contains("Relay:") ->
                            updateLog("dtls_start", "[DTLS] Рукопожатие (Handshake)...", 1, false)
                        lineTrim.contains("DTLS ОК") || lineTrim.contains("Соединение установлено") ->
                            updateLog("dtls_ok", "[DTLS] Соединение установлено ✓", 1, false)
                        lineTrim.contains("Активна ✓") || lineTrim.contains("Туннель готов к работе") ->
                            updateLog("ready", "[READY] Туннель готов к работе ✓", 2, toAppLog = true)
                        
                        // Ошибки (в конец)
                        isError -> {
                            // Формируем уникальный ключ ошибки на основе её типа (группируем по типу ошибки)
                            val errorKey = when {
                                lineTrim.contains("connection refused") -> "err_conn_refused"
                                lineTrim.contains("timeout") -> "err_timeout"
                                lineTrim.contains("кредов") -> "err_creds"
                                lineTrim.contains("DTLS") -> "err_dtls"
                                else -> "general_error_" + lineTrim.take(15).hashCode()
                            }
                            updateLog(errorKey, lineTrim, 99, true)
                        }
                    }

                    // 3. Обработка конфига (Скрываем от пользователя)
                    if (line.contains("╔") && line.contains("RAW Конфиг")) {
                        collectingRawConfig = true
                        rawConfigBuilder.clear()
                        return@forEachLine
                    } else if (collectingRawConfig) {
                        if (line.contains("╚")) {
                            collectingRawConfig = false
                            // Строки «IP = 10.70.x.y», «DNS = …», «MTU = …»
                            val fields = rawConfigBuilder.toString().lines().mapNotNull { l ->
                                val kv = l.split("=", limit = 2).map { it.trim() }
                                if (kv.size == 2) kv[0] to kv[1] else null
                            }.toMap()
                            val ip = fields["IP"].orEmpty()
                            val dnsCsv = fields["DNS"].orEmpty().ifBlank { "1.1.1.1,1.0.0.1" }
                            val mtu = fields["MTU"]?.toIntOrNull() ?: 1350
                            val ctx = lastContext
                            val sockName = rawTunSockName
                            if (ip.isEmpty() || ctx == null) {
                                updateLog("rawtun_conf_error", "Ошибка: некорректный конфиг от сервера", 99, true)
                            } else {
                                scope.launch {
                                    // Одна повторная попытка, дальше — стоп с причиной
                                    // (раньше ошибка только писалась в лог, а кнопка
                                    // навсегда оставалась в «Подключении»).
                                    var lastError: Exception? = null
                                    for (attempt in 1..2) {
                                        // Сессия уже сменилась (стоп, новый запуск) — эта
                                        // попытка от старого go_client: раньше она отдавала
                                        // TUN мёртвому процессу и рвала новое подключение.
                                        if (rawTunSockName != sockName) return@launch
                                        try {
                                            RawTunEngine.start(ctx, ip, dnsCsv, mtu, sockName)
                                            markReady()
                                            return@launch
                                        } catch (e: Exception) {
                                            lastError = e
                                            updateLog("vpn_start_error", "Ошибка запуска VPN (попытка $attempt): ${e.readableMessage()}", 99, true)
                                            delay(2_000)
                                        }
                                    }
                                    if (rawTunSockName != sockName) return@launch
                                    recoverOrStop("Не удалось поднять VPN: ${lastError?.readableMessage() ?: "ошибка"}")
                                }
                            }
                        } else if (line.contains("║")) {
                            val content = line.replace("║", "").trim()
                            if (content.isNotEmpty()) rawConfigBuilder.appendLine(content)
                        }
                        return@forEachLine
                    } else if (line.contains("╔") && line.contains("WireGuard")) {
                        collectingConfig = true
                        configBuilder.clear()
                        return@forEachLine
                    } else if (collectingConfig) {
                        if (line.contains("╚")) {
                            collectingConfig = false
                            val configStr = configBuilder.toString().trim()
                            config.value = configStr
                            
                            scope.launch(Dispatchers.Main) {
                                try {
                                    wgHelper?.startTunnel(configStr)
                                    markReady()
                                } catch (e: Exception) {
                                    updateLog("vpn_start_error", "Ошибка запуска VPN: ${e.readableMessage()}", 99, true)
                                }
                            }
                        } else if (line.contains("║")) {
                            val content = line.replace("║", "").trim()
                            if (content.isNotEmpty()) {
                                configBuilder.appendLine(content)
                            }
                        }
                        return@forEachLine
                    }
                }
            } catch (e: Exception) {
                updateLog("sys_error", "Процесс остановлен: ${e.message}", -1, true)
            } finally {
                // Раньше здесь ставилось running=false: при каждом мягком
                // перезапуске (сон, смена сети) служба видела «не запущен»
                // и выключала Обход целиком. running — это «пользователь хочет
                // туннель», его меняют только stop()/ошибки. Упавший процесс
                // перезапустит сторож. И не трогаем ссылку на НОВЫЙ процесс.
                if (process === proc) process = null
            }
        }
    }

    private var connectTimeoutJob: Job? = null

    /** Хосты VK, к которым ходит клиент за TURN-кредами и капчей. */
    private val VK_HOSTS = listOf(
        "login.vk.ru", "api.vk.ru", "api.vk.me", "calls.okcdn.ru",
        "id.vk.com", "id.vk.ru", "vk.com", "vk.ru",
    )

    /**
     * IP хостов VK системным резолвером Android. Ждём не дольше 3 с: блокирующий
     * getAllByName не прерывается, поэтому результаты собираются в общую таблицу,
     * а запуск берёт то, что успело прийти.
     */
    private suspend fun resolveVkHosts(): Map<String, List<String>> {
        val out = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        val jobs = VK_HOSTS.map { host ->
            scope.launch(Dispatchers.IO) {
                val ips = runCatching {
                    java.net.InetAddress.getAllByName(host)
                        .filterIsInstance<java.net.Inet4Address>()
                        .mapNotNull { it.hostAddress }
                        .distinct()
                }.getOrNull().orEmpty()
                if (ips.isNotEmpty()) out[host] = ips
            }
        }
        kotlinx.coroutines.withTimeoutOrNull(2_000) { jobs.forEach { it.join() } }
        return HashMap(out)
    }

    /** DNS реальной сети (не нашего VPN-туннеля), IPv4 и IPv6, + Яндекс — запасной. */
    private fun networkDnsFallback(context: Context): String {
        val ips = linkedSetOf<String>()
        runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val networks = cm.allNetworks.sortedByDescending { n ->
                val caps = cm.getNetworkCapabilities(n)
                if (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 1 else 0
            }
            for (n in networks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                cm.getLinkProperties(n)?.dnsServers
                    ?.mapNotNull { it.hostAddress?.substringBefore('%') }
                    ?.forEach { ips += it }
            }
        }
        ips += "77.88.8.8"
        ips += "77.88.8.1"
        updateLog("dns_info", "[СЕТЬ] Запасной DNS: ${ips.joinToString(", ")}", 1, toAppLog = true)
        return ips.joinToString(",")
    }

    /** Подключение не должно висеть вечно: 2 минуты (капча не считается) — стоп с причиной. */
    private fun scheduleConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            var waited = 0L
            while (waited < 120_000L) {
                delay(1_000)
                if (!running.value || tunnelReady.value) return@launch
                if (!isCaptchaSolving && !ManlCaptchaWebViewManager.isCaptchaPending) waited += 1_000
            }
            if (running.value && !tunnelReady.value) {
                handleCriticalError("Не удалось подключиться за 2 минуты — подробности в логах")
            }
        }
    }

    private fun handleCriticalError(message: String) {
        updateLog("circuit_breaker", "[СТОП] $message", -1, true)
        stop()
    }

    private fun markReady() {
        tunnelReady.value = true
        everReady = true
        recoveryAttempts = 0
        updateLog("ready", "[READY] Туннель готов к работе ✓", 2, toAppLog = true)
    }

    /**
     * Первое подключение не удалось — стоп с причиной (пользователь видит её
     * сразу). Туннель уже работал — не выключаем, а переподключаем с паузой.
     */
    private fun recoverOrStop(message: String, minDelayMs: Long = 0L) {
        if (everReady && running.value) {
            updateLog("recover_reason", "⚠ $message — переподключаемся, Обход не выключаем", 50, true)
            scheduleRecovery(message.substringBefore(" (").take(60), minDelayMs)
        } else {
            handleCriticalError(message)
        }
    }

    /**
     * Перезапуск Go-клиента с нарастающей паузой: 15 с → 30 → 60 → … → 5 мин.
     * TUN (VPN-значок) остаётся: приложения ждут, трафик не уходит мимо.
     */
    private fun scheduleRecovery(reason: String, minDelayMs: Long = 0L) {
        val params = currentParams ?: return
        val context = lastContext ?: return
        if (!running.value) return
        val attempt = recoveryAttempts
        val backoff = if (attempt == 0) 2_000L
            else (RECOVERY_BASE_MS shl (attempt - 1).coerceAtMost(5)).coerceAtMost(RECOVERY_MAX_MS)
        val delayMs = maxOf(backoff, minDelayMs) + (0..2_000).random()
        recoveryAttempts = attempt + 1
        tunnelReady.value = false
        updateLog(
            "recover",
            "[СЕТЬ] Переподключение через ${delayMs / 1000} с ($reason, попытка ${attempt + 1})",
            50,
            toAppLog = true
        )
        recoveryJob?.cancel()
        killProcess()
        recoveryJob = scope.launch {
            delay(delayMs)
            if (running.value && currentParams === params) {
                start(context, params, isSwitching = true)
            }
        }
    }

    private fun handleHashError() {
        val params = currentParams ?: return
        val context = lastContext ?: return

        currentHashErrorCount = 0
        forceRegenerateUA = true // Перегенерируем UA при следующих ошибках

        if (params.secondaryVkHash.isNotEmpty() && activeHashIndex == 0) {
            updateLog("hash_switch", "Основной хеш мертв. Переключение на запасной...", 50, true)
            activeHashIndex = 1
            // Не stopOnlyProcess(): он ставил running=false, и служба успевала
            // выключиться, пока поднимался процесс с запасным хешем.
            killProcess()
            start(context, params, isSwitching = true)
        } else {
            val msg = if (activeHashIndex == 1) "Запасной хеш тоже мертв. Отключение." else "Хеш умер, запасного нет. Отключение."
            // Звонок VK мог закрыться на время — после подключения пробуем дальше
            // (пауза до 5 мин), чтобы не оставить человека без интернета молча.
            recoverOrStop(msg, 60_000L)
        }
    }

    // ==================== WATCHDOG ====================
    // Процесс жив? Зомби 0 воркеров? Stale stats / stall ↑без↓ — мягкий restartTransport.
    private fun startWatchdog(context: Context, params: TunnelParams) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var zeroWorkersSince = 0L
            delay(10_000) // Даём 10 сек на старт
            while (isActive && running.value) {
                val proc = process
                if (proc == null || !proc.isAlive) {
                    updateLog("watchdog", "⚠ Процесс упал. Перезапуск...", 50, true)
                    activeWorkers.value = 0
                    forceRegenerateUA = true
                    // С паузой: если клиент падает сразу после старта, не
                    // долбим VK каждые 2 с (это приводит к Flood control).
                    scheduleRecovery("процесс упал")
                    return@launch
                }

                val workers = activeWorkers.value
                val now = System.currentTimeMillis()
                if (workers <= 0) {
                    if (zeroWorkersSince == 0L) {
                        zeroWorkersSince = now
                    } else if (now - zeroWorkersSince > 90_000 && !ManlCaptchaWebViewManager.isCaptchaPending && !isCaptchaSolving) {
                        updateLog("watchdog", "⚠ Зомби-процесс (0 воркеров 90с). Перезапуск...", 50, true)
                        forceRegenerateUA = true
                        scheduleRecovery("0 воркеров 90 с")
                        return@launch
                    }
                } else {
                    zeroWorkersSince = 0L

                    // Нет статистики 90с после grace 120с — мягкий рестарт транспорта
                    if (
                        !transportRestartInProgress &&
                        processStartedAtMs > 0L &&
                        now - processStartedAtMs > HEALTH_CHECK_GRACE_MS &&
                        lastStatsReceivedAtMs > 0L &&
                        now - lastStatsReceivedAtMs > STALE_STATS_MS &&
                        !ManlCaptchaWebViewManager.isCaptchaPending &&
                        !isCaptchaSolving
                    ) {
                        updateLog(
                            "health_stale",
                            "⚠ Нет статистики от воркеров ${STALE_STATS_MS / 1000}с — мягкий перезапуск",
                            50,
                            true
                        )
                        restartTransport("stale stats")
                        return@launch
                    }

                    // ↑есть, ↓нет долго — half-dead TURN ( thrash-safe пороги )
                    if (
                        !transportRestartInProgress &&
                        processStartedAtMs > 0L &&
                        now - processStartedAtMs > HEALTH_CHECK_GRACE_MS &&
                        lastUplinkChangedAtMs > 0L &&
                        now - lastUplinkChangedAtMs < 20_000L &&
                        (lastDownlinkChangedAtMs == 0L || now - lastDownlinkChangedAtMs > STALL_DOWNLINK_MS) &&
                        !ManlCaptchaWebViewManager.isCaptchaPending &&
                        !isCaptchaSolving
                    ) {
                        updateLog(
                            "health_stall",
                            "⚠ Трафик завис (↑есть ↓нет ${STALL_DOWNLINK_MS / 1000}с) — мягкий перезапуск",
                            50,
                            true
                        )
                        restartTransport("stall downlink")
                        return@launch
                    }
                }

                delay(5_000)
            }
        }
    }

    fun restartTransport(reason: String = "смена сети") {
        val params = currentParams ?: return
        val context = lastContext ?: return
        val now = System.currentTimeMillis()
        if (transportRestartInProgress) return
        if (now - lastTransportRestartAtMs < MIN_RESTART_INTERVAL_MS) {
            updateLog("network_restart", "[СЕТЬ] Пропуск рестарта ($reason): слишком часто", 50, toAppLog = true)
            return
        }
        // Не рвём живой туннель по «смене сети», если статистика свежая
        if (reason.contains("сеть", ignoreCase = true) && isTunnelHealthy()) {
            updateLog("network_restart", "[СЕТЬ] Туннель жив — без рестарта ($reason)", 50, toAppLog = true)
            return
        }
        transportRestartInProgress = true
        lastTransportRestartAtMs = now
        updateLog("network_restart", "[СЕТЬ] Мягкий перезапуск транспорта ($reason)...", 50, toAppLog = true)
        killProcess() // Только процесс; WireGuard/VPN не трогаем
        scope.launch {
            try {
                delay(1500)
                if (running.value) {
                    start(context, params, isSwitching = true)
                }
            } finally {
                transportRestartInProgress = false
            }
        }
    }

    fun pause() {
        if (!running.value) return
        recoveryJob?.cancel()
        killProcess() // Не ставим running=false, чтоб сервис не умер
        activeWorkers.value = 0
    }

    fun resume() {
        if (currentParams != null && lastContext != null) {
            recoveryJob?.cancel()
            scope.launch {
                start(lastContext!!, currentParams!!, isSwitching = true)
            }
        }
    }

    // Убивает процесс без изменения running
    private fun killProcess() {
        watchdogJob?.cancel()
        readerJob?.cancel()
        val proc = process
        process = null
        if (proc != null) {
            try { proc.destroy() } catch (_: Exception) {}
            // Даём 500мс на graceful shutdown
            try { proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            if (proc.isAlive) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
                try { proc.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            }
        }
    }

    private fun log(message: String) {
        updateLog("internal_${message.hashCode()}", message, 50, false)
    }

    fun stop() {
        recoveryJob?.cancel()
        connectTimeoutJob?.cancel()
        isCaptchaSolving = false
        tunnelReady.value = false
        CaptchaWebViewManager.onTunnelStop()
        scope.launch(Dispatchers.Main) {
            wgHelper?.stopTunnel()
        }
        val ctx = lastContext ?: WdttApplication.instance
        scope.launch { RawTunEngine.stop(ctx) }
        killProcess()
        running.value = false
        activeWorkers.value = 0
        currentParams = null
        ManlCaptchaWebViewManager.cancelCaptcha()
    }

    /** Синхронная остановка WireGuard + Go-процесса перед переключением на VPN. */
    suspend fun stopAndWait() {
        recoveryJob?.cancel()
        connectTimeoutJob?.cancel()
        isCaptchaSolving = false
        tunnelReady.value = false
        watchdogJob?.cancel()
        readerJob?.cancel()

        CaptchaWebViewManager.onTunnelStop()
        ManlCaptchaWebViewManager.cancelCaptcha()

        val ctx = lastContext ?: WdttApplication.instance
        withContext(Dispatchers.Main) {
            wgHelper?.releaseVpnCompletely()
            if (WireGuardHelper.isVpnSlotInUse) {
                WireGuardHelper(ctx).releaseVpnCompletely()
            }
        }
        RawTunEngine.stop(ctx)
        withContext(Dispatchers.IO) {
            killProcess()
            activeWorkers.value = 0
            currentParams = null
            repeat(15) {
                try {
                    java.net.ServerSocket(9000, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.close() }
                    running.value = false
                    return@withContext
                } catch (_: Exception) {
                    delay(50)
                }
            }
            running.value = false
        }
    }

    /** Систему или другое приложение забрали VPN — останавливаем Обход целиком. */
    fun onRawTunRevoked(context: Context) {
        RawTunEngine.onVpnRevoked()
        // При переходе на вкладку VPN слот забирает наш же XrayVpnService —
        // это не ошибка, Обход к этому моменту уже остановлен.
        if (!running.value) return
        updateLog("raw_revoked", "VPN отключён системой", 99, true)
        runCatching {
            context.startService(Intent(context, TunnelService::class.java).apply { action = "STOP" })
        }
    }

    fun reloadWireGuard() {
        if (running.value) {
            scope.launch {
                wgHelper?.reloadTunnel()
            }
        }
    }

    // ==================== CAPTCHA SOLVER (WebView Mode) ====================

    /**
     * Вызывается при получении CAPTCHA_SOLVE от Go-процесса.
     * auto/manual — только скрытый WebView (без ручного UI).
     */
    private suspend fun handleCaptchaSolve(requestMode: String, redirectUri: String, sessionToken: String) {
        val ctx = lastContext ?: run {
            writeCaptchaResult("error:context is null")
            return
        }
        val mode = requestMode.lowercase()

        isCaptchaSolving = true
        try {
            val token = when (mode) {
                "auto", "manual", "selected" -> solveAutoWebViewCaptcha(ctx, redirectUri, sessionToken)
                else -> solveAutoWebViewCaptcha(ctx, redirectUri, sessionToken)
            }
            updateLog("captcha_wv_step_4", "[КАПЧА WBV] Капча решена ✓", 5, toAppLog = true)
            writeCaptchaResult(token)
        } catch (e: IllegalStateException) {
            val errorMsg = e.message ?: "WV state error"
            logCaptchaError("[КАПЧА WBV] $errorMsg")
            writeCaptchaResult("error:$errorMsg")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logCaptchaError("[КАПЧА WBV] Таймаут WebView (авто 3/3)")
            writeCaptchaResult("error:timeout")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            logCaptchaError("[КАПЧА WBV] Отменено")
            writeCaptchaResult("error:cancelled")
        } catch (e: Exception) {
            val errorMsg = e.message ?: "${e::class.simpleName}"
            if (errorMsg != "tunnel stopped") {
                logCaptchaError("[КАПЧА WBV] $errorMsg")
            }
            writeCaptchaResult("error:$errorMsg")
        } finally {
            isCaptchaSolving = false
            currentHashErrorCount = 0
            refusedCount = 0
            floodCount = 0
            mismatchCount = 0
        }

        updateLog("captcha_wv_step_6", "[КАПЧА WBV] WebView уничтожен", 5, false)
    }

    private suspend fun solveSingleAutoWebViewCaptcha(
        redirectUri: String,
        sessionToken: String
    ): String {
        updateLog("captcha_wv_step_1", "[КАПЧА WBV] Авто WebView попытка 10с...", 5, false)
        return CaptchaWebViewManager.solveCaptchaAsync(redirectUri, sessionToken) { step ->
            updateLog("captcha_wv_auto_step", "[КАПЧА WBV] $step", 5, false)
        }
    }

    /** До 3 скрытых попыток — без ручного WebView для пользователя. */
    private suspend fun solveAutoWebViewCaptcha(
        ctx: android.content.Context,
        redirectUri: String,
        sessionToken: String
    ): String {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            updateLog(
                "captcha_wv_step_1",
                "[КАПЧА WBV] Авто WebView попытка ${attempt + 1}/3...",
                5,
                false
            )
            try {
                return solveSingleAutoWebViewCaptcha(redirectUri, sessionToken)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                lastError = e
                updateLog("captcha_wv_timeout_$attempt", "[КАПЧА WBV] Авто таймаут (${attempt + 1}/3)", 5, false)
            } catch (e: IllegalStateException) {
                if (e.message == CaptchaWebViewManager.ERROR_SLIDER_DETECTED) {
                    updateLog("captcha_wv_slider_$attempt", "[КАПЧА WBV] Слайдер, повтор авто (${attempt + 1}/3)", 5, false)
                    lastError = e
                } else {
                    throw e
                }
            }
            kotlinx.coroutines.delay(300)
        }
        throw lastError ?: IllegalStateException("auto captcha failed")
    }

    /**
     * Записывает результат решения капчи в stdin Go-процесса.
     */
    /**
     * Команда Go-клиенту через stdin: WAKE — проверить потоки после сна,
     * RECONNECT|причина — сменилась сеть, поднять все потоки заново (без
     * перезапуска процесса и без пересоздания TUN). Старый клиент без этих
     * команд их просто игнорирует.
     */
    fun sendCommand(command: String): Boolean {
        val proc = process
        if (proc == null || !proc.isAlive || !running.value) return false
        return try {
            synchronized(this) {
                proc.outputStream.write("$command\n".toByteArray(Charsets.UTF_8))
                proc.outputStream.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun writeCaptchaResult(result: String) {
        val proc = process
        if (proc == null || !proc.isAlive) return
        try {
            val line = "CAPTCHA_RESULT|$result\n"
            synchronized(this) {
                proc.outputStream.write(line.toByteArray(Charsets.UTF_8))
                proc.outputStream.flush()
            }
        } catch (e: Exception) {
            updateLog("captcha_write_err", "[КАПЧА] Ошибка записи: ${e.message}", 200, true)
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
        activeWorkers.value = 0
        tunnelReady.value = false
    }

    fun startCooldown(seconds: Int) {
        cooldownJob?.cancel()
        cooldownSeconds.value = seconds
        cooldownJob = scope.launch(Dispatchers.Main) {
            while (cooldownSeconds.value > 0) {
                delay(1000)
                cooldownSeconds.update { it - 1 }
            }
        }
    }

    private fun Throwable.readableMessage(): String {
        val text = message ?: localizedMessage
        return if (text.isNullOrBlank()) this::class.java.simpleName else "${this::class.java.simpleName}: $text"
    }
}

data class TunnelParams(
    val peer: String,
    val vkHashes: String,
    val secondaryVkHash: String = "",
    val workersPerHash: Int,
    val port: Int,
    val sni: String = "",
    val connectionPassword: String = "",
    val protocol: String = "udp",
    val captchaMode: String = "auto",
    val captchaSolveMethod: String = "auto",
    val vkAnonPath: String = "vkcalls",
    /** -peer целиком (основной + запасные адреса) — собирает интерфейс. */
    val peerArg: String = "",
    /** «Голосовой» заголовок; null — взять из списка серверов этого процесса. */
    val obfsVoice: Boolean? = null
)
