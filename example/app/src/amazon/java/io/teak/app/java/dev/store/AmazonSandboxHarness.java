package io.teak.app.java.dev.store;

import android.content.Context;
import android.util.Log;

import com.amazon.device.iap.PurchasingService;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import io.teak.app.java.dev.R;

/**
 * Amazon sandbox-detection helpers.
 *
 * verifyLicenseAtLaunch reflectively calls LicensingService.verifyLicense so the Appstore SDK
 * resolves its mode (getAppstoreSDKMode flips from UNKNOWN once verify completes). Reflection keeps
 * this compiling against the legacy IAP v2 jar, which has no LicensingService — that class is only
 * present at runtime under Appstore SDK 3.x or Amazon's standalone DRM lib.
 *
 * purchase starts an Amazon purchase; Teak's registered listener handles the response and reports
 * is_sandbox in the purchase-tracking payload.
 */
public final class AmazonSandboxHarness {
    private static final String TAG = "Teak.Example";

    private AmazonSandboxHarness() {}

    public static void verifyLicenseAtLaunch(Context context) {
        if (!context.getResources().getBoolean(R.bool.amazon_verify_license_at_launch)) {
            Log.i(TAG, "verifyLicense at launch disabled; getAppstoreSDKMode reads UNKNOWN until verify runs.");
            return;
        }
        verifyLicense(context);
    }

    public static void purchase(Context context) {
        final String sku = context.getResources().getString(R.string.amazon_sandbox_sku);
        try {
            Log.i(TAG, "Starting Amazon purchase for sku=" + sku + " requestId=" + PurchasingService.purchase(sku));
        } catch (Throwable t) {
            Log.e(TAG, "PurchasingService.purchase failed for sku=" + sku, t);
        }
    }

    private static void verifyLicense(Context context) {
        try {
            final Class<?> licensingService = Class.forName("com.amazon.device.drm.LicensingService");

            Method verifyLicense = null;
            for (Method m : licensingService.getMethods()) {
                if ("verifyLicense".equals(m.getName())) {
                    verifyLicense = m;
                    break;
                }
            }
            if (verifyLicense == null) {
                Log.i(TAG, "LicensingService.verifyLicense not found; skipping.");
                return;
            }

            // verifyLicense takes a Context and a callback interface whose type isn't on the compile
            // classpath; supply the context and a no-op proxy for the callback.
            final Class<?>[] paramTypes = verifyLicense.getParameterTypes();
            final Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                if (paramTypes[i].isInstance(context)) {
                    args[i] = context;
                } else if (paramTypes[i].isInterface()) {
                    args[i] = Proxy.newProxyInstance(paramTypes[i].getClassLoader(),
                        new Class<?>[] {paramTypes[i]},
                        (proxy, method, methodArgs) -> {
                            Log.i(TAG, "verifyLicense callback: " + method.getName());
                            return defaultValue(method.getReturnType());
                        });
                } else {
                    args[i] = null;
                }
            }

            verifyLicense.invoke(null, args);
            Log.i(TAG, "LicensingService.verifyLicense invoked.");
        } catch (ClassNotFoundException e) {
            Log.i(TAG, "LicensingService absent (legacy IAP v2 build); skipping verifyLicense.");
        } catch (Throwable t) {
            Log.e(TAG, "verifyLicense reflection failed.", t);
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) return null;
        if (returnType == boolean.class) return false;
        if (returnType == char.class) return '\0';
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0f;
        if (returnType == double.class) return 0d;
        if (returnType == short.class) return (short) 0;
        if (returnType == byte.class) return (byte) 0;
        return 0;
    }
}
