package com.wdtt.client.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Фоновая работа: главная причина, по которой Обход/VPN выключается, пока
 * телефон лежит, — экономия батареи прошивки (Xiaomi, OPPO/Realme, Huawei,
 * vivo, Samsung, Tecno). Кодом её не обойти — только настройкой у человека.
 * Экран — во вкладке «Настройки» (AppSettingsTab).
 */
object BackgroundHelper {

    fun ignoringBatteryOptimizations(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun backgroundRestricted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 28) {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
        } else false

    /** Есть что настроить — показываем значок. */
    fun needsAttention(context: Context): Boolean =
        !ignoringBatteryOptimizations(context) || backgroundRestricted(context)

    @SuppressLint("BatteryLife")
    fun openBatteryExemption(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        if (!start(context, direct)) {
            start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** Экраны автозапуска/фона в прошивках; первый открывшийся — он и нужен. */
    private val autostartScreens = listOf(
        // Xiaomi / Redmi / POCO (MIUI, HyperOS)
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // Huawei / Honor
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        // OPPO / Realme / OnePlus (ColorOS)
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        // vivo / iQOO
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        // Samsung — «Батарея → Приложения без ограничений»
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        // Tecno / Infinix / itel
        ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
        // Asus
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
    )

    fun openAutostart(context: Context) {
        for (cn in autostartScreens) {
            if (start(context, Intent().setComponent(cn))) return
        }
        openAppDetails(context)
    }

    fun openAlwaysOnVpn(context: Context) {
        if (!start(context, Intent(Settings.ACTION_VPN_SETTINGS))) openAppDetails(context)
    }

    fun openAppDetails(context: Context) {
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}")))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}
