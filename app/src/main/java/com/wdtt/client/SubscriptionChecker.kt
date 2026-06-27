package com.wdtt.client

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Подписка через /meta/{sub_key}
 *
 * GET https://sub.bypassme.online/meta/{key}
 * Headers: X-HWID: {android_id}, X-Device-Name: {Make Model (Android X)}
 *
 * 200 → {"type":"combo","days_left":30,"uuid":"...","wdtt_password":"..."}
 * 403 → {"error":"device_limit"} | device_blocked | device_removed
 * 404 → подписка не найдена
 */
object SubscriptionChecker {

    private const val BASE = "https://sub.bypassme.online"
    private const val MIRROR = "http://178.154.245.221/sub"

    /** "active" | "expired" | "unknown" */
    val status   = MutableStateFlow("unknown")
    val daysLeft = MutableStateFlow(0)

    sealed class Result {
        object Success       : Result()
        object Revoked       : Result()
        object DeviceLimitExceeded : Result()
        object DeviceBlocked : Result()
        object DeviceRemoved : Result()
        data class Error(val msg: String) : Result()
    }

    fun extractSubKey(input: String): String {
        val trimmed = input.trim()
        if (!trimmed.startsWith("http")) return trimmed
        return try {
            val segments = android.net.Uri.parse(trimmed).pathSegments
            segments.lastOrNull { it.isNotEmpty() } ?: trimmed
        } catch (_: Exception) { trimmed }
    }

    suspend fun fetch(context: Context, url: String, reconnect: Boolean = false): Result = withContext(Dispatchers.IO) {
        val subKey     = extractSubKey(url)
        val androidId  = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"

        var lastError = "Нет связи с сервером"
        for (base in listOf(BASE, MIRROR)) {
            val metaUrl = "$base/meta/$subKey"
            try {
                val conn = URL(metaUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("X-HWID", androidId)
                conn.setRequestProperty("X-Device-Name", deviceName)
                conn.setRequestProperty("User-Agent", "ByPassMe/2.0 Android/${Build.VERSION.RELEASE}")
                if (reconnect) {
                    conn.setRequestProperty("X-Device-Reconnect", "1")
                }
                conn.connectTimeout = 5_000
                conn.readTimeout    = 5_000
                conn.instanceFollowRedirects = true

                val code = conn.responseCode
                val body = runCatching { conn.inputStream.bufferedReader().readText() }
                    .getOrElse { runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull() ?: "" }
                conn.disconnect()

                when {
                    code == 403 -> {
                        val error = runCatching { JSONObject(body).optString("error", "") }.getOrDefault("")
                        return@withContext when (error) {
                            "device_limit" -> Result.DeviceLimitExceeded
                            "device_blocked" -> {
                                revokeAccess(context, "blocked")
                                Result.DeviceBlocked
                            }
                            "device_removed" -> {
                                revokeAccess(context, "removed")
                                Result.DeviceRemoved
                            }
                            else -> Result.Error("Ошибка доступа (403)")
                        }
                    }
                    code == 404 -> {
                        revokeAccess(context, "expired")
                        Result.Revoked
                    }
                    code == 200 && body.isNotEmpty() -> return@withContext parseAndSave(context, url, body)
                    else -> lastError = "Ошибка сервера: $code"
                }
            } catch (_: Exception) {
                lastError = "Нет связи с сервером"
            }
        }
        Result.Error(lastError)
    }

    private suspend fun parseAndSave(context: Context, url: String, body: String): Result {
        return try {
            val json     = JSONObject(body)
            val daysL    = json.optInt("days_left", 0)
            val uuid     = json.optString("uuid", "")
            val wdttPass = json.optString("wdtt_password", "")
            val type     = json.optString("type", "unknown")

            val newStatus = when {
                daysL == 0 && type != "unknown" -> "expired"
                type == "unknown"               -> "unknown"
                else                            -> "active"
            }

            if (newStatus == "expired") {
                revokeAccess(context, "expired")
                return Result.Revoked
            }

            val expireAt = System.currentTimeMillis() + daysL * 86_400_000L

            val store = SettingsStore(context)
            store.saveVpnCredentialsFull(
                uuid     = uuid,
                url      = url,
                status   = newStatus,
                daysLeft = daysL,
                expireAt = expireAt
            )
            store.clearRevokeReason()
            if (wdttPass.isNotEmpty()) {
                store.saveConnectionPassword(wdttPass)
            }

            status.value   = newStatus
            daysLeft.value = daysL

            Result.Success
        } catch (e: Exception) {
            Result.Error("Ошибка разбора ответа: ${e.message}")
        }
    }

    /** Загрузить кеш с DataStore моментально (без сети) */
    suspend fun loadCached(context: Context) {
        val store    = SettingsStore(context)
        val uuid     = store.vpnUuid.first()
        if (uuid.isBlank()) return

        val expireAt = store.vpnExpireAt.first()
        val cached   = store.vpnStatusString.first()
        val days     = store.vpnDaysLeft.first()

        if (expireAt > 0L) {
            val computed = ((expireAt - System.currentTimeMillis()) / 86_400_000L).toInt().coerceAtLeast(0)
            daysLeft.value = computed
            if (computed == 0 && cached == "active") {
                revokeAccess(context, "expired")
                return
            }
            status.value = cached
        } else {
            status.value   = cached
            daysLeft.value = days
            if (cached == "expired") {
                revokeAccess(context, "expired")
            }
        }
    }

    /**
     * Полный отзыв: стоп обхода + VPN, очистка подписки, возврат на онбординг.
     */
    suspend fun revokeAccess(context: Context, reason: String) {
        stopAllTunnels(context)
        SettingsStore(context).revokeVpnAccess(reason)
        BypassServerManager.clear()
        VpnServerManager.clear()
        resetRuntimeState()
    }

    private fun stopAllTunnels(context: Context) {
        XrayVpnService.stop(context)
        context.startService(
            Intent(context, TunnelService::class.java).apply { action = "STOP" }
        )
        XrayManager.running.value = false
        TunnelManager.running.value = false
        TunnelManager.tunnelReady.value = false
    }

    private fun resetRuntimeState() {
        status.value = "unknown"
        daysLeft.value = 0
        XrayManager.running.value = false
        XrayManager.lastError.value = ""
    }

    /** Фоновая проверка подписки (каждые 5 сек из MainActivity). */
    suspend fun refreshSubscription(context: Context) {
        val url = SettingsStore(context).vpnSubscriptionUrl.first()
        if (url.isEmpty()) return
        fetch(context, url)
    }

    fun refreshInBackground(context: Context, scope: CoroutineScope) {
        scope.launch { refreshSubscription(context) }
    }
}

/** Специальное исключение при превышении лимита устройств */
class DeviceLimitException : Exception("Лимит устройств превышен")
