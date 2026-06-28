package com.wdtt.client

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Гарантирует, что VPN (xray) и Обход Б/С (WireGuard) не работают одновременно.
 * На Android активен только один VpnService — между режимами нужна пауза.
 */
object ConnectionCoordinator {

    private const val STOP_TIMEOUT_MS = 10_000L
    private const val VPN_RELEASE_DELAY_MS = 1_200L
    private const val POLL_MS = 100L

    suspend fun stopBypass(context: Context) = withContext(Dispatchers.IO) {
        if (!TunnelManager.running.value && !TunnelManager.tunnelReady.value) return@withContext

        TunnelManager.stopAndWait()
        context.startService(
            Intent(context, TunnelService::class.java).apply { action = "STOP" }
        )
        waitUntil(STOP_TIMEOUT_MS) {
            !TunnelManager.running.value && !TunnelManager.tunnelReady.value
        }
        delay(VPN_RELEASE_DELAY_MS)
    }

    suspend fun stopVpn(context: Context) = withContext(Dispatchers.IO) {
        if (!XrayManager.running.value && !XrayManager.connecting.value && !XrayVpnService.isSessionActive) {
            return@withContext
        }

        XrayManager.connecting.value = false
        XrayVpnService.stop(context)
        waitUntil(STOP_TIMEOUT_MS) {
            !XrayManager.running.value && !XrayManager.connecting.value
        }
        XrayVpnService.waitUntilStopped(STOP_TIMEOUT_MS)
        delay(VPN_RELEASE_DELAY_MS)
    }

    suspend fun prepareForVpn(context: Context) = withContext(Dispatchers.IO) {
        stopBypass(context)
        stopVpn(context)
        delay(300)
    }

    suspend fun prepareForBypass(context: Context) = withContext(Dispatchers.IO) {
        stopVpn(context)
        stopBypass(context)
        delay(300)
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
