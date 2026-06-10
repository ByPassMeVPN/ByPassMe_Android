package com.wdtt.client

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Управляет libhev-socks5-tunnel — точно как TProxyService в v2rayNG.
 * Читает трафик из TUN fd и пробрасывает его в SOCKS5 на 127.0.0.1:SOCKS_PORT.
 */
class HevTun2Socks(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor
) {
    companion object {
        private const val SOCKS_PORT = 10808
        private const val VPN_ADDRESS = "10.10.0.1"
        private const val MTU = 1500

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
        val config = buildConfig()
        val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml").apply {
            writeText(config)
        }
        TProxyStartService(configFile.absolutePath, vpnInterface.fd)
    }

    fun stop() {
        try { TProxyStopService() } catch (_: Exception) {}
    }

    private fun buildConfig(): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: $MTU")
        appendLine("  ipv4: $VPN_ADDRESS")
        appendLine("socks5:")
        appendLine("  port: $SOCKS_PORT")
        appendLine("  address: 127.0.0.1")
        appendLine("  udp: 'udp'")
        appendLine("misc:")
        appendLine("  tcp-read-write-timeout: 300000")
        appendLine("  udp-read-write-timeout: 60000")
        appendLine("  log-level: warn")
    }
}
