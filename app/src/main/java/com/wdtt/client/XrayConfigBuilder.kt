package com.wdtt.client

import org.json.JSONArray
import org.json.JSONObject

/**
 * xray JSON — 1:1 с bypassme-api/xray_configs.py.
 * VpnService TUN → hev-socks5-tunnel → SOCKS 127.0.0.1:10808 → xray.
 */
object XrayConfigBuilder {

    private const val SOCKS_PORT = 10808
    private const val HTTP_PORT = 10809
    private const val GRPC_SERVICE_NAME = "vless-grpc-service"
    private const val REALITY_PUBLIC_KEY = "jQC3Kmmbu-CfUKlQaeuSn4I8dbqAZhpgihZhWu1SvBk"
    private const val REALITY_SHORT_ID = "6b69118a7847c4c3"
    private const val REALITY_SNI = "www.samsung.com"
    private const val REALITY_GRPC_SNI = "www.google.com"
    private const val REALITY_FLOW = "xtls-rprx-vision"

    private val whitelistDomains = arrayOf(
        "vk.com", "vk.ru", "vk.cc", "vk.link", "vk.me",
        "vkontakte.ru", "vkontakte.com",
        "vk-cdn.net", "vk-apps.ru", "vk.company",
        "userapi.com", "vkuser.net",
        "vkuseraudio.com", "vkuseraudio.net",
        "vkuservideo.com", "vkuservideo.net",
        "vkuserlive.com", "vkuserlive.net",
        "vkpay.io", "vkpay.com", "vkpay.app",
        "vkcs.cloud", "vkvideo.ru",
        "ok.ru", "odkl.ru", "ok.me", "okcdn.ru", "okko.ru", "apiok.ru",
        "tamtam.chat", "tamtam.ok.ru", "mycdn.me",
        "mail.ru", "tech-mail.ru", "vmailru.net", "smailru.net",
        "appsmail.ru", "imgsmail.ru", "apptracer.ru",
        "max.ru", "oneme.ru",
        "vkplay.ru", "my.games",
        "yandex.ru", "yandex.com", "yandex.net",
        "ya.ru", "yastatic.net",
        "dzen.ru", "dzeninfra.ru",
        "zen.yandex.ru", "zen.yandex.com", "zen.yandex.net",
        "kinopoisk.ru", "auto.ru",
        "sberbank.ru", "sber.ru",
        "tinkoff.ru", "tbank.ru",
        "t-bank-app.ru", "t-bank-app.su", "tcsbank.ru", "dolyame.ru",
        "vtb.ru", "alfabank.ru", "raiffeisen.ru",
        "sovcombank.ru", "psbank.ru",
        "gazprombank.ru", "gpb.ru", "cbr.ru",
        "gosuslugi.ru", "nalog.ru", "nalog.gov.ru",
        "mos.ru", "moscow.ru",
        "government.ru", "kremlin.ru",
        "duma.gov.ru", "digital.gov.ru",
        "cikrf.ru", "izbirkom.ru",
        "esia.gosuslugi.ru", "rt.ru", "rtk.ru",
        "wildberries.ru", "wb.ru", "wbstatic.net", "wbbasket.ru",
        "wbpay.ru", "wibes.ru", "rwb.ru",
        "ozon.ru", "ozone.ru", "ozon-dostavka.ru", "o3.ru", "o3t.ru",
        "ozonbank.ru", "ozonusercontent.com",
        "avito.ru", "avito.st", "megamarket.ru",
        "perekrestok.ru", "magnit.ru", "magnit-market.ru",
        "5ka.ru", "pyaterochka.ru", "chizhik.ru",
        "lemanapro.ru", "vseinstrumenty.ru", "vkusvill.ru",
        "lamoda.ru", "beru.ru", "market.yandex.ru", "x5.ru",
        "rutube.ru", "ivi.ru", "okko.tv", "kion.ru", "premier.one",
        "wink.ru", "wink.rt.ru", "ottplay.ru", "yappy.ru", "litres.ru",
        "mts-tv.ru", "music.mts.ru",
        "ntv.ru", "ctc.ru", "2x2tv.ru", "domashny.ru", "friday.ru", "tnt-online.ru",
        "lenta.ru", "lenta.com",
        "rbc.ru", "kp.ru", "rambler.ru", "gazeta.ru",
        "gismeteo.ru", "gismeteo.com",
        "hh.ru", "headhunter.ru", "zarplata.ru",
        "2gis.ru", "2gis.com",
        "rzd.ru", "tutu.ru", "t2.ru", "nspk.ru",
        "algoritmika.org", "yaklass.ru", "umschool.net",
        "code-class.ru", "tetrika-school.ru",
        "myschool.mosreg.ru", "school.mos.ru",
        "cian.ru", "pochta.ru", "cloud.ru",
        "vi.ru", "digitalaccess.ru", "gpmd.ru", "gu-st.ru",
        "res-nsdi.ru", "auth-nsdi.ru", "r0.mradx.net",
        "summerstage.ru", "vk-stadium.ru",
        "geobasket.ru", "wb-basket.ru", "paywb.com",
        "t-tech.team", "sferum-dev.ru", "cxhub.ru",
        "wildberries.by", "lmru.tech", "moskva.taximaxim.ru",
        "xn--80ajghhoc2aj1c8b.xn--p1ai",
    )

