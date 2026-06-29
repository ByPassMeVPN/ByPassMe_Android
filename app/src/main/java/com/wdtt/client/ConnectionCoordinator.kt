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

    /** Перед запуском Xray VPN — освободить слот (в т.ч. GoBackend после STOP обхода). */
    suspend fun ensureForXray(context: Context) = handoffMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            if (isBypassActive()) {
                stopBypass(context)
            } else {
                releaseGoBackend(context.applicationContext)
            }
            stopVpn(context)
            waitSlotFree()
        }
    }

    suspend fun prepareForBypass(context: Context) = handoffMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            stopVpn(context)
            stopBypass(context)
            waitSlotFree()
        }
    }

    private suspend fun stopBypass(context: Context) {
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
        releaseGoBackend(appCtx)
    }

    private suspend fun stopVpn(context: Context) {
        val appCtx = context.applicationContext
        if (!XrayManager.running.value && !XrayManager.connecting.value && !XrayVpnService.isSessionActive) {
            return
        }
        XrayManager.connecting.value = false
        XrayVpnService.stop(appCtx)
        waitUntil(STOP_TIMEOUT_MS) {
            !XrayManager.running.value && !XrayManager.connecting.value
        }
        XrayVpnService.waitUntilStopped(STOP_TIMEOUT_MS)
    }

    /** GoBackend держит VPN-слот даже когда WireGuard туннель уже DOWN. */
    private suspend fun releaseGoBackend(appCtx: Context) {
        withContext(Dispatchers.Main + NonCancellable) {
            runCatching {
                appCtx.stopService(Intent(appCtx, GoBackend.VpnService::class.java))
            }
        }
        waitUntil(2_000) { !WireGuardHelper.isVpnSlotInUse }
    }

    private fun isBypassActive(): Boolean =
        TunnelManager.running.value ||
            TunnelManager.tunnelReady.value ||
            WireGuardHelper.isVpnSlotInUse

    private suspend fun waitSlotFree() {
        waitUntil(STOP_TIMEOUT_MS) {
            !XrayVpnService.isSessionActive &&
                !XrayManager.running.value &&
                !XrayManager.connecting.value &&
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
