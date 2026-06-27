-keep class com.jcraft.jsch.** { *; }
-keep class com.mwiede.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn com.mwiede.jsch.**

-keep class go.** { *; }
-keep class com.hiddify.core.** { *; }
-keep class libv2ray.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.wdtt.client.vpn.** { *; }
-keep class com.wdtt.client.SingBoxVpnService { *; }
