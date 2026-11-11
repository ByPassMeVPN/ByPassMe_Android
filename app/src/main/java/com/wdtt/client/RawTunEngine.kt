package com.wdtt.client

import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Управляет [RawTunVpnService]: поднимает TUN по RAWCONF и передаёт fd в go_client.
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

                if (RawTunVpnService.instance == null) {
                    app.startService(Intent(app, RawTunVpnService::class.java))
                }
                val vpn = withTimeoutOrNull(8_000) {
                    while (RawTunVpnService.instance == null) delay(50)
                    RawTunVpnService.instance
                } ?: error("служба VPN не запустилась")

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
        reuseTunOnNextStart = RawTunVpnService.instance?.currentTunFd()?.fileDescriptor?.valid() == true
    }

    suspend fun stop(context: Context) = mutex.withLock {
        withContext(Dispatchers.IO) {
            reuseTunOnNextStart = false
            clearActive()
            RawTunVpnService.instance?.closeTun()
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, RawTunVpnService::class.java)
                )
            }
        }
    }

    fun onVpnRevoked() {
        reuseTunOnNextStart = false
        clearActive()
        RawTunVpnService.instance?.closeTun()
    }

    private fun clearActive() {
        activeIp = ""
        activeDnsCsv = ""
        activeMtu = 0
    }
}
