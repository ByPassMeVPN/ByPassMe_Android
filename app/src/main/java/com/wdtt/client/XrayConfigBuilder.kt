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
    private const val HYSTERIA_AUTH = "f2df79dd-f7d0-4c8b-8c61-7b6fb9852758"
    private const val HYSTERIA_ALPN = "h3"

    // VLESS+xHTTP+TLS (как в bypassme-api xray_configs.WS3_*)
    private const val XHTTP_PATH = "/assets/build/_app/immutable/chunks/stream-one/"
    private const val XHTTP_MODE = "auto"

    /** Всегда через VPN (на сервере — WARP): YouTube, Gemini, TikTok, Instagram, OpenAI, DeepSeek, Telegram. Строго ДО правила RU, иначе category-ru увела бы их напрямую. */
    private val forceVpnDomains = arrayOf(
        "geosite:youtube",
        "domain:youtube.com",
        "domain:youtu.be",
        "domain:youtube-nocookie.com",
        "domain:youtubekids.com",
        "domain:ytimg.com",
        "domain:ggpht.com",
        "domain:googlevideo.com",
        "domain:youtubei.googleapis.com",
        "domain:yt3.ggpht.com",
        "domain:jnn-pa.googleapis.com",
        "domain:gvt1.com",
        "domain:gvt2.com",
        "domain:googleapis.com",
        "domain:gstatic.com",
        "domain:www.gstatic.com",
        "geosite:google-gemini",
        "domain:gemini.google.com",
        "domain:gemini.google",
        "domain:generativelanguage.googleapis.com",
        "domain:ai.google.dev",
        "geosite:tiktok",
        "domain:tiktok.com",
        "domain:tiktokv.com",
        "domain:tiktokcdn.com",
        "domain:tiktokcdn-us.com",
        "domain:byteoversea.com",
        "domain:ibytedtos.com",
        "domain:ibyteimg.com",
        "domain:muscdn.com",
        "domain:musical.ly",
        "domain:bytedance.com",
        "domain:snssdk.com",
        "domain:ttwstatic.com",
        "domain:ipstatp.com",
        "domain:tiktokd.org",
        "domain:tiktokrow-cdn.com",
        "domain:analytics.tiktok.com",
        "domain:ads.tiktok.com",
        "geosite:instagram",
        "domain:instagram.com",
        "domain:cdninstagram.com",
        "domain:instagr.am",
        "domain:ig.me",
        "domain:fbcdn.net",
        "geosite:openai",
        "domain:openai.com",
        "domain:chatgpt.com",
        "domain:oaistatic.com",
        "domain:oaiusercontent.com",
        "domain:deepseek.com",
        "domain:deepseek.ai",
        "geosite:telegram",
        "domain:telegram.org",
        "domain:telegram.me",
        "domain:t.me",
        "domain:tdesktop.com",
        "domain:telegra.ph",
        "domain:telesco.pe",
        // Проверка IP — всегда через VPN, чтобы показывала IP сервера (как в Happ).
        "domain:2ip.ru",
        "domain:2ip.io",
        "domain:2ip.me",
        "domain:myip.ru",
        "domain:check-host.net",
        "domain:whoer.net",
        "domain:ip-api.com",
        "domain:ipify.org",
        "domain:ifconfig.me",
        "domain:icanhazip.com",
        "domain:ipinfo.io",
        "domain:ipleak.net",
        "domain:browserleaks.com",
    )

    /** Российские сайты — напрямую (как в подписке Happ). */
    private val ruDirectDomains = arrayOf(
        "geosite:category-ru",
        "geosite:category-gov-ru",
        "geosite:yandex",
        "geosite:vk",
        "geosite:ok",
        "regexp:^(.+\\.)?ru$",
        "regexp:^(.+\\.)?su$",
        "regexp:^(.+\\.)?xn--p1ai$",
        "domain:userapi.com",
        "domain:vk.com",
        "domain:vk.me",
        "domain:vk.cc",
        "domain:vkontakte.ru",
        "domain:vk-cdn.net",
        "domain:vkuser.net",
        "domain:vkuservideo.com",
        "domain:vkmessenger.com",
        "domain:mycdn.me",
        "domain:mradx.net",
        "domain:mvk.com",
        "domain:tamtam.chat",
        "domain:okolo.app",
        "domain:perekrestok.com",
        "domain:5post.market",
        "domain:chizhik.club",
        "domain:x5.com",
        "domain:x5.tech",
        "domain:x5.group",
        "domain:x5static.net",
        "domain:ozon.com",
        "domain:ozon.by",
        "domain:ozon.kz",
        "domain:ozonusercontent.com",
        "domain:paywb.com",
        "domain:okko.tv",
        "domain:okko.sport",
        "domain:kinopoisk-ru.clstorage.net",
        "domain:yandex.com",
        "domain:yandex.net",
        "domain:yandex.by",
        "domain:yandex.kz",
        "domain:yandex.cloud",
        "domain:yandexcloud.net",
        "domain:yandexmetrica.com",
        "domain:yastatic.net",
        "domain:yastat.net",
        "domain:naydex.net",
        "domain:turbopages.org",
        "domain:webvisor.com",
        "domain:yads.tech",
        "domain:avs.io",
        "domain:aviasales.com",
        "domain:2gis.com",
        "domain:2gis.kz",
        "domain:sberbank.com",
        "domain:sber.pro",
        "domain:giga.chat",
        "domain:sbercloud.tech",
        "domain:megamarket.tech",
        "domain:lenta.com",
        "domain:tochka.com",
        "domain:gazprombank.com",
        "domain:vtb.com",
        "domain:alfabank.com",
        "domain:sovcombank.com",
        "domain:psbank.com",
        "domain:mkb.com",
        "domain:pochtabank.com",
        "domain:mtsbank.com",
        "domain:tinkoff-group.com",
        "domain:tbank-online.com",
        "domain:dodois.com",
        "domain:dodopizza.com",
        "domain:fix-price.com",
        "domain:zvuk.com",
        "domain:trbcdn.net",
        "domain:cdnvideohub.com",
        "domain:boosty.to",
        "domain:donationalerts.com",
        "domain:youla.io",
        "domain:emias.info",
        "domain:unidraw.io",
    )

    /** Apple Private Relay / DoH — блок, иначе часть трафика уходит мимо VPN. */
    private val appleBlockDomains = arrayOf(
        "domain:mask.icloud.com",
        "domain:mask-h2.icloud.com",
        "domain:mask-api.icloud.com",
        "domain:dns.apple.com",
        "domain:doh.dns.apple.com",
    )

    /** Локальные сети — напрямую (без geoip.dat). */
    private val privateCidrs = arrayOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
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
        put("servers", JSONArray().put("1.1.1.1").put("9.9.9.9").put("94.140.14.14").put("1.0.0.1"))
        put("queryStrategy", "UseIPv4")
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
        // routeOnly: домен из SNI только для выбора маршрута, соединение — на
        // исходный IP (без повторного резолва внутри xray).
        put("routeOnly", true)
        put("destOverride", JSONArray().put("http").put("tls").put("quic"))
    }

    /**
     * Маршрутизация VPN как в подписке Happ: RU — напрямую, всё остальное —
     * в VPN; YouTube/TikTok/Instagram/… — принудительно в VPN (дальше WARP на ноде).
     */
    private fun routing(proxyTag: String): JSONObject {
        val rules = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "field")
                put("domain", JSONArray(appleBlockDomains))
                put("outboundTag", "block")
            })
            put(JSONObject().apply {
                put("type", "field")
                put("protocol", JSONArray().put("bittorrent"))
                put("outboundTag", "block")
            })
            put(JSONObject().apply {
                put("type", "field")
                put("domain", JSONArray(forceVpnDomains))
                put("outboundTag", proxyTag)
            })
            put(JSONObject().apply {
                put("type", "field")
                put("domain", JSONArray(ruDirectDomains))
                put("outboundTag", "direct")
            })
            put(JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray(privateCidrs))
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
            // AsIs: решение по имени сразу, без DNS-запроса перед каждым соединением.
            put("domainStrategy", "AsIs")
            put("rules", rules)
        }
    }

    private fun outbounds(server: VpnServerTemplate, uuid: String): JSONArray {
        val proxy = when (server.network.lowercase()) {
            "hysteria" -> hysteriaOutbound(server, uuid)
            "tcp"      -> tcpRealityOutbound(server, uuid)
            "xhttp", "splithttp" -> xhttpOutbound(server, uuid)
            else       -> grpcRealityOutbound(server, uuid)
        }
        return JSONArray().apply {
            put(proxy)
            put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        }
    }

    private fun hysteriaOutbound(server: VpnServerTemplate, uuid: String): JSONObject {
        // Remnawave hy2: auth = VLESS UUID пользователя (не legacy password).
        val auth = uuid.ifBlank { HYSTERIA_AUTH }
        val sni = server.sni.ifBlank { server.address }
        val fp = server.fingerprint.ifBlank { "chrome" }
        return JSONObject().apply {
            put("tag", server.outboundTag)
            put("protocol", "hysteria")
            put("settings", JSONObject().apply {
                put("address", server.address)
                put("port", server.port)
                put("version", 2)
            })
            put("streamSettings", JSONObject().apply {
                put("network", "hysteria")
                put("security", "tls")
                put("tlsSettings", JSONObject().apply {
                    put("alpn", JSONArray().put(HYSTERIA_ALPN))
                    put("fingerprint", fp)
                    put("serverName", sni)
                    if (server.isGaming) {
                        put("cipherSuites",
                            "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256")
                        put("enableSessionResumption", true)
                    }
                })
                put("hysteriaSettings", JSONObject().apply {
                    put("auth", auth)
                    put("version", 2)
                })
                put("finalmask", JSONObject().apply {
                    put("quicParams", JSONObject().apply {
                        put("disablePathMTUDiscovery", false)
                        put("initConnReceiveWindow", 20971520)
                        put("initStreamReceiveWindow", 8388608)
                        // GAMING SERVER (как в подписке Happ): brutal не сбрасывает
                        // скорость при потерях, короткий keepalive/idle — быстрее
                        // замечаем обрыв на мобильной сети.
                        if (server.isGaming) put("congestion", "brutal")
                        put("keepAlivePeriod", if (server.isGaming) 5 else 10)
                        put("maxConnReceiveWindow", 20971520)
                        put("maxIdleTimeout", if (server.isGaming) 15 else 30)
                        put("maxIncomingStreams", 1024)
                        put("maxStreamReceiveWindow", 8388608)
                    })
                })
                put("sockopt", JSONObject().apply {
                    put("udpDomainStrategy", "UseIP")
                })
            })
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
                    put("publicKey", server.publicKey.ifBlank { REALITY_PUBLIC_KEY })
                    put("shortId", server.shortId.ifBlank { REALITY_SHORT_ID })
                    put("fingerprint", server.fingerprint)
                })
            })
        }

    private fun tcpRealityOutbound(server: VpnServerTemplate, uuid: String): JSONObject {
        val sni = server.sni.ifBlank { REALITY_SNI }
        return JSONObject().apply {
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
                    put("serverName", sni)
                    put("publicKey", server.publicKey.ifBlank { REALITY_PUBLIC_KEY })
                    put("shortId", server.shortId.ifBlank { REALITY_SHORT_ID })
                    put("fingerprint", server.fingerprint.ifBlank { "firefox" })
                })
                put("sockopt", JSONObject().put("tcpKeepAliveInterval", 30))
            })
        }
    }

    private fun xhttpOutbound(server: VpnServerTemplate, uuid: String): JSONObject {
        val host = server.address
        val sni = server.sni.ifBlank { host }
        val path = server.path.ifBlank { XHTTP_PATH }
        val fp = server.fingerprint.ifBlank { "qq" }
        return JSONObject().apply {
            put("tag", server.outboundTag)
            put("protocol", "vless")
            put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
                put("address", host)
                put("port", server.port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", uuid)
                    put("encryption", "none")
                    put("flow", "")
                }))
            })))
            put("streamSettings", JSONObject().apply {
                put("network", "xhttp")
                put("security", "tls")
                put("tlsSettings", JSONObject().apply {
                    put("alpn", JSONArray().put("h2").put("http/1.1"))
                    put("fingerprint", fp)
                    put("serverName", sni)
                })
                put("xhttpSettings", JSONObject().apply {
                    put("path", path)
                    put("host", sni)
                    put("mode", XHTTP_MODE)
                    put("extra", JSONObject().apply {
                        put("scMaxEachPostBytes", "393216-786432")
                        put("xPaddingBytes", "100-500")
                        put("xmux", JSONObject().apply {
                            put("cMaxReuseTimes", "200-300")
                            put("hKeepAlivePeriod", 60)
                            put("hMaxRequestTimes", "200-300")
                            put("hMaxReusableSecs", "600-900")
                            put("maxConnections", 1)
                        })
                    })
                })
            })
        }
    }
}