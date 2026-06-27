package com.wdtt.client

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay

/**
 * Гарантирует, что VPN (xray) и Обход Б/С (WireGuard) не работают одновременно.
 */
object ConnectionCoordinator {

    private const val STOP_TIMEOUT_MS = 6_000L
    private const val POLL_MS = 100L

    suspend fun stopBypass(context: Context) {
        if (!TunnelManager.running.value && !TunnelManager.tunnelReady.value) return

        context.startService(
            Intent(context, TunnelService::class.java).apply { action = "STOP" }
        )
        if (waitUntil(STOP_TIMEOUT_MS) { !TunnelManager.running.value && !TunnelManager.tunnelReady.value }) {
            return
        }

        TunnelManager.stopAndWait()
    }

    suspend fun stopVpn(context: Context) {
        if (!XrayManager.running.value) return

        SingBoxVpnService.stop(context)
        waitUntil(STOP_TIMEOUT_MS) { !XrayManager.running.value }
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
