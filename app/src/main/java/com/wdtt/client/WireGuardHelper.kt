package com.wdtt.client

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class WireGuardHelper(context: Context) {
    private val appContext = context.applicationContext
    private val backend = (appContext as WdttApplication).getBackend(context)

    companion object {
        val wgMutex = Mutex()
        var sharedTunnel: WgTunnel? = null

        /** WireGuard TUN активен (GoBackend держит VPN-слот). */
        val isVpnSlotInUse: Boolean
            get() = sharedTunnel != null

        // Белый список РФ — эти приложения всегда идут напрямую (без туннеля).
        // Они доступны при ограничениях мобильного интернета, туннель им не нужен.
        val RUSSIA_WHITELIST_PACKAGES = setOf(
            // Государственные
            "ru.gosuslugi.goskey",           // Госуслуги
            "ru.pochta.app",                 // Почта России
            "ru.nalog.www",                  // ФНС
            // Банки
            "ru.sberbankmobile",             // Сбер
            "ru.sber.smartonline",           // СберОнлайн
            "ru.vtb24.mobilebanking.android",// ВТБ
            "ru.alfabank.mobile.android",    // Альфа-банк
            "ru.mtsbank.mtsapp",             // МТС Банк
            "ru.tinkoff.banking",            // Т-банк (Тинькофф)
            "ru.psbank.mobile",              // ПСБ
            "ru.nspk.mirpay",               // Мир Pay
            // Яндекс экосистема
            "com.yandex.browser",            // Яндекс Браузер
            "ru.yandex.searchplugin",        // Яндекс
            "ru.yandex.yandexmaps",          // Яндекс Карты
            "ru.yandex.taxi",                // Яндекс Go / Такси
            "ru.yandex.music",               // Яндекс Музыка
            "com.yandex.kinopoisk",          // Кинопоиск
            "ru.yandex.market",              // Яндекс Маркет
            "ru.yandex.zen",                 // Дзен
            "ru.yandex.video",               // VK Видео / Яндекс Видео
            // Соцсети и почта
            "ru.ok.android",                 // Одноклассники
            "ru.mail.mailapp",               // Mail.ru
            // Маркетплейсы и доставка
            "ru.ozon.app.android",           // Озон
            "com.wildberries.ru",            // Wildberries
            "ru.avito.android.avito",        // Авито
            "ru.cdek.app",                   // СДЭК
            "ru.samokat.android",            // Самокат
            "ru.kuper.app",                  // Купер
            // Транспорт
            "ru.rzd.passenger",              // РЖД
            "ru.dublgis.dgismobile",         // 2ГИС
            "ru.aeroflot.purchase",          // Аэрофлот
            "ru.tutu.android",               // Туту.ру
            // Операторы связи
            "ru.beeline.beeline",            // Билайн
            "ru.megafon.selfcare.mf",        // Мегафон
            "ru.mts.mtsapp",                 // МТС
            "ru.tele2.mytele2",              // Tele2
            "ru.rt.app",                     // Ростелеком
            // Видео / кино
            "ru.ivi",                        // Иви
            "ru.okko.okko",                  // Окко
            "ru.kinopoisk.android",          // Кинопоиск (альт. пакет)
            "ru.rutube.app",                 // Rutube
            // Работа
            "ru.hh.android",                 // HeadHunter
            // Еда / рестораны
            "ru.burger.king",                // Бургер Кинг
            "com.mcdonalds.mobileapp"        // МакДональдс (Вкусно и точка)
        )
    }

    class WgTunnel : Tunnel {
        override fun getName() = "Обход Б/С"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    suspend fun startTunnel(configString: String) = wgMutex.withLock {
        startTunnelLocked(configString)
    }

    private suspend fun startTunnelLocked(configString: String) = withContext(Dispatchers.IO) {
        try {
            if (VpnService.prepare(appContext) != null) {
                throw IllegalStateException("VPN-разрешение не выдано")
            }

            // Краткая проверка: Xray уже остановлен?
            for (attempt in 0 until 15) {
                if (!XrayVpnService.isSessionActive) break
                delay(100)
            }

            ensureGoBackendServiceStarted()

            sharedTunnel?.let { existingTunnel ->
                try {
                    backend.setState(existingTunnel, Tunnel.State.DOWN, null)
                } catch (e: Exception) {
                    Log.w("WG", "Failed to stop previous tunnel before restart: ${e.readableMessage()}")
                }
                sharedTunnel = null
            }

            val parsedConfig = Config.parse(ByteArrayInputStream(configString.toByteArray(Charsets.UTF_8)))

            val builder = Interface.Builder()
                .parseAddresses(parsedConfig.`interface`.addresses.joinToString(", ") { it.toString() })
            
            if (parsedConfig.`interface`.dnsServers.isNotEmpty()) {
                builder.parseDnsServers(parsedConfig.`interface`.dnsServers.joinToString(", ") { it.hostAddress ?: "" })
            }
            if (parsedConfig.`interface`.listenPort.isPresent) {
                builder.parseListenPort(parsedConfig.`interface`.listenPort.get().toString())
            }
            if (parsedConfig.`interface`.mtu.isPresent) {
                val serverMtu = parsedConfig.`interface`.mtu.get()
                // Используем серверное значение, но не менее 1280 для мобильных сетей
                builder.parseMtu(serverMtu.coerceAtLeast(1280).toString())
            } else {
                builder.parseMtu("1280")
            }
            builder.parsePrivateKey(parsedConfig.`interface`.keyPair.privateKey.toBase64())

            // 1. Пакеты, которые всегда исключаются (наше приложение, ВК)
            // 2. Получаю настройки пользователя
            val settingsStore = SettingsStore(appContext)
            val savedExcluded = settingsStore.excludedApps.first()
            
            val userSelected = savedExcluded.split(",").filter { it.isNotEmpty() }.toSet()

            // В обоих режимах (ЧС и БС) мы технически используем Blacklist (Checked = Excluded),
            // так как пользователю удобнее логика "снимите галочку, чтобы приложение пошло в туннель".
            // Разница только в описании и начальном состоянии списка (пустой/полный).
            val excluded = mutableSetOf(appContext.packageName, "com.vkontakte.android", "com.vk.calls")
            excluded.addAll(RUSSIA_WHITELIST_PACKAGES)
            excluded.addAll(userSelected)
            val installedExcluded = excluded.filter { it.isInstalledPackage() }.toSet()
            if (installedExcluded.isNotEmpty()) {
                builder.excludeApplications(installedExcluded)
            }

            val newInterface = builder.build()

            val peerBuilder = Peer.Builder()
            val firstPeer = parsedConfig.peers.firstOrNull()
                ?: throw IllegalStateException("WireGuard config has no peer")
            firstPeer.let { peer ->
                peerBuilder.parsePublicKey(peer.publicKey.toBase64())
                if (peer.preSharedKey.isPresent) peerBuilder.parsePreSharedKey(peer.preSharedKey.get().toBase64())
                if (peer.endpoint.isPresent) peerBuilder.parseEndpoint(peer.endpoint.get().toString())
                if (peer.persistentKeepalive.isPresent) peerBuilder.parsePersistentKeepalive(peer.persistentKeepalive.get().toString())
            }
            // Override AllowedIPs
            peerBuilder.parseAllowedIPs("0.0.0.0/0")
            
            val finalConfig = Config.Builder()
                .setInterface(newInterface)
                .addPeer(peerBuilder.build())
                .build()

            val nextTunnel = WgTunnel()
            setTunnelUpWithRetry(nextTunnel, finalConfig)
            sharedTunnel = nextTunnel
            Log.d("WG", "WireGuard tunnel started successfully")
        } catch (e: Exception) {
            val detailed = "WireGuard start failed: ${e.readableMessage()}; ${configString.describeWireGuardConfig()}"
            Log.e("WG", detailed)
            e.printStackTrace()
            throw IllegalStateException(detailed, e)
        }
    }

    suspend fun reloadTunnel() = wgMutex.withLock {
        withContext(Dispatchers.IO) {
            val currentTunnel = sharedTunnel ?: return@withContext
            try {
                val configFlow = TunnelManager.config.first() ?: return@withContext
                backend.setState(currentTunnel, Tunnel.State.DOWN, null)
                sharedTunnel = null
                delay(150)
                startTunnelLocked(configFlow)
                Log.d("WG", "WireGuard tunnel reloaded for new exceptions")
            } catch (e: Exception) {
                Log.e("WG", "Failed to reload WireGuard: ${e.readableMessage()}")
            }
        }
    }

    suspend fun stopTunnel() = wgMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                sharedTunnel?.let {
                    backend.setState(it, Tunnel.State.DOWN, null)
                    sharedTunnel = null
                    Log.d("WG", "WireGuard tunnel stopped")
                }
            } catch (e: Exception) {
                Log.e("WG", "Failed to stop WireGuard: ${e.readableMessage()}")
            }
        }
    }

    /** Полностью освобождает VPN-слот WireGuard / GoBackend. */
    suspend fun releaseVpnCompletely() {
        stopTunnel()
        stopGoBackendService()
    }

    private suspend fun stopGoBackendService() {
        withContext(Dispatchers.Main) {
            runCatching {
                appContext.stopService(Intent(appContext, GoBackend.VpnService::class.java))
            }.onFailure {
                Log.w("WG", "GoBackend stop failed: ${it.readableMessage()}")
            }
        }
    }

    private suspend fun ensureGoBackendServiceStarted() {
        withContext(Dispatchers.Main) {
            runCatching {
                appContext.startService(Intent(appContext, GoBackend.VpnService::class.java))
            }.onFailure {
                Log.w("WG", "GoBackend service warmup failed: ${it.readableMessage()}")
            }
        }
    }

    private suspend fun setTunnelUpWithRetry(nextTunnel: WgTunnel, finalConfig: Config) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                backend.setState(nextTunnel, Tunnel.State.UP, finalConfig)
                return
            } catch (e: Exception) {
                lastError = e
                Log.w("WG", "WireGuard UP attempt ${attempt + 1}/3 failed: ${e.readableMessage()}")
                runCatching { backend.setState(nextTunnel, Tunnel.State.DOWN, null) }
                ensureGoBackendServiceStarted()
                delay(100L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("WireGuard UP failed")
    }

    private fun Throwable.readableMessage(): String {
        val text = message ?: localizedMessage
        return if (text.isNullOrBlank()) this::class.java.simpleName else "${this::class.java.simpleName}: $text"
    }

    private fun String.isInstalledPackage(): Boolean {
        return runCatching {
            appContext.packageManager.getPackageInfo(this, 0)
            true
        }.getOrDefault(false)
    }

    private fun String.describeWireGuardConfig(): String {
        val lines = lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val hasInterface = lines.any { it.equals("[Interface]", ignoreCase = true) }
        val hasPeer = lines.any { it.equals("[Peer]", ignoreCase = true) }
        val hasPrivateKey = lines.any { it.startsWith("PrivateKey", ignoreCase = true) }
        val hasPublicKey = lines.any { it.startsWith("PublicKey", ignoreCase = true) }
        val hasAddress = lines.any { it.startsWith("Address", ignoreCase = true) }
        val endpoint = lines.firstOrNull { it.startsWith("Endpoint", ignoreCase = true) }
            ?.substringAfter("=", "")
            ?.trim()
            ?.take(80)
            ?: "none"
        return "config lines=${lines.size}, interface=$hasInterface, peer=$hasPeer, privateKey=$hasPrivateKey, publicKey=$hasPublicKey, address=$hasAddress, endpoint=$endpoint"
    }
}
