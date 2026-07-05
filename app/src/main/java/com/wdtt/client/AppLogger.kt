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
    private var counter = 0L

    val entries = MutableStateFlow<List<AppLogEntry>>(emptyList())

    fun log(source: LogSource, level: LogLevel, message: String) {
        if (message.isBlank()) return
        val entry = AppLogEntry(
            id = counter++,
            timestamp = fmt.format(Date()),
            source = source,
            level = level,
            message = message.trim()
        )
        entries.update { list ->
            val updated = list + entry
            if (updated.size > 500) updated.takeLast(500) else updated
        }
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
