package com.wdtt.client

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.hiddify.core.libbox.Notification
import com.hiddify.core.libbox.TunOptions
import com.wdtt.client.vpn.VpnCoreSettings
import com.wdtt.client.vpn.bg.BoxService
import com.wdtt.client.vpn.bg.PlatformInterfaceWrapper
import com.wdtt.client.vpn.ktx.toIpPrefix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** VPN через sing-box (hiddify-core) — TUN открывается ядром через openTun(). */
class SingBoxVpnService : VpnService(), PlatformInterfaceWrapper {

    companion object {
        private const val TAG = "SingBoxVpnService"
        const val NOTIF_CHANNEL = "bypassme_singbox_vpn"
        const val NOTIF_ID = 1002

        const val ACTION_START = "START_SINGBOX"
        const val ACTION_STOP = "STOP_SINGBOX"
        const val EXTRA_CONFIG_JSON = "config_json"

        const val BROADCAST_RUNNING = "com.bypassme.VPN_RUNNING"
        const val BROADCAST_STOPPED = "com.bypassme.VPN_STOPPED"
        const val BROADCAST_ERROR = "com.bypassme.VPN_ERROR"
        const val EXTRA_ERROR_MSG = "error_msg"

        fun start(context: android.content.Context, configJson: String) {
            context.startForegroundService(
                Intent(context, SingBoxVpnService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_CONFIG_JSON, configJson)
                }
            )
        }

        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, SingBoxVpnService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    private val boxService = BoxService(this, this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification("Подключение…")
                val json = intent.getStringExtra(EXTRA_CONFIG_JSON)
                if (json.isNullOrBlank()) {
                    sendError("Пустой конфиг VPN")
                    return START_NOT_STICKY
                }
                if (prepare(this) != null) {
                    sendError("VPN-разрешение не выдано")
                    return START_NOT_STICKY
                }
                try {
                    val configFile = java.io.File(filesDir, "vpn/active.json")
                    configFile.parentFile?.mkdirs()
                    configFile.writeText(json)
                    VpnCoreSettings.prepareForStart(configFile.absolutePath)
                } catch (e: Exception) {
                    sendError("Не удалось сохранить конфиг: ${e.message}")
                    return START_NOT_STICKY
                }
                boxService.onStartCommand()
            }
            ACTION_STOP -> {
                boxService.stopService()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        runBlocking { withContext(Dispatchers.Main) { boxService.onRevoke() } }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val builder = Builder()
            .setSession("ByPassMe VPN")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val inet4 = options.inet4Address
        while (inet4.hasNext()) {
            val a = inet4.next()
            builder.addAddress(a.address(), a.prefix())
        }

        if (options.autoRoute) {
            builder.addDnsServer(options.dnsServerAddress.value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val v4 = options.inet4RouteAddress
                if (v4.hasNext()) {
                    while (v4.hasNext()) builder.addRoute(v4.next().toIpPrefix())
                } else {
                    builder.addRoute("0.0.0.0", 0)
                }
            } else {
                val v4 = options.inet4RouteRange
                if (v4.hasNext()) {
                    while (v4.hasNext()) {
                        val a = v4.next()
                        builder.addRoute(a.address(), a.prefix())
                    }
                } else {
                    builder.addRoute("0.0.0.0", 0)
                }
            }
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        }

        val pfd = builder.establish() ?: error("android: vpn establish failed")
        boxService.fileDescriptor = pfd
        Log.i(TAG, "TUN opened fd=${pfd.fd}")
        return pfd.fd
    }

    override fun sendNotification(notification: Notification) {}

    private fun sendError(msg: String) {
        sendBroadcast(
            Intent(BROADCAST_ERROR).setPackage(packageName).putExtra(EXTRA_ERROR_MSG, msg)
        )
        stopSelf()
    }

    private fun startForegroundNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    NOTIF_CHANNEL, "ByPassMe VPN", android.app.NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val stopIntent = android.app.PendingIntent.getService(
            this, 0,
            Intent(this, SingBoxVpnService::class.java).apply { action = ACTION_STOP },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            NOTIF_ID,
            androidx.core.app.NotificationCompat.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("ByPassMe VPN")
                .setContentText(text)
                .setOngoing(true)
                .addAction(0, "Отключить", stopIntent)
                .build()
        )
    }
}
