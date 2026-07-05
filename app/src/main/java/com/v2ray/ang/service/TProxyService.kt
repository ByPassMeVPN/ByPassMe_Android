package com.v2ray.ang.service

/**
 * JNI-обёртка для libhev-socks5-tunnel.so (собран под v2rayNG).
 * Имя пакета и методов должны совпадать с нативной регистрацией в .so.
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    external fun TProxyStopService()

    @JvmStatic
    external fun TProxyGetStats(): LongArray
}
