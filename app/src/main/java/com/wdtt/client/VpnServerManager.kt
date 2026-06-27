package com.wdtt.client

import android.content.Context
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

data class VpnServerTemplate(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val network: String,
    val fingerprint: String,
    val outboundTag: String,
)

/**
 * Список VPN-серверов — hub.mos.ru + локальный кэш (как BypassServerManager).
 */
object VpnServerManager {

    private const val HUB_API_BASE = "https://hub.mos.ru/api/v4/projects"
    private const val HUB_PROJECT  = "dzonsonandrej706%2Fdzonson"
    private const val HUB_FILE     = "vpn-servers.json"
    private const val HUB_BRANCH   = "main"

    val servers = MutableStateFlow<List<VpnServerTemplate>>(emptyList())

    private fun parseServersArray(arr: JSONArray): List<VpnServerTemplate> =
        (0 until arr.length()).mapNotNull { i ->
            try {
                val s = arr.getJSONObject(i)
                val address = s.optString("address", "").trim()
                if (address.isEmpty()) return@mapNotNull null
                VpnServerTemplate(
                    id          = s.optString("id", address),
                    name        = s.optString("name", address),
                    address     = address,
                    port        = s.optInt("port", 2053),
                    network     = s.optString("network", "grpc"),
                    fingerprint = s.optString("fingerprint", "safari"),
                    outboundTag = s.optString("outboundTag", "proxy"),
                )
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun loadDiskCache(context: Context): Boolean {
        if (servers.value.isNotEmpty()) return true
        val json = SettingsStore(context).vpnServersJson.first()
        if (json.isEmpty()) return false
        return try {
            val arr = JSONObject(json).getJSONArray("servers")
            val list = parseServersArray(arr)
            if (list.isEmpty()) false
            else {
                servers.value = list
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun loadCached(context: Context) {
        loadDiskCache(context)
    }

    private suspend fun fetchFromHub(store: SettingsStore): Boolean {
        return try {
            val conn = URL(
                "$HUB_API_BASE/$HUB_PROJECT/repository/files/$HUB_FILE/raw?ref=$HUB_BRANCH"
            ).openConnection() as HttpURLConnection
            conn.setRequestProperty("PRIVATE-TOKEN", BuildConfig.HUB_MOS_TOKEN)
            conn.setRequestProperty("User-Agent", "ByPassMe/2.0 Android")
            conn.connectTimeout = 8_000
            conn.readTimeout    = 8_000
            if (conn.responseCode != 200) {
                conn.disconnect()
                return false
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val root = JSONObject(body)
            val arr = root.getJSONArray("servers")
            val list = parseServersArray(arr)
            if (list.isEmpty()) return false
            servers.value = list
            store.saveVpnServersJson(root.toString())
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchServers(context: Context): FetchResult = withContext(Dispatchers.IO) {
        val store = SettingsStore(context)
        if (fetchFromHub(store)) return@withContext FetchResult.Success
        if (loadDiskCache(context)) return@withContext FetchResult.Success
        FetchResult.NetworkError
    }

    sealed class FetchResult {
        object Success : FetchResult()
        object NetworkError : FetchResult()
    }

    fun refreshInBackground(context: Context, scope: CoroutineScope) {
        scope.launch { fetchServers(context) }
    }

    fun clear() {
        servers.value = emptyList()
    }

    fun defaultServerIndex(list: List<VpnServerTemplate>): Int {
        val nl = list.indexOfFirst { it.id == "nl" }
        return if (nl >= 0) nl else 0
    }
}
