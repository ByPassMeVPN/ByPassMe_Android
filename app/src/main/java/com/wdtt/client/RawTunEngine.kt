package com.wdtt.client

import android.content.Context
import android.net.VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Управляет TUN службы Обхода ([TunnelService.rawTun]): поднимает его по RAWCONF
 * и передаёт fd в go_client.
 * При мягком перезапуске go_client (смена сети, сторож) TUN переиспользуется —
 * значок VPN не мигает и приложения не теряют сеть.
 */
object RawTunEngine {
    private val mutex = Mutex()

    private var activeIp = ""
    private var activeDnsCsv = ""
    private var activeMtu = 0
    @Volatile private var reuseTunOnNextStart = false

    suspend fun start(context: Context, ip: String, dnsCsv: String, mtu: Int, sockName: String) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                if (VpnService.prepare(app) != null) error("VPN-разрешение не выдано")

                // TUN держит сама служба Обхода — она и запускает TunnelManager.
                val vpn = withTimeoutOrNull(8_000) {
                    while (TunnelService.instance == null) delay(50)
                    TunnelService.instance?.rawTun
                } ?: error("служба Обхода не запущена")

                val existing = vpn.currentTunFd()
                val canReuse = reuseTunOnNextStart &&
                    existing?.fileDescriptor?.valid() == true &&
                    activeIp == ip && activeDnsCsv == dnsCsv && activeMtu == mtu
                reuseTunOnNextStart = false

                val pfd = if (canReuse) {
                    requireNotNull(existing)
                } else {
                    vpn.establish(ip, dnsCsv, mtu).also {
                        activeIp = ip
                        activeDnsCsv = dnsCsv
                        activeMtu = mtu
                    }
                }

                TunnelService.instance?.applyUnderlyingNetwork()
                try {
                    TunFdBridge.sendOnce(sockName, pfd)
                } catch (e: Exception) {
                    clearActive()
                    vpn.closeTun()
                    throw e
                }
            }
        }

    /** Перед перезапуском только go_client: TUN оставить, если RAWCONF совпадёт. */
    fun keepTunForRestart() {
        reuseTunOnNextStart = TunnelService.instance?.rawTun?.currentTunFd()?.fileDescriptor?.valid() == true
    }

    suspend fun stop(context: Context) = mutex.withLock {
        withContext(Dispatchers.IO) {
            reuseTunOnNextStart = false
            clearActive()
            // Саму службу не останавливаем: это служба Обхода, она завершится сама.
            TunnelService.instance?.rawTun?.closeTun()
        }
    }

    fun onVpnRevoked() {
        reuseTunOnNextStart = false
        clearActive()
        TunnelService.instance?.rawTun?.closeTun()
    }

    private fun clearActive() {
        activeIp = ""
        activeDnsCsv = ""
        activeMtu = 0
    }
}
