package com.wdtt.client

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Передаёт TUN fd в go_client через unix-сокет (SCM_RIGHTS): go_client — отдельный
 * процесс (ProcessBuilder), штатной передачи fd в дочерний процесс нет.
 *
 * go_client слушает (recvTunFD в tun_fd.go), Android подключается уже после
 * establish() — так нет гонки «клиент стартовал раньше, чем поднят TUN».
 * Abstract namespace — без файла на диске.
 */
object TunFdBridge {
    private const val TAG = "TunFdBridge"
    private const val CONNECT_RETRY_DELAY_MS = 200L
    private const val MAX_ATTEMPTS = 25 // 5 с

    /** Имя сокета без "@" — для Android. */
    fun newSocketName(): String = "bpm_tun_fd_${android.os.Process.myPid()}_${System.nanoTime()}"

    /** То же имя с "@" — так его ждёт go_client в -tun-fd-sock. */
    fun goSockPath(name: String): String = "@$name"

    suspend fun sendOnce(name: String, pfd: ParcelFileDescriptor) = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            var client: LocalSocket? = null
            try {
                client = LocalSocket()
                client.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                client.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                // Байт нужен только как носитель ancillary-данных с fd.
                client.outputStream.write(1)
                client.outputStream.flush()
                Log.i(TAG, "TUN fd передан в go_client (попытка #$attempt)")
                return@withContext
            } catch (e: Exception) {
                lastError = e
            } finally {
                runCatching { client?.close() }
            }
            delay(CONNECT_RETRY_DELAY_MS)
        }
        throw IllegalStateException("go_client не принял TUN за ${MAX_ATTEMPTS * CONNECT_RETRY_DELAY_MS / 1000} с", lastError)
    }
}
