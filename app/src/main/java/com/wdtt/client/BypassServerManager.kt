package com.wdtt.client

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class BypassServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 56000
) {
    val peer: String get() = "$host:$port"
}

/**
 * Список bypass-серверов (Обход Б/С) с API и локальным кэшем.
 *
 * GET https://sub.bypassme.online/bypass/servers/{subKey}
 * GET http://178.154.245.221/sub/bypass/servers/{subKey}
 *
 * Ответ:
 * { "servers": [ { "id": "nl", "name": "🇳🇱 Нидерланды", "host": "1.2.3.4", "port": 56000 } ] }
 */
object BypassServerManager {
    private const val BASE_URL   = "https://sub.bypassme.online"
    private const val MIRROR_URL = "http://178.154.245.221/sub"

    val servers = MutableStateFlow<List<BypassServer>>(emptyList())

    private fun parseServersArray(arr: JSONArray): List<BypassServer> =
        (0 until arr.length()).mapNotNull { i ->
            try {
                val s = arr.getJSONObject(i)
                val host = s.optString("host", "").trim()
                if (host.isEmpty()) return@mapNotNull null
                BypassServer(
                    id   = s.optString("id", host),
                    name = s.optString("name", host),
                    host = host,
                    port = s.optInt("port", 56000)
                )
            } catch (_: Exception) {
                null
            }
        }

    suspend fun loadCached(context: Context) {
        if (servers.value.isNotEmpty()) return
        val json = SettingsStore(context).bypassServersJson.first()
        if (json.isEmpty()) return
        try {
            servers.value = parseServersArray(JSONArray(json))
        } catch (_: Exception) {}
    }

    suspend fun fetchServers(context: Context): FetchResult = withContext(Dispatchers.IO) {
        val store = SettingsStore(context)
        val subKey = store.vpnSubKey.first().ifEmpty {
            val url = store.vpnSubscriptionUrl.first()
            if (url.isEmpty()) return@withContext FetchResult.NoSubscription
            SubscriptionChecker.extractSubKey(url).also { key ->
                if (key.isNotEmpty()) store.saveVpnSubKey(key)
            }
        }
        if (subKey.isEmpty()) return@withContext FetchResult.NoSubscription

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"

        var lastCode = 0
        for (base in listOf(BASE_URL, MIRROR_URL)) {
            try {
                val conn = URL("$base/bypass/servers/$subKey").openConnection() as HttpURLConnection
                conn.setRequestProperty("X-HWID", androidId)
                conn.setRequestProperty("X-Device-Name", deviceName)
                conn.setRequestProperty("User-Agent", "ByPassMe/2.0 Android/${Build.VERSION.RELEASE}")
                conn.connectTimeout = 10_000
                conn.readTimeout    = 10_000
                conn.instanceFollowRedirects = true

                lastCode = conn.responseCode
                if (lastCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val arr = JSONObject(body).getJSONArray("servers")
                    val list = parseServersArray(arr)
                    if (list.isNotEmpty()) {
                        servers.value = list
                        store.saveBypassServersJson(arr.toString())
                        return@withContext FetchResult.Success
                    }
                    return@withContext FetchResult.EmptyList
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
        when (lastCode) {
            403 -> FetchResult.NoAccess
            404 -> FetchResult.NotFound
            else -> FetchResult.NetworkError
        }
    }

    sealed class FetchResult {
        object Success : FetchResult()
        object NoSubscription : FetchResult()
        object NoAccess : FetchResult()
        object NotFound : FetchResult()
        object EmptyList : FetchResult()
        object NetworkError : FetchResult()
    }

    fun refreshInBackground(context: Context, scope: CoroutineScope) {
        scope.launch {
            val store = SettingsStore(context)
            val url = store.vpnSubscriptionUrl.first()
            val subKey = store.vpnSubKey.first()
            if (url.isEmpty() && subKey.isEmpty()) return@launch
            fetchServers(context)
        }
    }

    fun clear() {
        servers.value = emptyList()
    }
}
