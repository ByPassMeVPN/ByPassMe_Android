package com.wdtt.client

import android.content.Context
import org.json.JSONObject

/**
 * Намерение пользователя и последний режим — для восстановления туннеля
 * системой (липкая служба после убийства процесса, «Постоянный VPN»).
 * Без намерения не поднимаем: человек сам выключил — значит выключено.
 */
object BypassRestorePrefs {
    private const val PREFS = "bypass_restore"
    private const val KEY_WANTED = "bypass_wanted"
    private const val KEY_LAST_MODE = "last_mode"
    private const val KEY_PARAMS = "bypass_params"

    const val MODE_BYPASS = "bypass"
    const val MODE_VPN = "vpn"

    fun setWanted(context: Context, wanted: Boolean) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WANTED, wanted)
        if (wanted) edit.putString(KEY_LAST_MODE, MODE_BYPASS)
        edit.apply()
    }

    fun isWanted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)

    fun setLastModeVpn(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_MODE, MODE_VPN)
            .putBoolean(KEY_WANTED, false)
            .apply()
    }

    fun lastMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_MODE, MODE_VPN) ?: MODE_VPN

    /**
     * Параметры последнего запуска Обхода — для восстановления. Хранятся в
     * процессе :bg: DataStore интерфейса отсюда читать нельзя (в этом
     * процессе он кэшируется при первом чтении и не видит изменений).
     */
    fun saveParams(context: Context, p: TunnelParams) {
        val json = JSONObject()
            .put("peer", p.peer)
            .put("vkHashes", p.vkHashes)
            .put("secondaryVkHash", p.secondaryVkHash)
            .put("workersPerHash", p.workersPerHash)
            .put("port", p.port)
            .put("sni", p.sni)
            .put("connectionPassword", p.connectionPassword)
            .put("protocol", p.protocol)
            .put("captchaMode", p.captchaMode)
            .put("captchaSolveMethod", p.captchaSolveMethod)
            .put("vkAnonPath", p.vkAnonPath)
            .put("peerArg", p.peerArg)
        p.obfsVoice?.let { json.put("obfsVoice", it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PARAMS, json.toString())
            .apply()
    }

    fun loadParams(context: Context): TunnelParams? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PARAMS, null)
            ?: return null
        return runCatching {
            val j = JSONObject(raw)
            TunnelParams(
                peer = j.getString("peer"),
                vkHashes = j.getString("vkHashes"),
                secondaryVkHash = j.optString("secondaryVkHash"),
                workersPerHash = j.getInt("workersPerHash"),
                port = j.optInt("port", 9000),
                sni = j.optString("sni"),
                connectionPassword = j.optString("connectionPassword"),
                protocol = j.optString("protocol", "udp"),
                captchaMode = j.optString("captchaMode", "auto"),
                captchaSolveMethod = j.optString("captchaSolveMethod", "auto"),
                vkAnonPath = j.optString("vkAnonPath", "vkcalls"),
                peerArg = j.optString("peerArg"),
                obfsVoice = if (j.has("obfsVoice")) j.getBoolean("obfsVoice") else null,
            )
        }.getOrNull()?.takeIf { it.peer.isNotEmpty() && it.vkHashes.isNotEmpty() }
    }
}