    fun build(server: VpnServerTemplate, uuid: String): String {
        val proxyTag = server.outboundTag
        val cfg = JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            put("dns", dns())
            put("inbounds", inbounds())
            put("routing", routing(proxyTag))
            put("outbounds", outbounds(server, uuid))
        }
        return cfg.toString()
    }

    private fun dns(): JSONObject = JSONObject().apply {
        put("servers", JSONArray().put("1.1.1.1").put("1.0.0.1"))
        put("queryStrategy", "UseIP")
    }

    private fun inbounds(): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            put("tag", "socks")
            put("port", SOCKS_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
            put("sniffing", sniffing())
        })
        put(JSONObject().apply {
            put("tag", "http")
            put("port", HTTP_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put("settings", JSONObject().put("allowTransparent", false))
            put("sniffing", sniffing())
        })
    }

    private fun sniffing(): JSONObject = JSONObject().apply {
        put("enabled", true)
        put("routeOnly", false)
        put("destOverride", JSONArray().put("http").put("tls").put("quic"))
    }

    private fun routing(proxyTag: String): JSONObject {
        val rules = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "field")
                put("protocol", JSONArray().put("bittorrent"))
                put("outboundTag", "direct")
            })
            put(JSONObject().apply {
                put("type", "field")
                put("domain", JSONArray(whitelistDomains))
                put("outboundTag", "direct")
            })
            put(JSONObject().apply {
                put("type", "field")
                put("network", "tcp,udp")
                put("outboundTag", proxyTag)
            })
        }
        return JSONObject().apply {
            put("domainMatcher", "hybrid")
            put("domainStrategy", "IPIfNonMatch")
            put("rules", rules)
        }
    }

    private fun outbounds(server: VpnServerTemplate, uuid: String): JSONArray {
        val proxy = when (server.network.lowercase()) {
            "tcp" -> tcpRealityOutbound(server, uuid)
            else  -> grpcRealityOutbound(server, uuid)
        }
        return JSONArray().apply {
            put(proxy)
            put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        }
    }

    private fun grpcRealityOutbound(server: VpnServerTemplate, uuid: String): JSONObject =
        JSONObject().apply {
            put("tag", server.outboundTag)
            put("protocol", "vless")
            put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
                put("address", server.address)
                put("port", server.port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", uuid)
                    put("encryption", "none")
                }))
            })))
            put("streamSettings", JSONObject().apply {
                put("network", "grpc")
                put("grpcSettings", JSONObject().apply {
                    put("serviceName", GRPC_SERVICE_NAME)
                    put("multiMode", false)
                })
                put("security", "reality")
                put("realitySettings", JSONObject().apply {
                    put("serverName", REALITY_GRPC_SNI)
                    put("publicKey", REALITY_PUBLIC_KEY)
                    put("shortId", REALITY_SHORT_ID)
                    put("fingerprint", server.fingerprint)
                })
            })
        }

    private fun tcpRealityOutbound(server: VpnServerTemplate, uuid: String): JSONObject =
        JSONObject().apply {
            put("tag", server.outboundTag)
            put("protocol", "vless")
            put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
                put("address", server.address)
                put("port", server.port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", uuid)
                    put("encryption", "none")
                    put("flow", REALITY_FLOW)
                }))
            })))
            put("streamSettings", JSONObject().apply {
                put("network", "tcp")
                put("tcpSettings", JSONObject())
                put("security", "reality")
                put("realitySettings", JSONObject().apply {
                    put("serverName", REALITY_SNI)
                    put("publicKey", REALITY_PUBLIC_KEY)
                    put("shortId", REALITY_SHORT_ID)
                    put("fingerprint", server.fingerprint)
                })
                put("sockopt", JSONObject().put("tcpKeepAliveInterval", 30))
            })
        }
}
