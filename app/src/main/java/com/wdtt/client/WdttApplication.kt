package com.wdtt.client

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.content.getSystemService
import go.Seq

class WdttApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        instance = this
    }

    override fun onCreate() {
        super.onCreate()
        Seq.setContext(this)
        DeployManager.init(this)
        installCrashRecorder()
        reportPreviousCrash()
    }

    /**
     * Java-вылет → текст в files/last_crash.txt, при следующем запуске он
     * попадает во вкладку логов (пользователь может прислать скрин).
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "${java.util.Date()} [${thread.name}] ${android.util.Log.getStackTraceString(error)}"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun reportPreviousCrash() {
        // Только главный процесс: у :ping свой AppLogger, запись там потеряется.
        if (android.os.Build.VERSION.SDK_INT >= 28 && getProcessName() != packageName) return
        val file = java.io.File(filesDir, CRASH_FILE)
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrDefault("")
        file.delete()
        if (text.isNotBlank()) {
            AppLogger.serviceErr("Прошлый вылет приложения:\n" + text.lines().take(25).joinToString("\n"))
        }
    }

    @Volatile
    private var backendInstance: com.wireguard.android.backend.GoBackend? = null

    val backend: com.wireguard.android.backend.GoBackend
        get() = getBackend(this)

    fun getBackend(context: Context): com.wireguard.android.backend.GoBackend {
        return backendInstance ?: synchronized(this) {
            backendInstance ?: com.wireguard.android.backend.GoBackend(context.applicationContext)
                .also { backendInstance = it }
        }
    }

    companion object {
        private const val CRASH_FILE = "last_crash.txt"

        lateinit var instance: WdttApplication
            private set

        val notification: NotificationManager
            get() = instance.getSystemService()!!
        val connectivity: ConnectivityManager
            get() = instance.getSystemService()!!
        val packageManager
            get() = instance.packageManager
        val powerManager: PowerManager
            get() = instance.getSystemService()!!
        val wifiManager: WifiManager
            get() = instance.getSystemService()!!
    }
}
