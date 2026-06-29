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

    private const val STOP_TIMEOUT_MS = 8_000L
    private const val POLL_MS = 100L

    /** Перед запуском Xray VPN — гарантированно освободить VPN-слот. */
    suspend fun ensureForXray(context: Context) = handoffMutex.withLock {
        val appCtx = context.applicationContext
        withContext(Dispatchers.IO + NonCancellable) {
            if (TunnelManager.running.value || TunnelManager.tunnelReady.value) {
                stopBypassTransport(appCtx)
            }
            releaseWireGuardSlot(appCtx)
            stopActiveXray(appCtx)
            waitVpnSlotFree()
        }
    }

    suspend fun prepareForBypass(context: Context) = handoffMutex.withLock {
        val appCtx = context.applicationContext
        withContext(Dispatchers.IO + NonCancellable) {
            stopActiveXray(appCtx)
            stopBypassTransport(appCtx)
            waitVpnSlotFree()
        }
    }

    private suspend fun stopBypassTransport(appCtx: Context) {
        TunnelManager.stopAndWait()
        appCtx.startService(
            Intent(appCtx, TunnelService::class.java).apply { action = "STOP" }
        )
        waitUntil(STOP_TIMEOUT_MS) {
            !TunnelManager.running.value &&
                !TunnelManager.tunnelReady.value
        }
        releaseWireGuardSlot(appCtx)
    }

    /** Только если Xray реально держит сессию — не трогаем «connecting» без TUN. */
    private suspend fun stopActiveXray(appCtx: Context) {
        if (!XrayVpnService.isSessionActive && !XrayManager.running.value) return
        XrayManager.connecting.value = false
        XrayVpnService.stop(appCtx)
        waitUntil(STOP_TIMEOUT_MS) {
            !XrayManager.running.value && !XrayVpnService.isSessionActive
        }
        XrayVpnService.waitUntilStopped(STOP_TIMEOUT_MS)
    }

    /** WireGuard DOWN + stop GoBackend.VpnService — иначе слот остаётся занят. */
    private suspend fun releaseWireGuardSlot(appCtx: Context) {
        withContext(Dispatchers.Main + NonCancellable) {
            WireGuardHelper(appCtx).releaseVpnCompletely()
            runCatching {
                appCtx.stopService(Intent(appCtx, GoBackend.VpnService::class.java))
            }
        }
        waitUntil(STOP_TIMEOUT_MS) { !WireGuardHelper.isVpnSlotInUse }
    }

    private suspend fun waitVpnSlotFree() {
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
