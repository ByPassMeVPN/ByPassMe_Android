package com.wdtt.client

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Обход: эти домены — напрямую, всё остальное — в туннель (список из
 * конфига «Обход Б/С»). В raw-TUN нет ядра с разбором доменов, поэтому
 * домены резолвятся при подключении и их IP исключаются из маршрутов TUN.
 *
 * Резолвим и системным DNS, и через 1.1.1.1: после подъёма туннеля приложения
 * спрашивают DNS сервера (1.1.1.1) — их ответы тоже должны идти мимо туннеля.
 */
object BypassDirectDomains {
    private const val TAG = "BypassDirect"
    private const val RESOLVE_TIMEOUT_MS = 5_000L
    private const val MAX_IPS = 3_000

    val domains = listOf(
        "vk.com", "vk.ru", "vk.cc", "vk.link", "vk.me", "vkontakte.ru", "vkontakte.com", "vk-cdn.net",
        "vk-apps.ru", "vk.company", "userapi.com", "vkuser.net", "vkuseraudio.com", "vkuseraudio.net",
        "vkuservideo.com", "vkuservideo.net", "vkuserlive.com", "vkuserlive.net", "vkpay.io",
        "vkpay.com", "vkpay.app", "vkcs.cloud", "vkvideo.ru", "ok.ru", "odkl.ru", "ok.me",
        "okcdn.ru", "okko.ru", "apiok.ru", "tamtam.chat", "tamtam.ok.ru", "mycdn.me", "mail.ru",
        "tech-mail.ru", "vmailru.net", "smailru.net", "appsmail.ru", "imgsmail.ru", "apptracer.ru",
        "max.ru", "oneme.ru", "vkplay.ru", "my.games", "yandex.ru", "yandex.com", "yandex.net",
        "ya.ru", "yastatic.net", "dzen.ru", "dzeninfra.ru", "zen.yandex.ru", "zen.yandex.com",
        "zen.yandex.net", "kinopoisk.ru", "auto.ru", "sberbank.ru", "sber.ru", "tinkoff.ru",
        "tbank.ru", "t-bank-app.ru", "t-bank-app.su", "tcsbank.ru", "dolyame.ru", "vtb.ru",
        "alfabank.ru", "raiffeisen.ru", "sovcombank.ru", "psbank.ru", "gazprombank.ru", "gpb.ru",
        "cbr.ru", "gosuslugi.ru", "nalog.ru", "nalog.gov.ru", "mos.ru", "moscow.ru", "government.ru",
        "kremlin.ru", "duma.gov.ru", "digital.gov.ru", "cikrf.ru", "izbirkom.ru", "esia.gosuslugi.ru",
        "rt.ru", "rtk.ru", "wildberries.ru", "wb.ru", "wbstatic.net", "wbbasket.ru", "wbpay.ru",
        "wibes.ru", "rwb.ru", "ozon.ru", "ozone.ru", "ozon-dostavka.ru", "o3.ru", "o3t.ru",
        "ozonbank.ru", "ozonusercontent.com", "avito.ru", "avito.st", "megamarket.ru", "perekrestok.ru",
        "magnit.ru", "magnit-market.ru", "5ka.ru", "pyaterochka.ru", "chizhik.ru", "lemanapro.ru",
        "vseinstrumenty.ru", "vkusvill.ru", "lamoda.ru", "beru.ru", "market.yandex.ru", "x5.ru",
        "rutube.ru", "ivi.ru", "okko.tv", "kion.ru", "premier.one", "wink.ru", "wink.rt.ru",
        "ottplay.ru", "yappy.ru", "litres.ru", "mts-tv.ru", "music.mts.ru", "ntv.ru", "ctc.ru",
        "2x2tv.ru", "domashny.ru", "friday.ru", "tnt-online.ru", "lenta.ru", "lenta.com", "rbc.ru",
        "kp.ru", "rambler.ru", "gazeta.ru", "gismeteo.ru", "gismeteo.com", "hh.ru", "headhunter.ru",
        "zarplata.ru", "2gis.ru", "2gis.com", "rzd.ru", "tutu.ru", "t2.ru", "nspk.ru", "algoritmika.org",
        "yaklass.ru", "umschool.net", "code-class.ru", "tetrika-school.ru", "myschool.mosreg.ru",
        "school.mos.ru", "cian.ru", "pochta.ru", "cloud.ru", "vi.ru", "digitalaccess.ru", "gpmd.ru",
        "gu-st.ru", "res-nsdi.ru", "auth-nsdi.ru", "r0.mradx.net", "summerstage.ru", "vk-stadium.ru",
        "geobasket.ru", "wb-basket.ru", "paywb.com", "t-tech.team", "sferum-dev.ru", "cxhub.ru",
        "wildberries.by", "lmru.tech", "moskva.taximaxim.ru", "xn--80ajghhoc2aj1c8b.xn--p1ai",
        "vk-portal.net", "cdn-vk.ru", "epp.genproc.gov.ru", "cdn-tinkoff.ru", "mradx.net",
        "rutubelist.ru", "yandex.st", "2gis.kz", "5post.market", "alfabank.com", "aviasales.com",
        "avs.io", "boosty.to", "chizhik.club", "dodois.com", "dodopizza.com", "donationalerts.com",
        "emias.info", "fix-price.com", "gazprombank.com", "giga.chat", "kinopoisk-ru.clstorage.net",
        "megamarket.tech", "mkb.com", "mtsbank.com", "mvk.com", "naydex.net", "okko.sport",
        "okolo.app", "ozon.by", "ozon.com", "ozon.kz", "perekrestok.com", "pochtabank.com",
        "psbank.com", "sber.pro", "sberbank.com", "sbercloud.tech", "sovcombank.com", "tbank-online.com",
        "tinkoff-group.com", "tochka.com", "turbopages.org", "unidraw.io", "vkmessenger.com",
        "vtb.com", "webvisor.com", "x5.com", "x5.group", "x5.tech", "x5static.net", "yads.tech",
        "yandex.by", "yandex.cloud", "yandex.kz", "yandexcloud.net", "yandexmetrica.com", "yastat.net",
        "youla.io", "zvuk.com", "47news.ru",
    )

