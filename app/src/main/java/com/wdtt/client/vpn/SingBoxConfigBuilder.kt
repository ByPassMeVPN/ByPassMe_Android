package com.wdtt.client.vpn

import org.json.JSONArray
import org.json.JSONObject

/** sing-box JSON для hiddify-core (TUN + VLESS REALITY), как Happ/xray_configs. */
object SingBoxConfigBuilder {

    private const val GRPC_SERVICE = "vless-grpc-service"
    private const val REALITY_PUBLIC_KEY = "jQC3Kmmbu-CfUKlQaeuSn4I8dbqAZhpgihZhWu1SvBk"
    private const val REALITY_SHORT_ID = "6b69118a7847c4c3"
    private const val REALITY_SNI = "www.samsung.com"
    private const val REALITY_GRPC_SNI = "www.google.com"
    private const val REALITY_FLOW = "xtls-rprx-vision"

    private val whitelistDomains = arrayOf(
        "vk.com", "vk.ru", "vk.cc", "vk.link", "vk.me", "vkontakte.ru", "vkontakte.com",
        "vk-cdn.net", "vk-apps.ru", "vk.company", "userapi.com", "vkuser.net",
        "vkuseraudio.com", "vkuseraudio.net", "vkuservideo.com", "vkuservideo.net",
        "vkuserlive.com", "vkuserlive.net", "vkpay.io", "vkpay.com", "vkpay.app",
        "vkcs.cloud", "vkvideo.ru", "ok.ru", "odkl.ru", "ok.me", "okcdn.ru", "okko.ru", "apiok.ru",
        "tamtam.chat", "tamtam.ok.ru", "mycdn.me", "mail.ru", "tech-mail.ru", "vmailru.net",
        "smailru.net", "appsmail.ru", "imgsmail.ru", "apptracer.ru", "max.ru", "oneme.ru",
        "vkplay.ru", "my.games", "yandex.ru", "yandex.com", "yandex.net", "ya.ru", "yastatic.net",
        "dzen.ru", "dzeninfra.ru", "zen.yandex.ru", "zen.yandex.com", "zen.yandex.net",
        "kinopoisk.ru", "auto.ru", "sberbank.ru", "sber.ru", "tinkoff.ru", "tbank.ru",
        "t-bank-app.ru", "t-bank-app.su", "tcsbank.ru", "dolyame.ru", "vtb.ru", "alfabank.ru",
        "raiffeisen.ru", "sovcombank.ru", "psbank.ru", "gazprombank.ru", "gpb.ru", "cbr.ru",
        "gosuslugi.ru", "nalog.ru", "nalog.gov.ru", "mos.ru", "moscow.ru", "government.ru",
        "kremlin.ru", "duma.gov.ru", "digital.gov.ru", "cikrf.ru", "izbirkom.ru",
        "esia.gosuslugi.ru", "rt.ru", "rtk.ru", "wildberries.ru", "wb.ru", "wbstatic.net",
        "wbbasket.ru", "wbpay.ru", "wibes.ru", "rwb.ru", "ozon.ru", "ozone.ru",
        "ozon-dostavka.ru", "o3.ru", "o3t.ru", "ozonbank.ru", "ozonusercontent.com",
        "avito.ru", "avito.st", "megamarket.ru", "perekrestok.ru", "magnit.ru",
        "magnit-market.ru", "5ka.ru", "pyaterochka.ru", "chizhik.ru", "lemanapro.ru",
        "vseinstrumenty.ru", "vkusvill.ru", "lamoda.ru", "beru.ru", "market.yandex.ru", "x5.ru",
        "rutube.ru", "ivi.ru", "okko.tv", "kion.ru", "premier.one", "wink.ru", "wink.rt.ru",
        "ottplay.ru", "yappy.ru", "litres.ru", "mts-tv.ru", "music.mts.ru", "ntv.ru", "ctc.ru",
        "2x2tv.ru", "domashny.ru", "friday.ru", "tnt-online.ru", "lenta.ru", "lenta.com",
        "rbc.ru", "kp.ru", "rambler.ru", "gazeta.ru", "gismeteo.ru", "gismeteo.com",
        "hh.ru", "headhunter.ru", "zarplata.ru", "2gis.ru", "2gis.com", "rzd.ru", "tutu.ru",
        "t2.ru", "nspk.ru", "algoritmika.org", "yaklass.ru", "umschool.net", "code-class.ru",
        "tetrika-school.ru", "myschool.mosreg.ru", "school.mos.ru", "cian.ru", "pochta.ru",
        "cloud.ru", "vi.ru", "digitalaccess.ru", "gpmd.ru", "gu-st.ru", "res-nsdi.ru",
        "auth-nsdi.ru", "r0.mradx.net", "summerstage.ru", "vk-stadium.ru", "geobasket.ru",
        "wb-basket.ru", "paywb.com", "t-tech.team", "sferum-dev.ru", "cxhub.ru",
        "wildberries.by", "lmru.tech", "moskva.taximaxim.ru", "xn--80ajghhoc2aj1c8b.xn--p1ai",
    )

