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

/**
 * Входной прокси перед сервером (SOCKS5 или HTTP CONNECT). Список, адреса и
 * логины/пароли меняются на hub.mos.ru — в приложении ничего не зашито.
 */
data class VpnProxy(
    val id: String,
    /** "socks" или "http". */
    val type: String,
    val host: String,
    val port: Int,
    val user: String = "",
    val pass: String = "",
)

data class VpnServerTemplate(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val network: String,
    val fingerprint: String,
    val outboundTag: String,
    val sni: String = "",
    val path: String = "",
    /** Свой Reality-ключ сервера (Россия); пусто — общий ключ. */
    val publicKey: String = "",
    val shortId: String = "",
    /** Подключаться к серверу через эти прокси (пусто — напрямую). */
    val proxies: List<VpnProxy> = emptyList(),
) {
    /** Hysteria (UDP/QUIC) через SOCKS/HTTP-прокси не ходит — для неё прокси не применяются. */
    val activeProxies: List<VpnProxy>
        get() = if (network.equals("hysteria", ignoreCase = true)) emptyList() else proxies

    /** Подпись под названием — как в Windows (VpnProtocolHelper) и Happ. */
    val protocolLabel: String
        get() = when (network.lowercase()) {
            "xhttp", "splithttp" -> "VLESS / XHTTP / TLS"
            "tcp" -> "VLESS / TCP / REALITY"
            "hysteria" -> "HYSTERIA / HYSTERIA / TLS"
            "grpc" -> "VLESS / GRPC / REALITY"
            else -> network.uppercase()
        }

    /** GAMING SERVER: Hy2 с игровыми QUIC-параметрами (как в подписке Happ). */
    val isGaming: Boolean
        get() = id.endsWith("-game") || name.contains("GAMING", ignoreCase = true)
}

/**
 * VPN-серверы: приложение загружает только с hub.mos.ru (vpn-servers.json).
 * Локальные файлы в hub/ — для редактирования и ручной загрузки на hub.
 */
object VpnServerManager {

    private const val REPO_FILES_API = "https://hub.mos.ru/api/v4/projects"
    private const val REPO_PROJECT  = "dzonsonandrej706%2Fdzonson"
    private const val SERVERS_FILE  = "vpn-servers.json"
    private const val REPO_BRANCH   = "main"

    val servers = MutableStateFlow<List<VpnServerTemplate>>(emptyList())

    /** Тип прокси из списка; неизвестный — null (такой прокси пропускается). */
    private fun proxyType(raw: String): String? = when (raw.trim().lowercase()) {
        "socks", "socks5", "socks5h" -> "socks"
        "http" -> "http"
        else -> null
    }

    private fun parseProxy(o: JSONObject, fallbackId: String): VpnProxy? {
        if (o.optBoolean("disabled", false)) return null
        val type = proxyType(o.optString("type", "socks")) ?: return null
        val host = o.optString("host", o.optString("address", "")).trim()
        val port = o.optInt("port", 0)
        if (host.isEmpty() || port !in 1..65535) return null
        return VpnProxy(
            id = o.optString("id", fallbackId).trim().ifEmpty { fallbackId },
            type = type,
            host = host,
            port = port,
            user = o.optString("user", o.optString("username", "")),
            pass = o.optString("pass", o.optString("password", "")),
        )
    }

    /** Общий список прокси: корневой "proxies" в vpn-servers.json. */
    private fun parseProxyPool(root: JSONObject): List<VpnProxy> {
        val arr = root.optJSONArray("proxies") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseProxy(it, "p$i") }
        }
    }

    /**
     * Поле "proxies" у сервера: "all" / true — все из общего списка; массив id —
     * только эти; в массиве можно описать прокси и целиком (объектом).
     * Нет поля — сервер напрямую, как раньше.
     */
    private fun serverProxies(s: JSONObject, pool: List<VpnProxy>): List<VpnProxy> {
        return when (val v = s.opt("proxies")) {
            null, JSONObject.NULL, false -> emptyList()
            true -> pool
            is String -> if (v.equals("all", ignoreCase = true)) pool else pool.filter { it.id == v }
            is JSONArray -> (0 until v.length()).flatMap { i ->
                when (val e = v.opt(i)) {
                    is String -> pool.filter { it.id == e }
                    is JSONObject -> listOfNotNull(parseProxy(e, "s$i"))
                    else -> emptyList()
                }
            }.distinctBy { "${it.type}|${it.host}|${it.port}|${it.user}" }
            else -> emptyList()
        }
    }

    private fun parseServersArray(arr: JSONArray, pool: List<VpnProxy>): List<VpnServerTemplate> =
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
                    sni         = s.optString("sni", "").trim(),
                    path        = s.optString("path", "").trim(),
                    publicKey   = s.optString("publicKey", "").trim(),
                    shortId     = s.optString("shortId", "").trim(),
                    proxies     = serverProxies(s, pool),
                )
            } catch (_: Exception) {
                null
            }
        }

    private fun applyServersJson(body: String): Boolean {
        return try {
            val root = JSONObject(body)
            val list = parseServersArray(root.getJSONArray("servers"), parseProxyPool(root))
            if (list.isEmpty()) return false
            servers.value = list
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun loadCached(context: Context) = withContext(Dispatchers.IO) {
        if (servers.value.isNotEmpty()) return@withContext
        // Только сохранённая копия последнего ответа hub.mos.ru — встроенного
        // в приложение списка нет (серверы задаются только в mos hub).
        val cached = SettingsStore(context).vpnServersJson.first()
        if (cached.isNotBlank()) applyServersJson(cached)
    }

    /**
     * Свежий список перед подключением к серверу с прокси (адреса/логины прокси
     * меняются на hub). Короткие таймауты: не дольше ~4 с, иначе остаётся кэш.
     */
    suspend fun refreshForConnect(context: Context) = withContext(Dispatchers.IO) {
        fetchFromHub(context, timeoutMs = 2_000)
    }

    private suspend fun fetchFromHub(context: Context, timeoutMs: Int = 8_000): FetchResult {
        val token = HubToken.mos
        if (token.isBlank()) return FetchResult.NoAccess
        return try {
            val conn = URL(
                "$REPO_FILES_API/$REPO_PROJECT/repository/files/$SERVERS_FILE/raw?ref=$REPO_BRANCH"
            ).openConnection() as HttpURLConnection
            conn.setRequestProperty("PRIVATE-TOKEN", token)
            conn.setRequestProperty("User-Agent", "ByPassMe/2.0 Android")
            conn.connectTimeout = timeoutMs
            conn.readTimeout    = timeoutMs
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
            SettingsStore(context).saveVpnServersJson(body)
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

    fun defaultServerIndex(list: List<VpnServerTemplate>): Int {
        val nl = list.indexOfFirst { it.id == "nl" }
        return if (nl >= 0) nl else 0
    }
}
