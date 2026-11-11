package com.wdtt.client

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Пинг VPN-серверов как в iOS: время TCP-подключения к адресу сервера
 * (DNS + рукопожатие TCP), все серверы параллельно, ответ за доли секунды.
 * Приложение исключено из VPN, поэтому замер идёт напрямую, а не через туннель.
 * У Hysteria-серверов на том же адресе открыт TCP 443 — цифра есть у всех.
 */
object VpnPinger {
    sealed interface State {
        data object Measuring : State
        data class Done(val ms: Long) : State
        data object Timeout : State
    }

    private const val TIMEOUT_MS = 4_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** serverId → состояние замера. */
    val results = MutableStateFlow<Map<String, State>>(emptyMap())

    @Suppress("UNUSED_PARAMETER")
    fun ping(context: Context, server: VpnServerTemplate) = pingAll(context, listOf(server))

    @Suppress("UNUSED_PARAMETER")
    fun pingAll(context: Context, servers: List<VpnServerTemplate>) {
        val todo = servers.filter { results.value[it.id] != State.Measuring }
        if (todo.isEmpty()) return
        results.update { map -> map + todo.associate { it.id to State.Measuring } }
        todo.forEach { server ->
            scope.launch {
                val ms = tcpConnectMs(server.address, server.port)
                if (ms == null) AppLogger.vpnErr("Пинг ${server.name}: нет ответа за ${TIMEOUT_MS / 1000} с")
                results.update { it + (server.id to if (ms != null) State.Done(ms) else State.Timeout) }
            }
        }
    }

    private fun tcpConnectMs(host: String, port: Int): Long? {
        val start = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            }
            ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        } catch (_: Exception) {
            null
        }
    }
}
