package com.wdtt.client

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Связь процесса интерфейса и процесса туннеля (:bg).
 *
 * Туннель (TunnelService — служба и VPN Обхода, XrayVpnService, Go-клиент) живёт
 * в процессе :bg: падение или выгрузка интерфейса его не рвёт. TunnelManager
 * в :bg — настоящий; в процессе интерфейса тот же объект — зеркало его
 * состояния (running, tunnelReady, stats, activeWorkers), которое
 * обновляется отсюда. Записи журнала :bg тоже пересылаются в интерфейс.
 * Команды из интерфейса — как и раньше, Intent'ами к службам.
 * Рассылки — только внутри приложения (setPackage + RECEIVER_NOT_EXPORTED).
 */
object TunnelBridge {
    const val ACTION_STATE = "com.wdtt.client.bridge.TUNNEL_STATE"
    const val ACTION_LOG = "com.wdtt.client.bridge.APP_LOG"
    const val ACTION_QUERY = "com.wdtt.client.bridge.QUERY"

    private const val EXTRA_RUNNING = "running"
    private const val EXTRA_READY = "ready"
    private const val EXTRA_STATS = "stats"
    private const val EXTRA_WORKERS = "workers"
    private const val EXTRA_VPN = "vpn"
    private const val EXTRA_SOURCE = "source"
    private const val EXTRA_LEVEL = "level"
    private const val EXTRA_MESSAGE = "message"
    private const val EXTRA_TIME = "time"

    @Volatile private var appContext: Context? = null
    @Volatile var isTunnelProcess = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun processNameOf(app: Application): String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else app.packageName

    // ─── Процесс туннеля (:bg) ───

    @OptIn(FlowPreview::class)
    fun startPublisher(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        isTunnelProcess = true
        scope.launch {
            combine(
                TunnelManager.running,
                TunnelManager.tunnelReady,
                TunnelManager.stats,
                TunnelManager.activeWorkers,
            ) { running, ready, stats, workers -> Snapshot(running, ready, stats, workers) }
                .debounce(200)
                .collect { publish(ctx, it) }
        }
        // Пульс: пока туннель работает, состояние повторяется — так интерфейс
        // понимает, что процесс :bg жив (см. startMirror).
        scope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                if (TunnelManager.running.value || XrayVpnService.isCoreUp) publish(ctx, current())
            }
        }
        register(ctx, IntentFilter(ACTION_QUERY)) { _ ->
            publish(ctx, current())
            XrayVpnService.publishState(ctx)
        }
    }

    private const val HEARTBEAT_MS = 5_000L
    private const val STALE_MS = 15_000L

    private data class Snapshot(
        val running: Boolean, val ready: Boolean, val stats: String, val workers: Int,
        val vpn: Boolean = XrayVpnService.isCoreUp,
    )

    private fun current() = Snapshot(
        TunnelManager.running.value, TunnelManager.tunnelReady.value,
        TunnelManager.stats.value, TunnelManager.activeWorkers.value,
    )

    @Volatile private var lastStateAt = 0L

    private fun publish(ctx: Context, s: Snapshot) {
        runCatching {
            ctx.sendBroadcast(Intent(ACTION_STATE).setPackage(ctx.packageName)
                .putExtra(EXTRA_RUNNING, s.running)
                .putExtra(EXTRA_READY, s.ready)
                .putExtra(EXTRA_STATS, s.stats)
                .putExtra(EXTRA_WORKERS, s.workers)
                .putExtra(EXTRA_VPN, s.vpn))
        }
    }

    /** AppLogger процесса :bg → журнал интерфейса. */
    fun forwardLog(source: LogSource, level: LogLevel, message: String, timeMs: Long) {
        val ctx = appContext ?: return
        if (!isTunnelProcess) return
        runCatching {
            ctx.sendBroadcast(Intent(ACTION_LOG).setPackage(ctx.packageName)
                .putExtra(EXTRA_SOURCE, source.name)
                .putExtra(EXTRA_LEVEL, level.name)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_TIME, timeMs))
        }
    }

    // ─── Процесс интерфейса ───

    fun startMirror(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        register(ctx, IntentFilter(ACTION_STATE)) { i ->
            lastStateAt = System.currentTimeMillis()
            TunnelManager.running.value = i.getBooleanExtra(EXTRA_RUNNING, false)
            TunnelManager.tunnelReady.value = i.getBooleanExtra(EXTRA_READY, false)
            TunnelManager.stats.value = i.getStringExtra(EXTRA_STATS) ?: ""
            TunnelManager.activeWorkers.value = i.getIntExtra(EXTRA_WORKERS, 0)
            // VPN в :bg уже не работает, а интерфейс пропустил «отключён».
            if (!i.getBooleanExtra(EXTRA_VPN, false) && XrayManager.running.value &&
                !XrayManager.connecting.value
            ) XrayManager.running.value = false
        }
        register(ctx, IntentFilter(ACTION_LOG)) { i ->
            val source = runCatching { LogSource.valueOf(i.getStringExtra(EXTRA_SOURCE) ?: "") }.getOrNull()
                ?: LogSource.SERVICE
            val level = runCatching { LogLevel.valueOf(i.getStringExtra(EXTRA_LEVEL) ?: "") }.getOrNull()
                ?: LogLevel.INFO
            AppLogger.appendRemote(source, level, i.getStringExtra(EXTRA_MESSAGE) ?: "",
                i.getLongExtra(EXTRA_TIME, System.currentTimeMillis()))
        }
        requestState(ctx)
        // Процесс :bg упал или убит системой — рассылок больше не будет, а
        // зеркало осталось бы «подключено». Нет пульса — переспросить и,
        // если тишина, показать «выключено». (После заморозки интерфейса
        // отложенные рассылки приходят разом — поэтому сначала вопрос.)
        scope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                if (!TunnelManager.running.value && !XrayManager.running.value) continue
                if (System.currentTimeMillis() - lastStateAt < STALE_MS) continue
                requestState(ctx)
                delay(3_000)
                if (System.currentTimeMillis() - lastStateAt >= STALE_MS) {
                    XrayManager.running.value = false
                    XrayManager.connecting.value = false
                    TunnelManager.running.value = false
                    TunnelManager.tunnelReady.value = false
                    TunnelManager.activeWorkers.value = 0
                    TunnelManager.stats.value = "Ожидание данных..."
                }
            }
        }
    }

    /** Интерфейс запущен заново (туннель мог работать без него) — спросить состояние. */
    fun requestState(context: Context) {
        val ctx = context.applicationContext
        runCatching { ctx.sendBroadcast(Intent(ACTION_QUERY).setPackage(ctx.packageName)) }
    }

    private fun register(ctx: Context, filter: IntentFilter, onReceive: (Intent) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent != null) onReceive(intent)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
    }
}
