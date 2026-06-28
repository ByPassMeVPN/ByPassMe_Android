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
 * Список VPN-серверов — удалённый конфиг.
 */
object VpnServerManager {

    private const val REPO_FILES_API = "https://hub.mos.ru/api/v4/projects"
    private const val REPO_PROJECT  = "dzonsonandrej706%2Fdzonson"
    private const val SERVERS_FILE  = "vpn-servers.json"
    private const val REPO_BRANCH   = "main"

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
