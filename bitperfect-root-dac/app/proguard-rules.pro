# Keep JNI entry points, callbacks, and models
-keep class com.bitperfect.rootdac.nativebridge.** { *; }

# Keep LSPosed / Xposed hook entry point, inner state classes, and all XC_MethodHook callbacks
-keepattributes InnerClasses,EnclosingMethod,Signature
-keep class com.bitperfect.rootdac.xposed.** { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * extends de.robv.android.xposed.XC_MethodHook { *; }
-keepnames class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# Keep Root out-of-process app_process64 fallback daemon & inner classes
-keep class com.bitperfect.rootdac.root.** { *; }
