package com.wdtt.client.vpn

import android.content.Context
import com.wdtt.client.WdttApplication
import com.wdtt.client.vpn.constant.ServiceMode
import java.io.File

/**
 * Настройки для hiddify-core.
 * active_config_path — в FlutterSharedPreferences, как ожидает ядро.
 */
object VpnCoreSettings {

    private val corePrefs by lazy {
        WdttApplication.instance.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
    }

    var activeConfigPath: String
        get() = corePrefs.getString("flutter.active_config_path", "") ?: ""
        set(value) = corePrefs.edit().putString("flutter.active_config_path", value).apply()

    var activeProfileName: String
        get() = corePrefs.getString("flutter.active_profile_name", "ByPassMe VPN") ?: "ByPassMe VPN"
        set(value) = corePrefs.edit().putString("flutter.active_profile_name", value).apply()

    val baseDir: String
        get() = WdttApplication.instance.filesDir.absolutePath

    val workingDir: String
        get() = (WdttApplication.instance.getExternalFilesDir(null)
            ?: WdttApplication.instance.filesDir).absolutePath

    val tempDir: String
        get() = WdttApplication.instance.cacheDir.absolutePath

    const val grpcServiceModePort = 17079

    val startCoreAfterStartingService: Boolean
        get() = corePrefs.getBoolean("flutter.starting_core_on_starting_service", true)

    val debugMode = false
    val disableMemoryLimit = false

    fun prepareForStart(configPath: String, profileName: String = "ByPassMe VPN") {
        ensureDirs()
        corePrefs.edit()
            .putString("flutter.active_config_path", configPath)
            .putString("flutter.active_profile_name", profileName)
            .putString("flutter.service-mode", ServiceMode.VPN)
            .putBoolean("flutter.starting_core_on_starting_service", true)
            .putString("base_dir", baseDir)
            .putString("working_dir", workingDir)
            .putString("tmp_dir", tempDir)
            .apply()
    }

    fun ensureDirs() {
        File(baseDir).mkdirs()
        File(workingDir).mkdirs()
        File(tempDir).mkdirs()
        File(workingDir, "stderr.log").delete()
    }
}
