package com.wdtt.client

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * libhev-socks5-tunnel — как TProxyService в XrayFA / v2rayNG.
 * TUN fd → SOCKS5 127.0.0.1:10808 → xray.
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

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService()

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }

    fun start() {
        val configFile = File(context.cacheDir, "tproxy.conf").apply {
            writeText(buildConfig())
        }
        TProxyStartService(configFile.absolutePath, vpnInterface.fd)
    }

    fun stop() {
        try { TProxyStopService() } catch (_: Exception) {}
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
