package com.wdtt.client

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    companion object {
        private val Context.dataStore by preferencesDataStore("settings")
        private val PEER = stringPreferencesKey("peer")
        private val VK_HASHES = stringPreferencesKey("vk_hashes")
        private val SECONDARY_VK_HASH = stringPreferencesKey("secondary_vk_hash")
        private val WORKERS_PER_HASH = intPreferencesKey("workers_per_hash")
        private val PROTOCOL = stringPreferencesKey("protocol")
        private val LISTEN_PORT = intPreferencesKey("listen_port")
        private val MANUAL_PORTS_ENABLED = booleanPreferencesKey("manual_ports_enabled")
        private val SERVER_DTLS_PORT = intPreferencesKey("server_dtls_port")
        private val SERVER_WG_PORT = intPreferencesKey("server_wg_port")
        private val SNI = stringPreferencesKey("sni")
        private val NO_DTLS = booleanPreferencesKey("no_dtls")
        private val NO_DNS = booleanPreferencesKey("no_dns")

        private val USER_AGENT = stringPreferencesKey("user_agent")

        private val DEPLOY_IP = stringPreferencesKey("deploy_ip")
        private val DEPLOY_LOGIN = stringPreferencesKey("deploy_login")
        private val DEPLOY_PASSWORD = stringPreferencesKey("deploy_password")
        private val DEPLOY_SSH_PORT = stringPreferencesKey("deploy_ssh_port")
        private val EXCLUDED_APPS = stringPreferencesKey("excluded_apps")
        
        private val DETAILED_LOGS = booleanPreferencesKey("detailed_logs")
        
        // ═══ Пароли и Управление ═══
        private val CONNECTION_PASSWORD = stringPreferencesKey("connection_password")
        private val DEPLOY_MAIN_PASSWORD = stringPreferencesKey("deploy_main_password")
        private val DEPLOY_ADMIN_ID = stringPreferencesKey("deploy_admin_id")
        private val DEPLOY_BOT_TOKEN = stringPreferencesKey("deploy_bot_token")

        // ═══ Proxy Mode ═══
        private val PROXY_MODE = stringPreferencesKey("proxy_mode") // "tun" or "socks5"
        private val PROXY_HOST = stringPreferencesKey("proxy_host")
        private val PROXY_PORT = intPreferencesKey("proxy_port")

        // ═══ Captcha Solve Mode ═══
        private val CAPTCHA_MODE = stringPreferencesKey("captcha_mode") // "webview" or "reverse_js"
        private val CAPTCHA_SOLVE_METHOD = stringPreferencesKey("captcha_solve_method") // "manual" or "auto"
        private val CAPTCHA_WBV_SOLVE_METHOD = stringPreferencesKey("captcha_wbv_solve_method") // "manual" or "auto"
        
        // ═══ VPN Exclusions Mode ═══
        private val IS_WHITELIST = booleanPreferencesKey("is_whitelist")

        // ═══ Bypass ═══
        private val BYPASS_SERVER_INDEX = intPreferencesKey("bypass_server_index")

        // ═══ Basic VPN ═══
        private val VPN_UUID = stringPreferencesKey("vpn_uuid")
        private val VPN_SERVER_INDEX = intPreferencesKey("vpn_server_index")
        private val VPN_SUBSCRIPTION_URL = stringPreferencesKey("vpn_subscription_url")
        private val VPN_SERVERS_JSON = stringPreferencesKey("vpn_servers_json")

        // ═══ Subscription status ═══
        private val VPN_EXPIRE_AT = longPreferencesKey("vpn_expire_at")           // Unix-ms
        private val VPN_STATUS_VALID = booleanPreferencesKey("vpn_status_valid")
        private val VPN_DEVICES_USED = intPreferencesKey("vpn_devices_used")
        private val VPN_DEVICES_LIMIT = intPreferencesKey("vpn_devices_limit")
        private val VPN_STATUS_LAST_CHECK = longPreferencesKey("vpn_status_last_check")
        private val VPN_STATUS_STRING = stringPreferencesKey("vpn_status_string")  // "active"|"expired"|"unknown"
        private val VPN_DAYS_LEFT = intPreferencesKey("vpn_days_left")
        private val VPN_REVOKE_REASON = stringPreferencesKey("vpn_revoke_reason") // "expired"|"blocked"|""

        // ═══ Theme Mode ═══
        private val THEME_MODE = stringPreferencesKey("theme_mode") // "system", "light", "dark"
        private val IS_DYNAMIC_COLOR = booleanPreferencesKey("is_dynamic_color")
        private val THEME_PALETTE = stringPreferencesKey("theme_palette")

        private val UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")
        private val UPDATE_LATEST_VERSION = stringPreferencesKey("update_latest_version")
        private val UPDATE_LAST_ERROR = stringPreferencesKey("update_last_error")
        private val UPDATE_CHECK_INTERVAL_HOURS = intPreferencesKey("update_check_interval_hours")
        private val UPDATE_POSTPONE_UNTIL = longPreferencesKey("update_postpone_until")
        private val UPDATE_POSTPONE_VERSION = stringPreferencesKey("update_postpone_version")
        private val UPDATE_DIALOG_LAST_SHOWN_VERSION = stringPreferencesKey("update_dialog_last_shown_version")
        private val UPDATE_DIALOG_LAST_SHOWN_AT = longPreferencesKey("update_dialog_last_shown_at")
        private val UPDATE_DIALOG_LAST_ACTION_VERSION = stringPreferencesKey("update_dialog_last_action_version")
        private val UPDATE_DIALOG_LAST_ACTION = stringPreferencesKey("update_dialog_last_action")
        private val UPDATE_DIALOG_LAST_ACTION_AT = longPreferencesKey("update_dialog_last_action_at")
    }

    private val dataStore = appContext.dataStore

    val peer: Flow<String> = dataStore.data.map { it[PEER] ?: "" }
    val vkHashes: Flow<String> = dataStore.data.map { it[VK_HASHES] ?: "" }
    val secondaryVkHash: Flow<String> = dataStore.data.map { it[SECONDARY_VK_HASH] ?: "" }
    val workersPerHash: Flow<Int> = dataStore.data.map { it[WORKERS_PER_HASH] ?: 16 }
    val protocol: Flow<String> = dataStore.data.map { it[PROTOCOL] ?: "udp" }
    val listenPort: Flow<Int> = dataStore.data.map { it[LISTEN_PORT] ?: 9000 }
    val manualPortsEnabled: Flow<Boolean> = dataStore.data.map { it[MANUAL_PORTS_ENABLED] ?: false }
    val serverDtlsPort: Flow<Int> = dataStore.data.map { it[SERVER_DTLS_PORT] ?: 56000 }
    val serverWgPort: Flow<Int> = dataStore.data.map { it[SERVER_WG_PORT] ?: 56001 }
    val sni: Flow<String> = dataStore.data.map { it[SNI] ?: "" }
    val noDns: Flow<Boolean> = dataStore.data.map { it[NO_DNS] ?: false }
    val userAgent: Flow<String> = dataStore.data.map { it[USER_AGENT] ?: "" }

    val deployIp: Flow<String> = dataStore.data.map { it[DEPLOY_IP] ?: "" }
    val deployLogin: Flow<String> = dataStore.data.map { it[DEPLOY_LOGIN] ?: "" }
    val deployPassword: Flow<String> = dataStore.data.map { it[DEPLOY_PASSWORD] ?: "" }
    val deploySshPort: Flow<String> = dataStore.data.map { it[DEPLOY_SSH_PORT] ?: "" }
    val excludedApps: Flow<String> = dataStore.data.map { it[EXCLUDED_APPS] ?: "" }
    
    val detailedLogs: Flow<Boolean> = dataStore.data.map { it[DETAILED_LOGS] ?: false }
    
    // ═══ Пароли и Управление ═══
    val connectionPassword: Flow<String> = dataStore.data.map { it[CONNECTION_PASSWORD] ?: "" }
    val deployMainPassword: Flow<String> = dataStore.data.map { it[DEPLOY_MAIN_PASSWORD] ?: "" }
    val deployAdminId: Flow<String> = dataStore.data.map { it[DEPLOY_ADMIN_ID] ?: "" }
    val deployBotToken: Flow<String> = dataStore.data.map { it[DEPLOY_BOT_TOKEN] ?: "" }

    // ═══ Proxy Mode ═══
    val proxyMode: Flow<String> = dataStore.data.map { it[PROXY_MODE] ?: "tun" }
    val proxyHost: Flow<String> = dataStore.data.map { it[PROXY_HOST] ?: "127.0.0.1" }
    val proxyPort: Flow<Int> = dataStore.data.map { it[PROXY_PORT] ?: 1080 }

    // ═══ Captcha Solve Mode ═══
    val captchaMode: Flow<String> = dataStore.data.map { it[CAPTCHA_MODE] ?: "wv" }
    val captchaSolveMethod: Flow<String> = dataStore.data.map { it[CAPTCHA_SOLVE_METHOD] ?: "manual" }
    val captchaWbvSolveMethod: Flow<String> = dataStore.data.map { it[CAPTCHA_WBV_SOLVE_METHOD] ?: "manual" }

    // ═══ Bypass ═══
    val bypassServerIndex: Flow<Int> = dataStore.data.map { it[BYPASS_SERVER_INDEX] ?: 0 }

    // ═══ Basic VPN ═══
    val vpnUuid: Flow<String> = dataStore.data.map { it[VPN_UUID] ?: "" }
    val vpnServerIndex: Flow<Int> = dataStore.data.map { it[VPN_SERVER_INDEX] ?: 0 }
    val vpnSubscriptionUrl: Flow<String> = dataStore.data.map { it[VPN_SUBSCRIPTION_URL] ?: "" }
    val vpnServersJson: Flow<String> = dataStore.data.map { it[VPN_SERVERS_JSON] ?: "" }

    // ═══ Subscription status ═══
    val vpnExpireAt: Flow<Long> = dataStore.data.map { it[VPN_EXPIRE_AT] ?: 0L }
    val vpnStatusValid: Flow<Boolean> = dataStore.data.map { it[VPN_STATUS_VALID] ?: true }
    val vpnDevicesUsed: Flow<Int> = dataStore.data.map { it[VPN_DEVICES_USED] ?: -1 }
    val vpnDevicesLimit: Flow<Int> = dataStore.data.map { it[VPN_DEVICES_LIMIT] ?: -1 }
    val vpnStatusLastCheck: Flow<Long> = dataStore.data.map { it[VPN_STATUS_LAST_CHECK] ?: 0L }
    val vpnStatusString: Flow<String> = dataStore.data.map { it[VPN_STATUS_STRING] ?: "unknown" }
    val vpnDaysLeft: Flow<Int> = dataStore.data.map { it[VPN_DAYS_LEFT] ?: 0 }
    val vpnRevokeReason: Flow<String> = dataStore.data.map { it[VPN_REVOKE_REASON] ?: "" }

    // ═══ VPN Exclusions Mode ═══
    val isWhitelist: Flow<Boolean> = dataStore.data.map { it[IS_WHITELIST] ?: false }

    // ═══ Theme Mode ═══
    val themeMode: Flow<String> = dataStore.data.map { it[THEME_MODE] ?: "system" }
    val isDynamicColor: Flow<Boolean> = dataStore.data.map { it[IS_DYNAMIC_COLOR] ?: false }
    val themePalette: Flow<String> = dataStore.data.map { it[THEME_PALETTE] ?: "indigo" }

    val updateLastCheckAt: Flow<Long> = dataStore.data.map { it[UPDATE_LAST_CHECK_AT] ?: 0L }
    val updateLatestVersion: Flow<String> = dataStore.data.map { it[UPDATE_LATEST_VERSION] ?: "" }
    val updateLastError: Flow<String> = dataStore.data.map { it[UPDATE_LAST_ERROR] ?: "" }
    val updateCheckIntervalHours: Flow<Int> = dataStore.data.map { it[UPDATE_CHECK_INTERVAL_HOURS] ?: DEFAULT_UPDATE_CHECK_INTERVAL_HOURS }
    val updatePostponeUntil: Flow<Long> = dataStore.data.map { it[UPDATE_POSTPONE_UNTIL] ?: 0L }
    val updatePostponeVersion: Flow<String> = dataStore.data.map { it[UPDATE_POSTPONE_VERSION] ?: "" }
    val updateDialogLastShownVersion: Flow<String> = dataStore.data.map { it[UPDATE_DIALOG_LAST_SHOWN_VERSION] ?: "" }
    val updateDialogLastShownAt: Flow<Long> = dataStore.data.map { it[UPDATE_DIALOG_LAST_SHOWN_AT] ?: 0L }
    val updateDialogLastActionVersion: Flow<String> = dataStore.data.map { it[UPDATE_DIALOG_LAST_ACTION_VERSION] ?: "" }
    val updateDialogLastAction: Flow<String> = dataStore.data.map { it[UPDATE_DIALOG_LAST_ACTION] ?: "" }
    val updateDialogLastActionAt: Flow<Long> = dataStore.data.map { it[UPDATE_DIALOG_LAST_ACTION_AT] ?: 0L }

    suspend fun saveThemeMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[THEME_MODE] = mode
        }
    }

    suspend fun saveDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[IS_DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun saveThemePalette(palette: String) {
        dataStore.edit { prefs ->
            prefs[THEME_PALETTE] = palette
        }
    }

    suspend fun saveUpdateState(lastCheckAt: Long, latestVersion: String, error: String) {
        dataStore.edit { prefs ->
            prefs[UPDATE_LAST_CHECK_AT] = lastCheckAt
            prefs[UPDATE_LATEST_VERSION] = latestVersion
            prefs[UPDATE_LAST_ERROR] = error
        }
    }

    suspend fun saveUpdateCheckIntervalHours(hours: Int) {
        dataStore.edit { prefs ->
            prefs[UPDATE_CHECK_INTERVAL_HOURS] = hours
        }
    }

    suspend fun saveUpdatePostpone(version: String, until: Long) {
        dataStore.edit { prefs ->
            prefs[UPDATE_POSTPONE_VERSION] = version
            prefs[UPDATE_POSTPONE_UNTIL] = until
        }
    }

    suspend fun saveUpdateDialogShown(version: String, shownAt: Long) {
        dataStore.edit { prefs ->
            prefs[UPDATE_DIALOG_LAST_SHOWN_VERSION] = version
            prefs[UPDATE_DIALOG_LAST_SHOWN_AT] = shownAt
        }
    }

    suspend fun saveUpdateDialogAction(version: String, action: String, actedAt: Long) {
        dataStore.edit { prefs ->
            prefs[UPDATE_DIALOG_LAST_ACTION_VERSION] = version
            prefs[UPDATE_DIALOG_LAST_ACTION] = action
            prefs[UPDATE_DIALOG_LAST_ACTION_AT] = actedAt
        }
    }

    suspend fun save(
        peer: String,
        vkHashes: String,
        secondaryVkHash: String,
        workersPerHash: Int,
        protocol: String,
        listenPort: Int,
        sni: String = "",
        noDns: Boolean = false
    ) {
        dataStore.edit { prefs ->
            prefs[PEER] = peer
            prefs[VK_HASHES] = vkHashes
            prefs[SECONDARY_VK_HASH] = secondaryVkHash
            prefs[WORKERS_PER_HASH] = workersPerHash
            prefs[PROTOCOL] = protocol
            prefs[LISTEN_PORT] = listenPort
            prefs[SNI] = sni
            prefs[NO_DNS] = noDns
        }
    }

    suspend fun saveManualPortsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[MANUAL_PORTS_ENABLED] = enabled
        }
    }

    suspend fun savePorts(serverDtlsPort: Int, serverWgPort: Int, listenPort: Int) {
        dataStore.edit { prefs ->
            prefs[SERVER_DTLS_PORT] = serverDtlsPort
            prefs[SERVER_WG_PORT] = serverWgPort
            prefs[LISTEN_PORT] = listenPort
        }
    }

    suspend fun saveUserAgent(ua: String) {
        dataStore.edit { prefs ->
            prefs[USER_AGENT] = ua
        }
    }

    suspend fun saveDeploy(ip: String, login: String, pass: String, sshPort: String) {
        dataStore.edit { prefs ->
            prefs[DEPLOY_IP] = ip
            prefs[DEPLOY_LOGIN] = login
            prefs[DEPLOY_PASSWORD] = pass
            prefs[DEPLOY_SSH_PORT] = sshPort
        }
    }

    suspend fun saveExcludedApps(packages: String) {
        dataStore.edit { prefs ->
            prefs[EXCLUDED_APPS] = packages
        }
    }
    
    suspend fun saveDetailedLogs(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[DETAILED_LOGS] = enabled
        }
    }
    
    // ═══ Сохранение пароля подключения ═══
    suspend fun saveConnectionPassword(password: String) {
        dataStore.edit { prefs ->
            prefs[CONNECTION_PASSWORD] = password
        }
    }
    
    // ═══ Сохранение секретов деплоя ═══
    suspend fun saveDeploySecrets(mainPass: String, adminId: String, botToken: String, sshPort: String) {
        dataStore.edit { prefs ->
            prefs[DEPLOY_MAIN_PASSWORD] = mainPass
            prefs[DEPLOY_ADMIN_ID] = adminId
            prefs[DEPLOY_BOT_TOKEN] = botToken
            prefs[DEPLOY_SSH_PORT] = sshPort
        }
    }

    // ═══ Сохранение proxy mode ═══
    suspend fun saveProxyMode(mode: String, host: String, port: Int) {
        dataStore.edit { prefs ->
            prefs[PROXY_MODE] = mode
            prefs[PROXY_HOST] = host
            prefs[PROXY_PORT] = port
        }
    }

    // ═══ Сохранение режима обхода капчи ═══
    suspend fun saveCaptchaMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[CAPTCHA_MODE] = mode
        }
    }

    suspend fun saveCaptchaSolveMethod(method: String) {
        dataStore.edit { prefs ->
            prefs[CAPTCHA_SOLVE_METHOD] = method
        }
    }

    suspend fun saveWbvCaptchaSolveMethod(method: String) {
        dataStore.edit { prefs ->
            prefs[CAPTCHA_WBV_SOLVE_METHOD] = method
            if (prefs[CAPTCHA_MODE] == "wv") {
                prefs[CAPTCHA_SOLVE_METHOD] = method
            }
        }
    }

    // ═══ Сохранение Basic VPN ═══
    suspend fun saveVpnUuid(uuid: String) {
        dataStore.edit { prefs -> prefs[VPN_UUID] = uuid }
    }

    suspend fun saveVpnServersJson(json: String) {
        dataStore.edit { prefs -> prefs[VPN_SERVERS_JSON] = json }
    }

    suspend fun saveBypassServerIndex(index: Int) {
        dataStore.edit { prefs -> prefs[BYPASS_SERVER_INDEX] = index }
    }

    suspend fun saveVpnServerIndex(index: Int) {
        dataStore.edit { prefs -> prefs[VPN_SERVER_INDEX] = index }
    }

    suspend fun saveVpnSubscriptionUrl(url: String) {
        dataStore.edit { prefs -> prefs[VPN_SUBSCRIPTION_URL] = url }
    }

    /** Сохранить результат проверки подписки */
    suspend fun saveSubscriptionStatus(status: SubscriptionStatus) {
        dataStore.edit { prefs ->
            if (status.expireAt > 0L) prefs[VPN_EXPIRE_AT] = status.expireAt
            prefs[VPN_STATUS_VALID] = status.isAccessible
            if (status.devicesUsed >= 0) prefs[VPN_DEVICES_USED] = status.devicesUsed
            if (status.devicesLimit > 0) prefs[VPN_DEVICES_LIMIT] = status.devicesLimit
            prefs[VPN_STATUS_LAST_CHECK] = System.currentTimeMillis()
        }
    }

    /** При первом успешном вводе — сбрасываем кешированный статус */
    suspend fun saveVpnCredentials(uuid: String, url: String, status: SubscriptionStatus) {
        dataStore.edit { prefs ->
            prefs[VPN_UUID] = uuid
            prefs[VPN_SUBSCRIPTION_URL] = url
            prefs[VPN_STATUS_VALID] = true
            if (status.expireAt > 0L) prefs[VPN_EXPIRE_AT] = status.expireAt
            if (status.devicesUsed >= 0) prefs[VPN_DEVICES_USED] = status.devicesUsed
            if (status.devicesLimit > 0) prefs[VPN_DEVICES_LIMIT] = status.devicesLimit
            prefs[VPN_STATUS_LAST_CHECK] = System.currentTimeMillis()
        }
    }

    /** Полное сохранение после /meta/ запроса */
    suspend fun saveVpnCredentialsFull(
        uuid: String, url: String, status: String, daysLeft: Int, expireAt: Long
    ) {
        dataStore.edit { prefs ->
            prefs[VPN_UUID]             = uuid
            prefs[VPN_SUBSCRIPTION_URL] = url
            prefs[VPN_STATUS_STRING]    = status
            prefs[VPN_DAYS_LEFT]        = daysLeft
            prefs[VPN_EXPIRE_AT]        = expireAt
            prefs[VPN_STATUS_VALID]     = status == "active"
            prefs[VPN_STATUS_LAST_CHECK] = System.currentTimeMillis()
        }
    }

    /** Очистить данные устройства (устройство удалено или подписка отозвана) */
    suspend fun revokeVpnAccess(reason: String = "") {
        dataStore.edit { prefs ->
            prefs.remove(VPN_UUID)
            prefs.remove(VPN_SUBSCRIPTION_URL)
            prefs[VPN_STATUS_VALID] = false
            prefs[VPN_STATUS_LAST_CHECK] = System.currentTimeMillis()
            if (reason.isNotEmpty()) prefs[VPN_REVOKE_REASON] = reason
        }
    }

    /** Сбросить причину отзыва после отображения сообщения */
    suspend fun clearRevokeReason() {
        dataStore.edit { prefs -> prefs.remove(VPN_REVOKE_REASON) }
    }

    // ═══ Сохранение режима списка (ЧС/БС) ═══
    suspend fun saveIsWhitelist(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[IS_WHITELIST] = enabled
        }
    }

    // Атомарное сохранение обоих параметров для исключения гонки при перезагрузке
    suspend fun saveExceptionsMode(packages: String, isWhitelist: Boolean) {
        dataStore.edit { prefs ->
            prefs[EXCLUDED_APPS] = packages
            prefs[IS_WHITELIST] = isWhitelist
        }
    }
}
