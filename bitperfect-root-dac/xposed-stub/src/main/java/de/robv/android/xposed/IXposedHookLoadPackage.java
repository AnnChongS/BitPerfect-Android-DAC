package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Xposed API 82 compileOnly stub for package load hook.
 */
public interface IXposedHookLoadPackage extends IXposedMod {
    void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
