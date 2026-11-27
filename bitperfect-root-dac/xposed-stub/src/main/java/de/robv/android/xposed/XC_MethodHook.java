package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Xposed API 82 compileOnly stub for XC_MethodHook.
 */
public abstract class XC_MethodHook {

    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        public Object getResult() {
            return null;
        }

        public void setResult(Object result) {
        }

        public Throwable getThrowable() {
            return null;
        }

        public boolean hasThrowable() {
            return false;
        }

        public void setThrowable(Throwable throwable) {
        }

        public Object getResultOrThrowable() throws Throwable {
            return null;
        }
    }

    public class Unhook {
        public Member getHookedMethod() {
            return null;
        }

        public XC_MethodHook getCallback() {
            return XC_MethodHook.this;
        }

        public void unhook() {
        }
    }
}
