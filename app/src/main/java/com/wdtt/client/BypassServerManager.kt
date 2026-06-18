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

data class BypassServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 56000
) {
    val peer: String get() = "$host:$port"
}

/**
 * Список bypass-серверов (Обход Б/С) — только hub.mos.ru + локальный кэш.
 *
 * GET https://hub.mos.ru/api/v4/projects/.../repository/files/bypass-servers.json/raw
 */
object BypassServerManager {

    private const val HUB_API_BASE = "https://hub.mos.ru/api/v4/projects"
    private const val HUB_PROJECT  = "dzonsonandrej706%2Fdzonson"
    private const val HUB_FILE     = "bypass-servers.json"
    private const val HUB_BRANCH   = "main"

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

    private suspend fun loadDiskCache(context: Context): Boolean {
        if (servers.value.isNotEmpty()) return true
        val json = SettingsStore(context).bypassServersJson.first()
        if (json.isEmpty()) return false
        return try {
            val list = parseServersArray(JSONArray(json))
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
            val arr = JSONObject(body).getJSONArray("servers")
            val list = parseServersArray(arr)
            if (list.isEmpty()) return false
            servers.value = list
            store.saveBypassServersJson(arr.toString())
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
        object NoSubscription : FetchResult()
        object NoAccess : FetchResult()
        object NotFound : FetchResult()
        object EmptyList : FetchResult()
        object NetworkError : FetchResult()
    }

    fun refreshInBackground(context: Context, scope: CoroutineScope) {
        scope.launch { fetchServers(context) }
    }

    fun clear() {
        servers.value = emptyList()
    }

    fun defaultServerIndex(list: List<BypassServer>): Int {
        val nl = list.indexOfFirst { it.id == "nl" }
        return if (nl >= 0) nl else 0
    }
}
