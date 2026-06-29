package com.wdtt.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object XrayManager {
    /** Не привязан к Compose — переключение вкладок не отменяет подключение. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null

    val selectedIndex = MutableStateFlow(0)
    val running       = MutableStateFlow(false)
    val connecting    = MutableStateFlow(false)
    val lastError     = MutableStateFlow("")

    private var receiver: BroadcastReceiver? = null
    private var connectTimeoutJob: Job? = null

    fun startVpnAsync(context: Context) {
        val appCtx = context.applicationContext
        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                startVpn(appCtx)
            } catch (_: CancellationException) {
                connecting.value = false
            } catch (e: Exception) {
                connecting.value = false
                lastError.value = e.message ?: "Ошибка запуска VPN"
            }
        }
    }

    fun stopVpnAsync(context: Context) {
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            stopVpn(context.applicationContext)
        }
    }

    fun switchServerAsync(context: Context, serverIndex: Int) {
        val appCtx = context.applicationContext
        scope.launch {
            try {
                switchServer(appCtx, serverIndex)
            } catch (e: Exception) {
                connecting.value = false
                lastError.value = e.message ?: "Ошибка смены сервера"
            }
        }
    }

    fun registerReceiver(context: Context) {
        if (receiver != null) return
        val appCtx = context.applicationContext
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    XrayVpnService.BROADCAST_RUNNING -> {
                        cancelConnectTimeout()
                        running.value = true
                        connecting.value = false
                        lastError.value = ""
                    }
                    XrayVpnService.BROADCAST_STOPPED -> {
                        cancelConnectTimeout()
                        running.value = false
                        connecting.value = false
                    }
                    XrayVpnService.BROADCAST_ERROR -> {
                        cancelConnectTimeout()
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

    suspend fun restoreSelectedIndex(context: Context) {
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

    private suspend fun startVpn(context: Context) = withContext(Dispatchers.IO) {
        val list = VpnServerManager.servers.value
        if (list.isEmpty()) {
            lastError.value = "Список серверов пуст"
            return@withContext
        }
        val uuid = SettingsStore(context).vpnUuid.first().trim()
        if (uuid.isEmpty()) {
            lastError.value = "UUID подписки не найден"
            return@withContext
        }

        val idx = selectedIndex.value.coerceIn(list.indices)
        SettingsStore(context).saveVpnServerIndex(idx)

        val server = list[idx]
        val configJson = XrayConfigBuilder.build(server, uuid)

        connecting.value = true
        lastError.value = ""
        scheduleConnectTimeout(context)

        when {
            TunnelManager.running.value || TunnelManager.tunnelReady.value || WireGuardHelper.isVpnSlotInUse ->
                ConnectionCoordinator.prepareForVpn(context)
            XrayVpnService.isSessionActive || XrayManager.running.value -> {
                XrayVpnService.stop(context)
                XrayVpnService.waitUntilStopped(3_000)
            }
        }

        XrayVpnService.start(context, server.id, configJson)
    }

    private suspend fun stopVpn(context: Context) {
        cancelConnectTimeout()
        connecting.value = false
        XrayVpnService.stop(context)
    }

    private suspend fun switchServer(context: Context, serverIndex: Int) = withContext(Dispatchers.IO) {
        val list = VpnServerManager.servers.value
        if (serverIndex !in list.indices) return@withContext

        selectedIndex.value = serverIndex
        SettingsStore(context).saveVpnServerIndex(serverIndex)

        if (!running.value && !connecting.value) return@withContext

        val uuid = SettingsStore(context).vpnUuid.first().trim()
        if (uuid.isEmpty()) {
            lastError.value = "UUID подписки не найден"
            return@withContext
        }

        connecting.value = true
        scheduleConnectTimeout(context)
        val server = list[serverIndex]
        val configJson = XrayConfigBuilder.build(server, uuid)
        XrayVpnService.restart(context, server.id, configJson)
    }

    private fun scheduleConnectTimeout(context: Context) {
        cancelConnectTimeout()
        connectTimeoutJob = scope.launch {
            delay(25_000)
            if (connecting.value && !running.value) {
                connecting.value = false
                lastError.value = "Таймаут подключения VPN"
                XrayVpnService.stop(context.applicationContext)
            }
        }
    }

    private fun cancelConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
    }
}
