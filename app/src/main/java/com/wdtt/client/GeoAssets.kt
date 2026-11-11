package com.wdtt.client

import android.content.Context
import android.util.Log
import java.io.File

/**
 * geosite.dat (v2fly dlc.dat, кладётся в assets при сборке) → filesDir, откуда его
 * читает xray (initCoreEnv). Нужен для geosite:category-ru / youtube / tiktok / …
 * Копируется заново, если размер в APK изменился (новая версия списков).
 */
object GeoAssets {
    private const val TAG = "GeoAssets"
    private val FILES = listOf("geosite.dat")

    @Synchronized
    fun ensure(context: Context) {
        for (name in FILES) {
            val target = File(context.filesDir, name)
            try {
                val assetSize = context.assets.openFd(name).use { it.length }
                if (target.exists() && target.length() == assetSize) continue
                val tmp = File(context.filesDir, "$name.tmp")
                context.assets.open(name).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                tmp.renameTo(target)
            } catch (e: Exception) {
                // openFd не работает для сжатых assets — копируем, только если файла нет.
                if (!target.exists()) {
                    runCatching {
                        context.assets.open(name).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }.onFailure { Log.w(TAG, "$name: ${it.message}") }
                }
            }
        }
    }
}
