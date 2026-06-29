package com.wdtt.client

import android.content.Context
import android.content.Intent
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Переключение VPN (xray) ↔ Обход Б/С (WireGuard).
 */
object ConnectionCoordinator {

    const val EXTRA_HANDOFF_DONE = "handoff_done"

    private val handoffMutex = Mutex()

    private const val STOP_TIMEOUT_MS = 5_000L
    private const val POLL_MS = 100L

    suspend fun prepareForVpn(context: Context) = handoffMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            stopBypass(context)
            stopActiveXray(context)
            waitSlotFree()
        }
    }

    suspend fun prepareForBypass(context: Context) = handoffMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            stopActiveXray(context)
            stopBypass(context)
            waitSlotFree()
        }
    }

    /** После STOP обхода GoBackend может ещё держать VPN-слот — только stopService, без WireGuardHelper. */
    suspend fun releaseBypassVpnSlot(context: Context) {
        val appCtx = context.applicationContext
        withContext(Dispatchers.Main + NonCancellable) {
            runCatching {
                appCtx.stopService(Intent(appCtx, GoBackend.VpnService::class.java))
            }
        }
        delay(500)
    }

    private suspend fun stopBypass(context: Context) {
        if (!isBypassActive()) return
        val appCtx = context.applicationContext
        TunnelManager.stopAndWait()
        appCtx.startService(
            Intent(appCtx, TunnelService::class.java).apply { action = "STOP" }
        )
        waitUntil(STOP_TIMEOUT_MS) {
            !TunnelManager.running.value &&
                !TunnelManager.tunnelReady.value &&
                !WireGuardHelper.isVpnSlotInUse
        }
        releaseBypassVpnSlot(appCtx)
    }

    private suspend fun stopActiveXray(context: Context) {
        val appCtx = context.applicationContext
        if (!XrayManager.running.value && !XrayVpnService.isSessionActive) return
        XrayManager.connecting.value = false
        XrayVpnService.stop(appCtx)
        waitUntil(STOP_TIMEOUT_MS) {
            !XrayManager.running.value && !XrayVpnService.isSessionActive
        }
        XrayVpnService.waitUntilStopped(STOP_TIMEOUT_MS)
    }

    private fun isBypassActive(): Boolean =
        TunnelManager.running.value ||
            TunnelManager.tunnelReady.value ||
            WireGuardHelper.isVpnSlotInUse

    private suspend fun waitSlotFree() {
        waitUntil(STOP_TIMEOUT_MS) {
            !XrayVpnService.isSessionActive &&
                !XrayManager.running.value &&
                !TunnelManager.tunnelReady.value &&
                !TunnelManager.running.value &&
                !WireGuardHelper.isVpnSlotInUse
        }
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
