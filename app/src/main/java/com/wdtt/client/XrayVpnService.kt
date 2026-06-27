package com.wdtt.client

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.StrictMode
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import go.Seq
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.atomic.AtomicBoolean

class XrayVpnService : VpnService() {

    companion object {
        private const val TAG = "XrayVpnService"
        private const val NOTIF_CHANNEL = "bypassme_xray_vpn"
        private const val NOTIF_ID = 1002
        private const val VPN_ADDRESS = "10.10.0.2"
        private const val VPN_PREFIX = 30

        const val ACTION_START = "START_XRAY"
        const val ACTION_STOP  = "STOP_XRAY"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_CONFIG_JSON = "config_json"

        const val BROADCAST_RUNNING = "com.bypassme.VPN_RUNNING"
        const val BROADCAST_STOPPED = "com.bypassme.VPN_STOPPED"
        const val BROADCAST_ERROR   = "com.bypassme.VPN_ERROR"
        const val EXTRA_ERROR_MSG   = "error_msg"
        private const val PKG = "com.bypassme.app"

        fun start(context: Context, serverId: String, configJson: String) {
            context.startForegroundService(
                Intent(context, XrayVpnService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_SERVER_ID, serverId)
                    putExtra(EXTRA_CONFIG_JSON, configJson)
                }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, XrayVpnService::class.java).apply { action = ACTION_STOP }
            )
        }

        private val coreInitialized = AtomicBoolean(false)

        private fun initCoreEnv(context: Context) {
            if (coreInitialized.compareAndSet(false, true)) {
                try {
                    Seq.setContext(context.applicationContext)
                    copyAssetIfNeeded(context, "geoip.db")
                    val rawId = try {
                        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
                    } catch (_: Exception) { "" }
                    val deviceId = rawId.padEnd(32, '0').substring(0, 32)
                    Libv2ray.initCoreEnv(context.filesDir.absolutePath, deviceId)
                    Log.i(TAG, "libv2ray initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "initCoreEnv failed: ${e.message}", e)
                    coreInitialized.set(false)
                    throw e
                }
            }
        }

        private fun copyAssetIfNeeded(context: Context, name: String) {
            val target = java.io.File(context.filesDir, name)
            if (target.exists() && target.length() > 0) return
            try {
                context.assets.open(name).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "asset copy skipped ($name): ${e.message}")
            }
        }
    }

    private lateinit var vpnInterface: ParcelFileDescriptor
    private var isRunning = false
    private var coreController: CoreController? = null
    private var hevTun2Socks: HevTun2Socks? = null
    private var coreThread: Thread? = null

    private val coreCallback = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long {
            sendBroadcast(Intent(BROADCAST_STOPPED).setPackage(PKG))
            return 0
        }
        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { setUnderlyingNetworks(arrayOf(network)) }
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) { setUnderlyingNetworks(arrayOf(network)) }
            override fun onLost(network: Network) { setUnderlyingNetworks(null) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: return START_NOT_STICKY
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
                showNotification()
                if (isRunning) stopAll()
                setupAndStart(serverId, configJson)
                START_STICKY
            }
            ACTION_STOP -> {
                stopAll()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun setupAndStart(serverId: String, configFromIntent: String?) {
        if (prepare(this) != null) {
            fail("VPN-разрешение не выдано")
            return
        }

        val configJson = configFromIntent?.takeIf { it.isNotBlank() }
            ?: runBlocking { buildConfigJson(serverId) }
        if (configJson.isNullOrBlank()) {
            fail("Не удалось собрать конфиг VPN")
            return
        }

        if (!configureVpn()) {
            fail("Не удалось создать VPN-интерфейс")
            return
        }

        startXrayCore(configJson)
    }

    private suspend fun buildConfigJson(serverId: String): String? {
        val store = SettingsStore(this)
        val uuid = store.vpnUuid.first().trim()
        if (uuid.isEmpty()) return null

        VpnServerManager.loadCached(this)
        var list = VpnServerManager.servers.value
        if (list.isEmpty()) {
            VpnServerManager.fetchServers(this)
            list = VpnServerManager.servers.value
        }
        val server = list.firstOrNull { it.id == serverId } ?: list.firstOrNull() ?: return null
        return XrayConfigBuilder.build(server, uuid)
    }

    private fun configureVpn(): Boolean {
        return try {
            val builder = Builder()
            builder.setMtu(1500)
            builder.addAddress(VPN_ADDRESS, VPN_PREFIX)
            builder.addRoute("0.0.0.0", 0)
            // Только IPv4 — иначе Telegram/YouTube могут уйти мимо туннеля по IPv6
            builder.allowFamily(OsConstants.AF_INET)
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("1.0.0.1")
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            builder.setSession("ByPassMe VPN")
            builder.setBlocking(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try { connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback) }
                catch (_: Exception) {}
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()!!
            isRunning = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "configureVpn failed: ${e.message}", e)
            false
        }
    }

    /**
     * v2rayNG: startLoop блокирует поток — запускаем xray в фоне, затем hev TUN → SOCKS.
     */
    private fun startXrayCore(configJson: String) {
        try {
            initCoreEnv(this)

            Libv2ray.useProtector(object : libv2ray.V2RayProtector {
                override fun protect(fd: Long): Boolean = protect(fd.toInt())
            })

            val ctrl = Libv2ray.newCoreController(coreCallback)
            coreController = ctrl

            coreThread = Thread({
                try {
                    ctrl.startLoop(configJson, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "startLoop ended: ${e.message}", e)
                }
            }, "xray-core-loop").also { it.start() }

            var waited = 0
            while (!ctrl.isRunning && waited < 100) {
                Thread.sleep(100)
                waited++
            }
            if (!ctrl.isRunning) {
                fail("xray не запустился (таймаут)")
                return
            }

            Thread({
                try {
                    hevTun2Socks = HevTun2Socks(this@XrayVpnService, vpnInterface).also { it.start() }
                } catch (e: Exception) {
                    Log.e(TAG, "hev start failed: ${e.message}", e)
                    fail("TUN туннель: ${e.message ?: "ошибка"}")
                }
            }, "hev-tun2socks").start()

            Thread.sleep(200)
            sendBroadcast(Intent(BROADCAST_RUNNING).setPackage(PKG))
            Log.i(TAG, "VPN tunnel started (hev-socks5 + xray)")
        } catch (e: Exception) {
            fail("xray ошибка: ${e.message}")
        }
    }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        sendBroadcast(Intent(BROADCAST_ERROR).setPackage(PKG).putExtra(EXTRA_ERROR_MSG, msg))
        stopAll()
    }

    private fun stopCore() {
        try { coreController?.let { if (it.isRunning) it.stopLoop() } } catch (_: Exception) {}
        coreController = null
        coreThread?.interrupt()
        coreThread = null
    }

    private fun stopAll() {
        isRunning = false

        try { hevTun2Socks?.stop() } catch (_: Exception) {}
        hevTun2Socks = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { connectivity.unregisterNetworkCallback(defaultNetworkCallback) } catch (_: Exception) {}
        }

        stopCore()
        sendBroadcast(Intent(BROADCAST_STOPPED).setPackage(PKG))

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        try { Thread.sleep(100) } catch (_: InterruptedException) {}

        if (::vpnInterface.isInitialized) {
            try { vpnInterface.close() } catch (_: Exception) {}
        }
    }

    private fun showNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "ByPassMe VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, XrayVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            NOTIF_ID,
            NotificationCompat.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("ByPassMe VPN")
                .setContentText("VPN подключён")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, "Отключить", stopIntent)
                .build()
        )
    }

    override fun onRevoke() = stopAll()

    override fun onDestroy() {
        if (isRunning) stopAll()
        super.onDestroy()
    }
}