    private const val CACHE_TTL_MS = 30 * 60_000L
    @Volatile private var cache: Pair<Long, List<String>>? = null

    private val prefetchExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var inflight: java.util.concurrent.Future<List<String>>? = null

    private fun freshCache(): List<String>? = cache?.let { (at, ips) ->
        if (System.currentTimeMillis() - at < CACHE_TTL_MS && ips.isNotEmpty()) ips else null
    }

    /**
     * Запустить резолв в фоне (при нажатии «Подключить»): пока клиент получает
     * креды VK и конфиг от сервера, IP доменов уже будут готовы.
     */
    @Synchronized
    fun prefetch() {
        if (freshCache() != null) return
        val running = inflight
        if (running != null && !running.isDone) return
        inflight = prefetchExec.submit<List<String>> {
            resolveFresh().also { cache = System.currentTimeMillis() to it }
        }
    }

    /**
     * IP доменов для маршрутов TUN. Подъём туннеля ждёт не дольше [maxWaitMs]
     * (раньше — до 5 с): не успели — туннель без них (сайты пойдут через
     * туннель и будут работать), следующее подключение возьмёт кэш.
     */
    fun resolveAll(maxWaitMs: Long = 1_500): List<String> {
        freshCache()?.let { return it }
        prefetch()
        return try {
            inflight?.get(maxWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS).orEmpty()
        } catch (_: Exception) {
            cache?.second.orEmpty()
        }
    }

    private fun resolveFresh(): List<String> {
        val result = ConcurrentHashMap.newKeySet<String>()
        val pool = Executors.newFixedThreadPool(16)
        for (d in domains) {
            pool.execute {
                runCatching {
                    InetAddress.getAllByName(d).filterIsInstance<Inet4Address>()
                        .forEach { result.add(it.hostAddress ?: return@forEach) }
                }
                runCatching { queryA(d, "1.1.1.1").forEach { result.add(it) } }
            }
        }
        pool.shutdown()
        pool.awaitTermination(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        pool.shutdownNow()
        Log.i(TAG, "resolved ${result.size} IPs for ${domains.size} domains")
        return result.take(MAX_IPS)
    }

    /** Простой A-запрос по UDP к dns:53 (сокет приложения идёт мимо VPN). */
    private fun queryA(name: String, dns: String): List<String> {
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val q = ByteBuffer.allocate(512)
        q.putShort(id.toShort()).putShort(0x0100).putShort(1).putShort(0).putShort(0).putShort(0)
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            q.put(bytes.size.toByte()).put(bytes)
        }
        q.put(0).putShort(1).putShort(1)
        val out = mutableListOf<String>()
        DatagramSocket().use { sock ->
            sock.soTimeout = 2_000
            sock.send(DatagramPacket(q.array(), q.position(), InetAddress.getByName(dns), 53))
            val buf = ByteArray(1500)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            val b = ByteBuffer.wrap(buf, 0, resp.length)
            if ((b.short.toInt() and 0xFFFF) != id) return out
            b.short
            val qd = b.short.toInt() and 0xFFFF
            val an = b.short.toInt() and 0xFFFF
            b.short; b.short
            repeat(qd) { skipName(b); b.short; b.short }
            repeat(an) {
                skipName(b)
                val type = b.short.toInt() and 0xFFFF
                b.short; b.int
                val len = b.short.toInt() and 0xFFFF
                if (type == 1 && len == 4) {
                    out += "${b.get().toInt() and 0xFF}.${b.get().toInt() and 0xFF}." +
                        "${b.get().toInt() and 0xFF}.${b.get().toInt() and 0xFF}"
                } else {
                    b.position(b.position() + len)
                }
            }
        }
        return out
    }

    private fun skipName(b: ByteBuffer) {
        while (true) {
            val len = b.get().toInt() and 0xFF
            if (len == 0) return
            if (len and 0xC0 == 0xC0) { b.get(); return }
            b.position(b.position() + len)
        }
    }
}
