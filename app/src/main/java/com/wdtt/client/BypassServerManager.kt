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
    /** WireGuard-порт — для старых приложений (iOS, Windows, Android до 1.6.24). */
    val port: Int = 56000,
    /** Порт Обхода без WireGuard (-listen-raw). Необязательное поле списка. */
    val rawPort: Int = SettingsStore.BYPASS_RAW_PORT,
    /** Запасные входные адреса страны (alt_hosts, необязательное поле). */
    val altHosts: List<String> = emptyList(),
    /** Сервер понимает «голосовой» RTP-заголовок (voice, необязательное поле). */
    val voice: Boolean = false,
) {
    val peer: String get() = "$host:$port"
    val rawPeer: String get() = "$host:$rawPort"
}

/**
 * Bypass-серверы: приложение загружает только с hub.mos.ru (bypass-servers.json).
 * Локальные файлы в hub/ — для редактирования и ручной загрузки на hub.
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
                    port = s.optInt("port", 56000),
                    rawPort = s.optInt("rawPort", SettingsStore.BYPASS_RAW_PORT),
                    altHosts = s.optJSONArray("alt_hosts")?.let { a ->
                        (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() && it != host }
                    } ?: emptyList(),
                    voice = s.optBoolean("voice", false)
                )
            } catch (_: Exception) {
                null
            }
        }

    /**
     * -peer для Go-клиента: основной адрес и запасные (alt_hosts) через
     * запятую — клиент сам переключится, если основной не отвечает.
     */
    fun peerArgFor(peer: String): String {
        val host = peer.substringBeforeLast(":")
        val port = peer.substringAfterLast(":", "")
        val server = servers.value.firstOrNull { it.host == host } ?: return peer
        if (server.altHosts.isEmpty() || port.isEmpty()) return peer
        return (listOf(peer) + server.altHosts.map { "$it:$port" }).joinToString(",")
    }

    /** Сервер с этим адресом понимает «голосовой» заголовок (voice в списке). */
    fun voiceFor(peer: String): Boolean {
        val host = peer.substringBefore(",").substringBeforeLast(":")
        return servers.value.firstOrNull { it.host == host }?.voice == true
    }

    private fun applyServersJson(body: String): Boolean {
        return try {
            val arr = JSONObject(body).getJSONArray("servers")
            val list = parseServersArray(arr)
            if (list.isEmpty()) return false
            servers.value = list
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun loadCached(context: Context) = withContext(Dispatchers.IO) {
        if (servers.value.isNotEmpty()) return@withContext
        val cached = SettingsStore(context).bypassServersJson.first()
        if (cached.isNotBlank()) applyServersJson(cached)
    }

    private suspend fun fetchFromHub(context: Context): FetchResult {
        val token = HubToken.mos
        if (token.isBlank()) return FetchResult.NoAccess
        return try {
            val conn = URL(
                "$REPO_FILES_API/$REPO_PROJECT/repository/files/$SERVERS_FILE/raw?ref=$REPO_BRANCH"
            ).openConnection() as HttpURLConnection
            conn.setRequestProperty("PRIVATE-TOKEN", token)
            conn.setRequestProperty("User-Agent", "ByPassMe/2.0 Android")
            conn.connectTimeout = 8_000
            conn.readTimeout    = 8_000
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                conn.disconnect()
                return FetchResult.NoAccess
            }
            if (code == 404) {
                conn.disconnect()
                return FetchResult.NotFound
            }
            if (code != 200) {
                conn.disconnect()
                return FetchResult.NetworkError
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            if (!applyServersJson(body)) return FetchResult.EmptyList
            SettingsStore(context).saveBypassServersJson(body)
            FetchResult.Success
        } catch (_: Exception) {
            FetchResult.NetworkError
        }
    }

    suspend fun fetchServers(context: Context): FetchResult = withContext(Dispatchers.IO) {
        when (val result = fetchFromHub(context)) {
            FetchResult.Success -> result
            else -> {
                if (servers.value.isEmpty()) loadCached(context)
                if (servers.value.isNotEmpty()) FetchResult.Success else result
            }
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
