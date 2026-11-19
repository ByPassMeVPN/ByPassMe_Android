package com.wdtt.client

import android.content.Context
import android.os.ParcelFileDescriptor
import com.v2ray.ang.service.TProxyService
import java.io.File

/**
 * TUN fd → SOCKS5 127.0.0.1:10808 через libhev-socks5-tunnel (v2rayNG JNI).
 */
class HevTun2Socks(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val vpnAddress: String = VPN_ADDRESS,
    private val mtu: Int = MTU,
) {
    companion object {
        const val VPN_ADDRESS = "10.0.0.2"
        const val MTU = 1500
        private const val SOCKS_PORT = 10808
    }

    fun start() {
        val configFile = File(context.cacheDir, "tproxy.conf").apply {
            writeText(buildConfig())
        }
        TProxyService.TProxyStartService(configFile.absolutePath, vpnInterface.fd)
    }

    fun stop() {
        try { TProxyService.TProxyStopService() } catch (_: Exception) {}
    }

    private fun buildConfig(): String = buildString {
        appendLine("misc:")
        appendLine("  task-stack-size: 81920")
        appendLine("tunnel:")
        appendLine("  mtu: $mtu")
        appendLine("  ipv4: $vpnAddress")
        appendLine("socks5:")
        appendLine("  port: $SOCKS_PORT")
        appendLine("  address: '127.0.0.1'")
        appendLine("  udp: 'udp'")
    }
}
