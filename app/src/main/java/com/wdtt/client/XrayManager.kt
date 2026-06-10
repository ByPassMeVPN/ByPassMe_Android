package com.wdtt.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VpnServer(val id: String, val name: String, val configJson: String)

object XrayManager {
    private const val BASE_URL   = "https://sub.bypassme.online"
    private const val MIRROR_URL = "http://178.154.245.221/sub"

    val servers       = MutableStateFlow<List<VpnServer>>(emptyList())
    val selectedIndex = MutableStateFlow(0)
    val running       = MutableStateFlow(false)
    val lastError     = MutableStateFlow("")

    // BroadcastReceiver — слушает состояние из :xray_daemon процесса
    private var receiver: BroadcastReceiver? = null

    fun registerReceiver(context: Context) {
        if (receiver != null) return
        val appCtx = context.applicationContext
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    XrayVpnService.BROADCAST_RUNNING -> { running.value = true; lastError.value = "" }
                    XrayVpnService.BROADCAST_STOPPED -> running.value = false
                    XrayVpnService.BROADCAST_ERROR   -> {
                        running.value = false
                        lastError.value = intent.getStringExtra(XrayVpnService.EXTRA_ERROR_MSG) ?: "Неизвестная ошибка"
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(XrayVpnService.BROADCAST_RUNNING)
            addAction(XrayVpnService.BROADCAST_STOPPED)
            addAction(XrayVpnService.BROADCAST_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appCtx.registerReceiver(receiver, filter)
        }
    }

    fun startVpn(context: Context) {
        val list = servers.value
        if (list.isEmpty()) return
        val idx = selectedIndex.value.coerceIn(list.indices)
        XrayVpnService.start(context, list[idx].configJson)
    }

    fun stopVpn(context: Context) {
        XrayVpnService.stop(context)
    }

    suspend fun loadCached(context: Context) {
        val json = SettingsStore(context).vpnServersJson.first()
        if (json.isNotEmpty() && servers.value.isEmpty()) {
            try {
                val arr = JSONArray(json)
                servers.value = (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    VpnServer(s.getString("id"), s.getString("name"), s.getJSONObject("config").toString())
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun fetchServers(context: Context): Boolean = withContext(Dispatchers.IO) {
        val subUrl = SettingsStore(context).vpnSubscriptionUrl.first()
        if (subUrl.isEmpty()) return@withContext false
        val subKey = SubscriptionChecker.extractSubKey(subUrl)
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (_: Exception) { "unknown" }
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"

        for (base in listOf(BASE_URL, MIRROR_URL)) {
            try {
                val conn = URL("$base/vpn/servers/$subKey").openConnection() as HttpURLConnection
                conn.setRequestProperty("X-HWID", androidId)
                conn.setRequestProperty("X-Device-Name", deviceName)
                conn.setRequestProperty("User-Agent", "ByPassMe/2.0 Android/${Build.VERSION.RELEASE}")
                conn.connectTimeout = 10_000
                conn.readTimeout    = 10_000
                conn.instanceFollowRedirects = true

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val arr = JSONObject(body).getJSONArray("servers")
                    val list = (0 until arr.length()).map { i ->
                        val s = arr.getJSONObject(i)
                        VpnServer(
                            id         = s.getString("id"),
                            name       = s.getString("name"),
                            configJson = s.getJSONObject("config").toString()
                        )
                    }
                    servers.value = list
                    SettingsStore(context).saveVpnServersJson(arr.toString())
                    return@withContext true
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
        false
    }
}
