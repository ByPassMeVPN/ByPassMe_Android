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
