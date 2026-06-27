package com.wdtt.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

object XrayManager {
    val selectedIndex = MutableStateFlow(0)
    val running       = MutableStateFlow(false)
    val connecting    = MutableStateFlow(false)
    val lastError     = MutableStateFlow("")

    private var receiver: BroadcastReceiver? = null

    fun registerReceiver(context: Context) {
        if (receiver != null) return
        val appCtx = context.applicationContext
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    XrayVpnService.BROADCAST_RUNNING -> {
                        running.value = true
                        connecting.value = false
                        lastError.value = ""
                    }
                    XrayVpnService.BROADCAST_STOPPED -> {
                        running.value = false
                        connecting.value = false
                    }
                    XrayVpnService.BROADCAST_ERROR -> {
                        running.value = false
                        connecting.value = false
                        lastError.value = intent.getStringExtra(XrayVpnService.EXTRA_ERROR_MSG)
                            ?: "Неизвестная ошибка"
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(XrayVpnService.BROADCAST_RUNNING)
            addAction(XrayVpnService.BROADCAST_STOPPED)
            addAction(XrayVpnService.BROADCAST_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appCtx.registerReceiver(receiver, filter)
        }
    }

    suspend fun loadCached(context: Context) {
        VpnServerManager.loadCached(context)
        val store = SettingsStore(context)
        val savedIndex = store.vpnServerIndex.first()
        val list = VpnServerManager.servers.value
        selectedIndex.value = when {
            list.isEmpty() -> savedIndex
            savedIndex in list.indices -> savedIndex
            else -> VpnServerManager.defaultServerIndex(list)
        }
    }

    suspend fun fetchServers(context: Context): VpnServerManager.FetchResult = withContext(Dispatchers.IO) {
        val result = VpnServerManager.fetchServers(context)
        val list = VpnServerManager.servers.value
        if (list.isNotEmpty()) {
            val idx = selectedIndex.value
            if (idx !in list.indices) {
                selectedIndex.value = VpnServerManager.defaultServerIndex(list)
            }
        }
        result
    }

    suspend fun startVpn(context: Context) {
        val list = VpnServerManager.servers.value
        if (list.isEmpty()) {
            lastError.value = "Список серверов пуст"
            return
        }
        val uuid = SettingsStore(context).vpnUuid.first().trim()
        if (uuid.isEmpty()) {
            lastError.value = "UUID подписки не найден"
            return
        }

        val idx = selectedIndex.value.coerceIn(list.indices)
        SettingsStore(context).saveVpnServerIndex(idx)

        connecting.value = true
        ConnectionCoordinator.prepareForVpn(context)
        XrayVpnService.start(context, list[idx].id)
    }

    suspend fun stopVpn(context: Context) {
        connecting.value = false
        ConnectionCoordinator.stopVpn(context)
    }

    /** Переподключение на другой сервер без cooldown (VPN уже активен). */
    suspend fun switchServer(context: Context, serverIndex: Int) {
        val list = VpnServerManager.servers.value
        if (serverIndex !in list.indices) return

        selectedIndex.value = serverIndex
        SettingsStore(context).saveVpnServerIndex(serverIndex)

        if (!running.value && !connecting.value) return

        val uuid = SettingsStore(context).vpnUuid.first().trim()
        if (uuid.isEmpty()) {
            lastError.value = "UUID подписки не найден"
            return
        }

        connecting.value = true
        XrayVpnService.start(context, list[serverIndex].id)
    }
}
