package com.wdtt.client

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Переключение VPN (xray) ↔ Обход Б/С (WireGuard).
 * На Android одновременно активен только один VpnService — нужна пауза ~3 с.
 */
object ConnectionCoordinator {

    const val EXTRA_HANDOFF_DONE = "handoff_done"

    private val handoffMutex = Mutex()

    /** Ждём после остановки, пока система освободит VPN-интерфейс. */
    private const val SLOT_RELEASE_MS = 3_000L
    private const val STOP_TIMEOUT_MS = 12_000L
    private const val POLL_MS = 150L

    suspend fun prepareForVpn(context: Context) = handoffMutex.withLock {
        withContext(Dispatchers.IO) {
            stopBypass(context)
            stopVpn(context)
            waitVpnSlotReleased()
        }
    }

    suspend fun prepareForBypass(context: Context) = handoffMutex.withLock {
        withContext(Dispatchers.IO) {
            stopVpn(context)
            stopBypass(context)
            waitVpnSlotReleased()
        }
    }

    private suspend fun stopBypass(context: Context) {
        if (!TunnelManager.running.value && !TunnelManager.tunnelReady.value) return

        TunnelManager.stopAndWait()
        context.startService(
            Intent(context, TunnelService::class.java).apply { action = "STOP" }
        )
        waitUntil(STOP_TIMEOUT_MS) {
            !TunnelManager.running.value && !TunnelManager.tunnelReady.value
        }
    }

    private suspend fun stopVpn(context: Context) {
        if (!XrayManager.running.value && !XrayManager.connecting.value && !XrayVpnService.isSessionActive) {
            return
        }

        XrayManager.connecting.value = false
        XrayVpnService.stop(context)
        waitUntil(STOP_TIMEOUT_MS) {
            !XrayManager.running.value && !XrayManager.connecting.value
        }
        XrayVpnService.waitUntilStopped(STOP_TIMEOUT_MS)
    }

    /** Проверяем флаги + фиксированная пауза для Android VPN stack. */
    private suspend fun waitVpnSlotReleased() {
        repeat(60) {
            val xrayFree = !XrayVpnService.isSessionActive &&
                !XrayManager.running.value &&
                !XrayManager.connecting.value
            val bypassFree = !TunnelManager.tunnelReady.value && !TunnelManager.running.value
            if (xrayFree && bypassFree) {
                delay(SLOT_RELEASE_MS)
                return
            }
            delay(POLL_MS)
        }
        delay(SLOT_RELEASE_MS)
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(POLL_MS)
        }
        return condition()
    }
}
