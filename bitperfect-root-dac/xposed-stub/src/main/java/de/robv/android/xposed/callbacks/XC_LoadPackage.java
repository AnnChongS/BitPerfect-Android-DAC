package de.robv.android.xposed.callbacks;

/**
 * Xposed API 82 compileOnly stub for XC_LoadPackage.
 */
public abstract class XC_LoadPackage {

    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
