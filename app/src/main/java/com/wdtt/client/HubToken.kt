package com.wdtt.client

/**
 * Токен hub.mos.ru — XOR-байты из BuildConfig.HUB_TOKEN_ENC (без plaintext glpat в исходниках).
 */
object HubToken {
    private const val KEY: Int = 0x5C

    @Volatile
    private var cached: String? = null

    val mos: String
        get() {
            cached?.let { return it }
            val decoded = decode(BuildConfig.HUB_TOKEN_ENC)
            cached = decoded
            return decoded
        }

    @JvmStatic
    private fun decode(encCsv: String): String {
        if (encCsv.isBlank()) return ""
        val parts = encCsv.split(',')
        if (parts.isEmpty()) return ""
        val bytes = ByteArray(parts.size)
        var i = 0
        while (i < parts.size) {
            val v = parts[i].trim().toIntOrNull() ?: return ""
            bytes[i] = (v xor KEY xor (i and 0xFF)).toByte()
            i++
        }
        return runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
    }
}
