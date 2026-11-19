package com.wdtt.client

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Пинг VPN-серверов как в iOS: время TCP-подключения к адресу сервера
 * (DNS + рукопожатие TCP), все серверы параллельно, ответ за доли секунды.
 * Приложение исключено из VPN, поэтому замер идёт напрямую, а не через туннель.
 * У Hysteria-серверов на том же адресе открыт TCP 443 — цифра есть у всех.
 *
 * Сервер с прокси: время только последнего шага — от команды CONNECT до ответа
 * прокси «соединение с сервером установлено» (путь «телефон → прокси → сервер»,
 * как пинг в Happ), лучший из прокси. Подключение к прокси, приветствие и логин
 * в цифру не входят — иначе это 4 круга до прокси вместо одного (~300 мс вместо ~90).
 * Прямой вход у таких серверов закрыт, прямой TCP-пинг показал бы «нет ответа».
 */
object VpnPinger {
    sealed interface State {
        data object Measuring : State
        data class Done(val ms: Long) : State
        data object Timeout : State
    }

    private const val TIMEOUT_MS = 4_000
    private const val PROXY_TIMEOUT_MS = 6_000

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
                val proxies = server.activeProxies
                val ms = if (proxies.isEmpty()) {
                    tcpConnectMs(server.address, server.port)
                } else {
                    viaProxiesMs(proxies, server.address, server.port)
                }
                if (ms == null) {
                    val how = if (proxies.isEmpty()) "" else " через прокси (${proxies.size})"
                    val sec = (if (proxies.isEmpty()) TIMEOUT_MS else PROXY_TIMEOUT_MS) / 1000
                    AppLogger.vpnErr("Пинг ${server.name}$how: нет ответа за $sec с")
                }
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

    /** Все прокси параллельно, результат — самый быстрый из ответивших. */
    private suspend fun viaProxiesMs(proxies: List<VpnProxy>, host: String, port: Int): Long? =
        coroutineScope {
            proxies.map { p -> async(Dispatchers.IO) { viaProxyMs(p, host, port) } }
                .awaitAll()
                .filterNotNull()
                .minOrNull()
        }

    private fun viaProxyMs(p: VpnProxy, host: String, port: Int): Long? {
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.soTimeout = PROXY_TIMEOUT_MS
                s.connect(InetSocketAddress(p.host, p.port), PROXY_TIMEOUT_MS)
                val ns = if (p.type == "http") httpConnect(s, p, host, port) else socks5Connect(s, p, host, port)
                ns?.let { (it / 1_000_000).coerceAtLeast(1) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun InputStream.readFully(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) throw java.io.EOFException()
            off += r
        }
        return buf
    }

    /**
     * SOCKS5 (RFC 1928/1929): приветствие, логин/пароль при наличии, CONNECT по имени/IP.
     * Возвращает время шага CONNECT в наносекундах (null — не получилось).
     */
    private fun socks5Connect(s: Socket, p: VpnProxy, host: String, port: Int): Long? {
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        val auth = p.user.isNotEmpty() || p.pass.isNotEmpty()
        out.write(if (auth) byteArrayOf(5, 1, 2) else byteArrayOf(5, 1, 0))
        out.flush()
        val greet = inp.readFully(2)
        if (greet[0].toInt() != 5) return null
        when (greet[1].toInt() and 0xFF) {
            0 -> Unit
            2 -> {
                val u = p.user.toByteArray()
                val pw = p.pass.toByteArray()
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(pw.size.toByte()) + pw)
                out.flush()
                if (inp.readFully(2)[1].toInt() != 0) return null
            }
            else -> return null
        }
        val ipv4 = host.split('.').takeIf { parts ->
            parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
        }
        val addr = if (ipv4 != null) {
            byteArrayOf(1) + ipv4.map { it.toInt().toByte() }.toByteArray()
        } else {
            val h = host.toByteArray()
            byteArrayOf(3, h.size.toByte()) + h
        }
        val request = byteArrayOf(5, 1, 0) + addr + byteArrayOf((port shr 8).toByte(), port.toByte())
        val start = System.nanoTime()
        out.write(request)
        out.flush()
        val head = inp.readFully(4)
        val took = System.nanoTime() - start
        return if (head[0].toInt() == 5 && head[1].toInt() == 0) took else null
    }

    /** HTTP CONNECT с Proxy-Authorization: Basic; успех — ответ 2xx. Время запроса в нс или null. */
    private fun httpConnect(s: Socket, p: VpnProxy, host: String, port: Int): Long? {
        val out = s.getOutputStream()
        val sb = StringBuilder()
            .append("CONNECT $host:$port HTTP/1.1\r\n")
            .append("Host: $host:$port\r\n")
        if (p.user.isNotEmpty() || p.pass.isNotEmpty()) {
            val token = Base64.encodeToString("${p.user}:${p.pass}".toByteArray(), Base64.NO_WRAP)
            sb.append("Proxy-Authorization: Basic $token\r\n")
        }
        sb.append("\r\n")
        val start = System.nanoTime()
        out.write(sb.toString().toByteArray())
        out.flush()
        // Первая строка ответа: "HTTP/1.1 200 Connection established".
        val inp = s.getInputStream()
        val line = StringBuilder()
        while (line.length < 256) {
            val c = inp.read()
            if (c < 0 || c == '\n'.code) break
            line.append(c.toChar())
        }
        val took = System.nanoTime() - start
        val code = line.split(' ').getOrNull(1)?.toIntOrNull() ?: return null
        return if (code in 200..299) took else null
    }
}
