package io.teak.sdk;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OkhttpNpeTest {

    private static NullPointerException npeFromClass(String className) {
        NullPointerException e = new NullPointerException("test");
        StackTraceElement frame = new StackTraceElement(className, "doSomething", "Foo.java", 42);
        e.setStackTrace(new StackTraceElement[] {frame});
        return e;
    }

    @Test
    public void okhttpNpe_isTransient() {
        assertTrue(Request.isOkhttpNpe(npeFromClass("com.android.okhttp.internal.http.HttpEngine")));
    }

    @Test
    public void okhttpSubpackageNpe_isTransient() {
        assertTrue(Request.isOkhttpNpe(npeFromClass("com.android.okhttp.OkHttpClient")));
    }

    @Test
    public void teakNpe_isNotTransient() {
        assertFalse(Request.isOkhttpNpe(npeFromClass("io.teak.sdk.Request")));
    }

    @Test
    public void androidNpe_isNotTransient() {
        assertFalse(Request.isOkhttpNpe(npeFromClass("android.os.Handler")));
    }

    @Test
    public void emptyStackTrace_isNotTransient() {
        NullPointerException e = new NullPointerException("test");
        e.setStackTrace(new StackTraceElement[] {});
        assertFalse(Request.isOkhttpNpe(e));
    }
}
