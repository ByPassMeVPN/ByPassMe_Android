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
        if (isTunnelProcess()) {
            // Процесс туннеля (:bg). WebView капчи живёт здесь, а в процессе
            // интерфейса — своя: с одной папкой данных на два процесса
            // Android завершает приложение с ошибкой.
            runCatching { android.webkit.WebView.setDataDirectorySuffix("bg") }
            TunnelBridge.startPublisher(this)
            AppLogger.init(this, tunnelProcess = true)
            return
        }
        if (isMainProcess()) {
            AppLogger.init(this)
            reportExitReasons()
            TunnelBridge.startMirror(this)
        }
        reportPreviousCrash()
    }

    private fun isMainProcess(): Boolean =
        android.os.Build.VERSION.SDK_INT < 28 || getProcessName() == packageName

    private fun isTunnelProcess(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 28 && getProcessName() == "$packageName:bg"

    /**
     * Почему Android завершил прошлые процессы (Android 11+): убила система
     * или прошивка, не хватило памяти, вылет, пользователь смахнул и т.д.
     * Главное для разбора «Обход отвалился, пока телефон лежал».
     */
    private fun reportExitReasons() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val prefs = getSharedPreferences("exit_reasons", MODE_PRIVATE)
            val lastSeen = prefs.getLong("last_ts", 0L)
            val list = am.getHistoricalProcessExitReasons(packageName, 0, 5)
                .filter { it.timestamp > lastSeen }
                .sortedBy { it.timestamp }
            if (list.isEmpty()) return
            prefs.edit().putLong("last_ts", list.maxOf { it.timestamp }).apply()
            val fmt = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.getDefault())
            for (info in list) {
                val proc = info.processName.removePrefix(packageName).ifEmpty { "главный" }
                val text = "Процесс ($proc) завершён ${fmt.format(java.util.Date(info.timestamp))}: " +
                    exitReasonText(info.reason) +
                    (info.description?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "") +
                    " [важность ${info.importance}]"
                val bad = info.reason != android.app.ApplicationExitInfo.REASON_USER_REQUESTED &&
                    info.reason != android.app.ApplicationExitInfo.REASON_EXIT_SELF
                if (bad) AppLogger.serviceErr(text) else AppLogger.service(text)
            }
        }
    }

    private fun exitReasonText(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "не хватило памяти"
        android.app.ApplicationExitInfo.REASON_CRASH -> "вылет (Java)"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "вылет (native)"
        android.app.ApplicationExitInfo.REASON_ANR -> "приложение зависло (ANR)"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "закрылось само"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "убито сигналом (часто прошивка/экономия батареи)"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "закрыто пользователем / остановлено в настройках"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "пользователь остановил приложение"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "система: слишком много ресурсов в фоне"
        android.app.ApplicationExitInfo.REASON_FREEZER -> "система: заморозка приложения"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "изменились разрешения"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "умерла зависимость"
        android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "обновление приложения"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "ошибка запуска"
        android.app.ApplicationExitInfo.REASON_OTHER -> "система (другое)"
        else -> "причина $reason"
    }

    /**
     * Java-вылет → текст в files/last_crash.txt, при следующем запуске он
     * попадает во вкладку логов (пользователь может прислать скрин).
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val proc = if (isTunnelProcess()) "туннель" else "интерфейс"
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "${java.util.Date()} [$proc, ${thread.name}] ${android.util.Log.getStackTraceString(error)}"
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
