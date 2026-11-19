package com.wdtt.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogSource(val label: String) {
    VPN("VPN"),
    BYPASS("Обход"),
    SERVICE("Сервис"),
    SYSTEM("Система")
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class AppLogEntry(
    val id: Long,
    val timestamp: String,
    val source: LogSource,
    val level: LogLevel,
    val message: String
)

object AppLogger {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    // Логи пишут из многих потоков (пинги серверов одновременно) — id строго уникальный,
    // иначе LazyColumn падает «Key was already used».
    private val counter = java.util.concurrent.atomic.AtomicLong(0L)

    val entries = MutableStateFlow<List<AppLogEntry>>(emptyList())

    // Копия логов в файл (files/logs/, до 2×1 МБ): если прошивка или Android
    // убьют процесс, пока телефон спит, после перезапуска видно, что было
    // перед этим. У каждого процесса свой файл: интерфейс — app.log,
    // туннель (:bg) — app-bg.log; записи :bg ещё и пересылаются в журнал
    // интерфейса (TunnelBridge).
    @Volatile private var logFile: java.io.File? = null
    private val fileWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "applog-file").apply { isDaemon = true }
    }
    private val fileFmt = SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.getDefault())
    private const val MAX_FILE_BYTES = 1_000_000L

    @Volatile private var forwardToUi = false

    /**
     * Вызывать один раз в каждом процессе: подключает свой файл и показывает
     * хвост прошлого запуска. [tunnelProcess] — процесс :bg (пишет app-bg.log
     * и пересылает записи в интерфейс).
     */
    fun init(context: android.content.Context, tunnelProcess: Boolean = false) {
        val dir = java.io.File(context.filesDir, "logs").apply { mkdirs() }
        val file = java.io.File(dir, if (tunnelProcess) BG_FILE else UI_FILE)
        val tail = runCatching {
            if (file.exists()) file.readLines().takeLast(PREVIOUS_TAIL_LINES) else emptyList()
        }.getOrDefault(emptyList())
        logFile = file
        if (tunnelProcess) {
            // Хвост прошлого запуска туннеля интерфейс покажет сам (читает
            // оба файла); здесь только включаем пересылку.
            forwardToUi = true
            log(LogSource.SYSTEM, LogLevel.INFO, "Запуск процесса туннеля")
            return
        }
        val bgTail = runCatching {
            val bg = java.io.File(dir, BG_FILE)
            if (bg.exists()) bg.readLines().takeLast(PREVIOUS_TAIL_LINES) else emptyList()
        }.getOrDefault(emptyList())
        if (tail.isNotEmpty()) {
            log(LogSource.SYSTEM, LogLevel.INFO, "── Конец прошлого запуска интерфейса (${tail.size} строк) ──", toFile = false)
            tail.forEach { log(LogSource.SYSTEM, LogLevel.DEBUG, it, toFile = false) }
        }
        if (bgTail.isNotEmpty()) {
            log(LogSource.SYSTEM, LogLevel.INFO, "── Последние записи туннеля (${bgTail.size} строк) ──", toFile = false)
            bgTail.forEach { log(LogSource.SYSTEM, LogLevel.DEBUG, it, toFile = false) }
        }
        if (tail.isNotEmpty() || bgTail.isNotEmpty()) {
            log(LogSource.SYSTEM, LogLevel.INFO, "── Новый запуск ──", toFile = false)
        }
        log(LogSource.SYSTEM, LogLevel.INFO, "Запуск приложения")
    }

    /** Запись из процесса туннеля (TunnelBridge): только в журнал на экране — в файл её пишет :bg. */
    fun appendRemote(source: LogSource, level: LogLevel, message: String, timeMs: Long) {
        if (message.isBlank()) return
        val entry = AppLogEntry(
            id = counter.getAndIncrement(),
            timestamp = synchronized(fmt) { fmt.format(Date(timeMs)) },
            source = source,
            level = level,
            message = message.trim()
        )
        entries.update { list ->
            val updated = list + entry
            if (updated.size > 500) updated.takeLast(500) else updated
        }
    }

    fun log(source: LogSource, level: LogLevel, message: String) = log(source, level, message, toFile = true)

    private fun log(source: LogSource, level: LogLevel, message: String, toFile: Boolean) {
        if (message.isBlank()) return
        val now = Date()
        val entry = AppLogEntry(
            id = counter.getAndIncrement(),
            timestamp = synchronized(fmt) { fmt.format(now) },
            source = source,
            level = level,
            message = message.trim()
        )
        entries.update { list ->
            val updated = list + entry
            if (updated.size > 500) updated.takeLast(500) else updated
        }
        if (forwardToUi) TunnelBridge.forwardLog(source, level, entry.message, now.time)
        val file = logFile
        if (toFile && file != null) {
            val line = "${synchronized(fileFmt) { fileFmt.format(now) }} [${source.label}]" +
                (if (level == LogLevel.ERROR) " ОШИБКА" else "") + " " +
                entry.message.replace('\n', ' ')
            runCatching {
                fileWriter.execute {
                    runCatching {
                        if (file.length() > MAX_FILE_BYTES) {
                            val old = java.io.File(file.parentFile, file.name + ".1")
                            old.delete()
                            file.renameTo(old)
                        }
                        file.appendText(line + "\n")
                    }
                }
            }
        }
    }

    private const val PREVIOUS_TAIL_LINES = 120
    private const val UI_FILE = "app.log"
    private const val BG_FILE = "app-bg.log"

    /**
     * Файл для «Поделиться логами»: сведения о телефоне + журнал (прошлый и
     * текущий файл). Возвращает null, если файла логов ещё нет.
     */
    fun exportForShare(context: android.content.Context): java.io.File? {
        val dir = java.io.File(context.filesDir, "logs")
        val parts = listOf(UI_FILE + ".1", UI_FILE, BG_FILE + ".1", BG_FILE)
            .map { java.io.File(dir, it) }.filter { it.exists() }
        if (parts.isEmpty()) return null
        val outDir = java.io.File(context.cacheDir, "logs-share").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val out = java.io.File(outDir, "bypassme-logs-$stamp.txt")
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        // Дождаться, пока записываются последние строки.
        runCatching { fileWriter.submit(Runnable {}).get(2, java.util.concurrent.TimeUnit.SECONDS) }
        out.bufferedWriter().use { w ->
            w.write("ByPassMe $version | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) | " +
                "исключение из экономии батареи=${pm.isIgnoringBatteryOptimizations(context.packageName)}\n\n")
            parts.forEach { f ->
                w.write("\n══════ ${if (f.name.startsWith(BG_FILE)) "Туннель" else "Интерфейс"} (${f.name}) ══════\n")
                f.bufferedReader().use { r -> r.copyTo(w) }
            }
        }
        return out
    }

    fun vpn(msg: String)     = log(LogSource.VPN,     LogLevel.INFO,  msg)
    fun vpnErr(msg: String)  = log(LogSource.VPN,     LogLevel.ERROR, msg)
    fun vpnDbg(msg: String)  = log(LogSource.VPN,     LogLevel.DEBUG, msg)

    fun bypass(msg: String)    = log(LogSource.BYPASS,  LogLevel.INFO,  msg)
    fun bypassErr(msg: String) = log(LogSource.BYPASS,  LogLevel.ERROR, msg)
    fun bypassDbg(msg: String) = log(LogSource.BYPASS,  LogLevel.DEBUG, msg)

    fun service(msg: String)    = log(LogSource.SERVICE, LogLevel.INFO,  msg)
    fun serviceErr(msg: String) = log(LogSource.SERVICE, LogLevel.ERROR, msg)

    fun clear() {
        entries.value = emptyList()
    }
}
