package com.wdtt.client

/**
 * Токен hub.mos.ru — XOR-байты подставляются при сборке (не plaintext в исходниках).
 * См. app/build.gradle.kts → HubToken.ENC
 */
object HubToken {
    private const val KEY: Int = 0x5C

    /** Заполняется BuildConfig из gradle при assemble; пусто → списки не загрузятся. */
    private val enc: IntArray = BuildConfig.HUB_TOKEN_ENC
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .toIntArray()

    val mos: String
        get() {
            if (enc.isEmpty()) return ""
            val bytes = ByteArray(enc.size) { i ->
                (enc[i] xor KEY xor (i and 0xFF)).toByte()
            }
            return runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        }
}
