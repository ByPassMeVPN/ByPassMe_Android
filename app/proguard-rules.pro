-keep class com.jcraft.jsch.** { *; }
-keep class com.mwiede.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn com.mwiede.jsch.**

-keep class go.** { *; }
-keep class libv2ray.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.wdtt.client.XrayVpnService { *; }
-keep class com.wdtt.client.HevTun2Socks { *; }
-keep class com.v2ray.ang.service.TProxyService { *; }
-keep class com.wireguard.android.backend.GoBackend { *; }
-keep class com.wireguard.android.backend.GoBackend$VpnService { *; }
