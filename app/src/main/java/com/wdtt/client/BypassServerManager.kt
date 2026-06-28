package com.wdtt.client

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Список bypass-серверов (Обход Б/С) — удалённый конфиг.
 */
object BypassServerManager {

    private const val REPO_FILES_API = "https://hub.mos.ru/api/v4/projects"
    private const val REPO_PROJECT  = "dzonsonandrej706%2Fdzonson"
    private const val SERVERS_FILE  = "bypass-servers.json"
    private const val REPO_BRANCH   = "main"

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

    private suspend fun fetchFromHub(): Boolean {
        return try {
            val conn = URL(
                "$REPO_FILES_API/$REPO_PROJECT/repository/files/$SERVERS_FILE/raw?ref=$REPO_BRANCH"
            ).openConnection() as HttpURLConnection
            conn.setRequestProperty("PRIVATE-TOKEN", BuildConfig.REPO_ACCESS_TOKEN)
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
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchServers(context: Context): FetchResult = withContext(Dispatchers.IO) {
        if (fetchFromHub()) FetchResult.Success else FetchResult.NetworkError
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
