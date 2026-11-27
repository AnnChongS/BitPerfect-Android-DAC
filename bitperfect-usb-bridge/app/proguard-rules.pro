# Keep JNI entry points, callbacks, and models
-keep class com.bitperfect.usbbridge.nativebridge.** { *; }

# Keep Shizuku UserService (loaded out-of-process under uid=2000 via app_process) & AIDL stubs
-keep class com.bitperfect.usbbridge.shizuku.** { *; }
-keep interface com.bitperfect.usbbridge.shizuku.** { *; }

# Keep Shizuku SDK / Provider classes
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
