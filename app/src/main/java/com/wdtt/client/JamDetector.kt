package com.wdtt.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Фоновая проверка глушения: TCP к иностранным VPN-серверам (NL / DE / US).
 * Если ни один не отвечает — зона глушения; если хоть один отвечает — интернет открыт.
 */
object JamDetector {

    enum class State { UNKNOWN, OPEN, JAMMED, CHECKING }

    private val _state = MutableStateFlow(State.UNKNOWN)
    val state: StateFlow<State> = _state.asStateFlow()
    val isJammed: Boolean get() = _state.value == State.JAMMED

    private var loopJob: Job? = null
    private const val PROBE_TIMEOUT_MS = 4_000
    private const val INTERVAL_MS = 45_000L

    private val fallbackTargets = listOf(
        "144.31.54.213" to 443, // NL
        "89.34.219.129" to 443, // DE
        "45.91.138.188" to 443, // US
    )

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            checkNow()
            while (isActive) {
                delay(INTERVAL_MS)
                checkNow()
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    suspend fun checkNow() {
        val prev = _state.value
        if (prev != State.JAMMED && prev != State.OPEN) {
            _state.value = State.CHECKING
        }
        val targets = probeTargets()
        val anyOk = withContext(Dispatchers.IO) {
            targets.any { (host, port) -> tcpReachable(host, port) }
        }
        val next = if (anyOk) State.OPEN else State.JAMMED
        if (next != prev) {
            if (next == State.JAMMED) {
                AppLogger.service("Глушение: иностранные серверы NL/DE/US недоступны → используйте Обход")
            } else if (prev == State.JAMMED) {
                AppLogger.service("Глушение снято: иностранные серверы снова доступны")
            }
        }
        _state.value = next
    }

    private fun probeTargets(): List<Pair<String, Int>> {
        val byCountry = linkedMapOf<String, Pair<String, Int>>()
        for (s in VpnServerManager.servers.value) {
            val key = s.id.filter { it.isLetter() }.lowercase()
            if (key !in listOf("nl", "de", "us")) continue
            if (key !in byCountry) {
                byCountry[key] = s.address to s.port.coerceAtLeast(1)
            }
        }
        val picked = listOf("nl", "de", "us").mapNotNull { byCountry[it] }
        return picked.ifEmpty { fallbackTargets }
    }

    private fun tcpReachable(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
