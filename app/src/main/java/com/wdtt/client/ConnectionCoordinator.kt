package com.wdtt.client

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay

/**
 * Гарантирует, что VPN (xray) и Обход Б/С (WireGuard) не работают одновременно.
 */
object ConnectionCoordinator {

    private const val STOP_TIMEOUT_MS = 8_000L
    private const val VPN_RELEASE_DELAY_MS = 600L
    private const val POLL_MS = 100L

    suspend fun stopBypass(context: Context) {
        if (!TunnelManager.running.value && !TunnelManager.tunnelReady.value) return

        // Сначала гарантированно гасим WireGuard (иначе XrayVpnService не поднимет TUN)
        TunnelManager.stopAndWait()
        context.startService(
            Intent(context, TunnelService::class.java).apply { action = "STOP" }
        )
        delay(VPN_RELEASE_DELAY_MS)
    }

    suspend fun stopVpn(context: Context) {
        if (!XrayManager.running.value && !XrayManager.connecting.value) return

        XrayManager.connecting.value = false
        XrayVpnService.stop(context)
        waitUntil(STOP_TIMEOUT_MS) {
            !XrayManager.running.value && !XrayManager.connecting.value
        }
        delay(VPN_RELEASE_DELAY_MS)
    }

    suspend fun prepareForVpn(context: Context) {
        stopBypass(context)
        stopVpn(context)
    }

    suspend fun prepareForBypass(context: Context) {
        stopVpn(context)
        stopBypass(context)
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
