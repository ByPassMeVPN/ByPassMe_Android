package com.wdtt.client.vpn.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hiddify.core.libbox.Libbox
import com.hiddify.core.libbox.PlatformInterface
import com.hiddify.core.mobile.Mobile
import com.hiddify.core.mobile.SetupOptions
import com.wdtt.client.MainActivity
import com.wdtt.client.SingBoxVpnService
import com.wdtt.client.vpn.VpnCoreSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BoxService(
    private val service: Service,
    private val platformInterface: PlatformInterface,
) {
    companion object {
        private const val TAG = "BoxService"
        private var initializeOnce = false

        private fun initialize() {
            if (initializeOnce) return
            VpnCoreSettings.ensureDirs()
            Libbox.redirectStderr(File(VpnCoreSettings.workingDir, "stderr.log").absolutePath)
            initializeOnce = true
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    internal fun onStartCommand(): Int {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                startCore()
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                notifyError(e.message ?: "Ошибка VPN")
            }
        }
        return Service.START_STICKY
    }

    private suspend fun startCore() {
        initialize()
        val configPath = VpnCoreSettings.activeConfigPath
        if (configPath.isBlank() || !File(configPath).exists()) {
            notifyError("Конфиг VPN не найден")
            return
        }

        DefaultNetworkMonitor.start()
        Libbox.setMemoryLimit(!VpnCoreSettings.disableMemoryLimit)

        Mobile.setup(SetupOptions().also {
            it.basePath = VpnCoreSettings.baseDir
            it.workingDir = VpnCoreSettings.workingDir
            it.tempDir = VpnCoreSettings.tempDir
            it.fixAndroidStack = Bugs.fixAndroidStack
            it.mode = 4L
            it.listen = "127.0.0.1:${VpnCoreSettings.grpcServiceModePort}"
            it.secret = ""
            it.debug = VpnCoreSettings.debugMode
        }, platformInterface)

        if (VpnCoreSettings.startCoreAfterStartingService) {
            Mobile.start("", "")
        }

        withContext(Dispatchers.Main) {
            showNotification("VPN подключён")
            service.sendBroadcast(
                Intent(SingBoxVpnService.BROADCAST_RUNNING).setPackage(service.packageName)
            )
        }
    }

    fun stopService() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val pfd = fileDescriptor
                fileDescriptor = null
                pfd?.close()
            } catch (_: Exception) {}
            try { DefaultNetworkMonitor.stop() } catch (_: Exception) {}
            try { Mobile.stop() } catch (_: Exception) {}
            try { Mobile.close(4L) } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                service.sendBroadcast(
                    Intent(SingBoxVpnService.BROADCAST_STOPPED).setPackage(service.packageName)
                )
                service.stopSelf()
            }
        }
    }

    fun onRevoke() = stopService()

    private fun notifyError(msg: String) {
        GlobalScope.launch(Dispatchers.Main) {
            service.sendBroadcast(
                Intent(SingBoxVpnService.BROADCAST_ERROR)
                    .setPackage(service.packageName)
                    .putExtra(SingBoxVpnService.EXTRA_ERROR_MSG, msg)
            )
            stopService()
        }
    }

    private fun showNotification(text: String) {
        val nm = service.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = SingBoxVpnService.NOTIF_CHANNEL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "ByPassMe VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            service, 0,
            Intent(service, SingBoxVpnService::class.java).apply { action = SingBoxVpnService.ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            service, 1,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(service, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("ByPassMe VPN")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Отключить", stopIntent)
            .build()
        service.startForeground(SingBoxVpnService.NOTIF_ID, notification)
    }
}