    fun build(server: VpnServerTemplate, uuid: String): String {
        val proxyTag = server.outboundTag
        return JSONObject().apply {
            put("log", JSONObject().put("level", "warn"))
            put("dns", dns())
            put("inbounds", JSONArray().put(tunInbound()))
            put("outbounds", outbounds(server, uuid))
            put("route", route(proxyTag))
        }.toString()
    }

    private fun dns(): JSONObject = JSONObject().apply {
        put("servers", JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "dns-remote")
                put("address", "1.1.1.1")
                put("detour", "proxy")
            })
            put(JSONObject().apply {
                put("tag", "dns-direct")
                put("address", "1.1.1.1")
                put("detour", "direct")
            })
        })
        put("strategy", "ipv4_only")
    }

    private fun tunInbound(): JSONObject = JSONObject().apply {
        put("type", "tun")
        put("tag", "tun-in")
        put("inet4_address", "172.19.0.1/30")
        put("auto_route", true)
        put("strict_route", true)
        put("stack", "mixed")
        put("sniff", true)
        put("sniff_override_destination", true)
    }

    private fun route(proxyTag: String): JSONObject {
        val rules = JSONArray().apply {
            put(JSONObject().apply {
                put("protocol", "bittorrent")
                put("outbound", "direct")
            })
            put(JSONObject().apply {
                put("domain", JSONArray(whitelistDomains))
                put("outbound", "direct")
            })
        }
        return JSONObject().apply {
            put("rules", rules)
            put("auto_detect_interface", true)
            put("final", proxyTag)
        }
    }

    private fun outbounds(server: VpnServerTemplate, uuid: String): JSONArray =
        JSONArray().apply {
            put(if (server.network.equals("tcp", true)) tcpVless(server, uuid) else grpcVless(server, uuid))
            put(JSONObject().put("type", "direct").put("tag", "direct"))
            put(JSONObject().put("type", "block").put("tag", "block"))
        }

    private fun realityTls(serverName: String, fingerprint: String): JSONObject =
        JSONObject().apply {
            put("enabled", true)
            put("server_name", serverName)
            put("utls", JSONObject().apply {
                put("enabled", true)
                put("fingerprint", fingerprint)
            })
            put("reality", JSONObject().apply {
                put("enabled", true)
                put("public_key", REALITY_PUBLIC_KEY)
                put("short_id", REALITY_SHORT_ID)
            })
        }

    private fun grpcVless(server: VpnServerTemplate, uuid: String): JSONObject =
        JSONObject().apply {
            put("type", "vless")
            put("tag", server.outboundTag)
            put("server", server.address)
            put("server_port", server.port)
            put("uuid", uuid)
            put("tls", realityTls(REALITY_GRPC_SNI, server.fingerprint))
            put("transport", JSONObject().apply {
                put("type", "grpc")
                put("service_name", GRPC_SERVICE)
            })
        }

    private fun tcpVless(server: VpnServerTemplate, uuid: String): JSONObject =
        JSONObject().apply {
            put("type", "vless")
            put("tag", server.outboundTag)
            put("server", server.address)
            put("server_port", server.port)
            put("uuid", uuid)
            put("flow", REALITY_FLOW)
            put("tls", realityTls(REALITY_SNI, server.fingerprint))
        }
}
