package com.wdtt.client

import android.content.Intent
import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * VpnService Обхода без WireGuard: TUN поднимается здесь и отдаётся напрямую
 * в go_client ([TunFdBridge]) — сырые IP-пакеты уходят через TURN VK на
 * сервер (-listen-raw), без второго слоя шифрования WireGuard.
 */
class RawTunVpnService : VpnService() {
    @Volatile
    private var tunFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    // Жизнью управляет TunnelManager/TunnelService — это только держатель TUN.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onRevoke() {
        Log.w(TAG, "raw VPN revoked")
        TunnelManager.onRawTunRevoked(applicationContext)
        stopSelf()
    }

    override fun onDestroy() {
        closeTun()
        instance = null
        super.onDestroy()
    }

    /** Поднимает TUN с параметрами из RAWCONF сервера. */
    fun establish(ip: String, dnsCsv: String, mtu: Int): ParcelFileDescriptor {
        if (prepare(this) != null) error("VPN-разрешение не выдано")

        val builder = Builder()
            .setSession("ByPassMe Обход")
            .setMtu(mtu)
            .addAddress(ip, 32)
            .setBlocking(false)

        dnsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { dns ->
            runCatching { builder.addDnsServer(dns) }
        }
        // Мимо туннеля: само приложение (его go_client ходит к TURN VK — иначе
        // петля), приложения VK (звонки) и российские приложения (как в WireGuard).
        val direct = listOf(packageName, "com.vkontakte.android", "com.vk.calls") +
            WireGuardHelper.RUSSIA_WHITELIST_PACKAGES
        for (pkg in direct) {
            if (isInstalled(pkg)) runCatching { builder.addDisallowedApplication(pkg) }
        }
        applyRoutes(builder)

        closeTun()
        val pfd = builder.establish() ?: error("Android не создал VPN-интерфейс")
        tunFd = pfd
        Log.i(TAG, "raw TUN up fd=${pfd.fd} ip=$ip mtu=$mtu")
        return pfd
    }

    /**
     * Всё в туннель, кроме: локальные сети, подсети VK и IP доменов из списка
     * «напрямую» ([BypassDirectDomains]). Точечное исключение IP — только с
     * Android 13 (excludeRoute); на старых — как раньше, без доменов.
     */
    private fun applyRoutes(builder: Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.addRoute("0.0.0.0", 0)
            for (cidr in EXCLUDED_CIDRS) {
                val (ip, len) = cidr.split('/')
                runCatching { builder.excludeRoute(IpPrefix(java.net.InetAddress.getByName(ip), len.toInt())) }
            }
            val ips = BypassDirectDomains.resolveAll()
            var added = 0
            for (ip in ips) {
                val r = runCatching {
                    builder.excludeRoute(IpPrefix(java.net.InetAddress.getByName(ip), 32))
                }
                if (r.isSuccess) added++
            }
            Log.i(TAG, "direct domains: excluded $added IPs")
            AppLogger.bypass("Напрямую: ${BypassDirectDomains.domains.size} доменов ($added IP)")
        } else {
            for ((address, prefix) in RouteMath.allExcept(EXCLUDED_CIDRS)) {
                builder.addRoute(address, prefix)
            }
            AppLogger.bypass("Android ниже 13: список доменов «напрямую» не применяется")
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun closeTun() {
        runCatching { tunFd?.close() }
        tunFd = null
    }

    fun currentTunFd(): ParcelFileDescriptor? = tunFd

    companion object {
        private const val TAG = "RawTunVpnService"

        /** Те же исключения, что go_client/split_tunnel.go для WireGuard. */
        private val EXCLUDED_CIDRS = listOf(
            "95.163.0.0/16",
            "87.240.0.0/16",
            "93.186.224.0/20",
            "185.32.248.0/22",
            "185.29.130.0/24",
            "217.20.144.0/20",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
        )

        @Volatile
        var instance: RawTunVpnService? = null
            private set
    }
}

/** IPv4-маршруты «весь интернет минус исключения» без excludeRoute (он только с API 33). */
internal object RouteMath {
    fun allExcept(cidrs: List<String>): List<Pair<String, Int>> {
        val excludes = cidrs.mapNotNull { parse(it) }
        val out = mutableListOf<Pair<String, Int>>()
        split(0L, 0, excludes, out)
        return out
    }

    private fun parse(cidr: String): Pair<Long, Int>? {
        val (ip, len) = cidr.split('/').takeIf { it.size == 2 } ?: return null
        val parts = ip.split('.').mapNotNull { it.toLongOrNull() }
        if (parts.size != 4) return null
        val prefix = len.toIntOrNull() ?: return null
        val value = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        return mask(value, prefix) to prefix
    }

    private fun mask(value: Long, prefix: Int): Long =
        if (prefix == 0) 0L else value and (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    private fun split(base: Long, prefix: Int, excludes: List<Pair<Long, Int>>, out: MutableList<Pair<String, Int>>) {
        val blockEnd = base + (1L shl (32 - prefix)) - 1
        var overlaps = false
        for ((exBase, exPrefix) in excludes) {
            val exEnd = exBase + (1L shl (32 - exPrefix)) - 1
            if (exBase <= base && exEnd >= blockEnd) return // блок целиком исключён
            if (exBase <= blockEnd && exEnd >= base) overlaps = true
        }
        if (!overlaps) {
            out += toIp(base) to prefix
            return
        }
        val half = 1L shl (31 - prefix)
        split(base, prefix + 1, excludes, out)
        split(base + half, prefix + 1, excludes, out)
    }

    private fun toIp(v: Long): String =
        "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
}
