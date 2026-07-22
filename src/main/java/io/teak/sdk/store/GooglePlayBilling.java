package io.teak.sdk.store;

import android.content.Context;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.teak.sdk.Helpers;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.Unobfuscable;
import io.teak.sdk.event.PurchaseEvent;

// Single Google Play Billing store. Teak compiles against billing 7.1.1 but the host game
// supplies the runtime library; this class is runtime-compatible across billing 6, 7, 8, and 9.
//
// Two billing APIs the tracking path uses diverge across those versions:
// - enablePendingPurchases: 6.x only has the no-arg overload (PendingPurchasesParams doesn't
//   exist below billing 7.0); 7.x has both; 8+ only has the PendingPurchasesParams overload (the
//   no-arg one was removed). Gated by a Class.forName check on PendingPurchasesParams.
// - the product-details query callback hands back a List<ProductDetails> on 6-7 and a
//   QueryProductDetailsResult on 8+. That listener is registered through a dynamic proxy whose
//   sole job is to normalize the second argument back to List<ProductDetails>.
// Every other call — client construction, purchases-updated, the product-details query itself,
// and the price read — is a single native call site valid on all four versions (verified against
// the 6.2.1 / 7.1.1 / 8.3.0 / 9.1.0 runtime interfaces).
public class GooglePlayBilling implements Unobfuscable, IStore, PurchasesUpdatedListener, BillingClientStateListener {
    private final BillingClient billingClient;

    public GooglePlayBilling(Context context) {
        // Log the version before registering so a failure in build()/startConnection() still
        // tells us which billing version was being set up.
        Teak.log.i("billing.google", "Registering Google Play Billing.", Helpers.mm.h("billing_version", billingLibraryVersion()));

        this.billingClient = enablePendingPurchases(BillingClient.newBuilder(context).setListener(this), pendingPurchasesParamsAvailable()).build();

        this.billingClient.startConnection(this);
    }

    // billing 7+ has the PendingPurchasesParams-typed overload; billing 6.x only has the
    // deprecated no-arg one (removed again in 8+, which is why 7+ always prefers the typed
    // overload here). Gate on class presence rather than calling the no-arg overload
    // unconditionally, since that one doesn't exist at all on 8+.
    //
    // Package-private + boolean-injectable so both selections are unit-testable against a mocked
    // Builder — CI always runs against the 7.1.1 compileOnly floor, so pendingPurchasesParamsAvailable()
    // itself can only ever probe "present" there; injecting the flag lets the no-arg (6.x) branch
    // get a real regression guard too.
    static BillingClient.Builder enablePendingPurchases(BillingClient.Builder builder, boolean pendingPurchasesParamsAvailable) {
        if (pendingPurchasesParamsAvailable) {
            return builder.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build());
        }
        return builder.enablePendingPurchases();
    }

    // Package-private so the version gate is unit-testable without a real BillingClient.Builder
    // (which needs the Android runtime, per CreateStoreTest).
    static boolean pendingPurchasesParamsAvailable() {
        try {
            Class.forName("com.android.billingclient.api.PendingPurchasesParams");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // The Play Billing version the host game actually ships, read reflectively. A direct reference
    // to BillingClient.BuildConfig.VERSION_NAME would inline to Teak's compileOnly version (7.1.1)
    // at compile time; reading the field reflectively yields the real runtime version (6, 7, 8, or 9).
    // Best-effort — returns "unknown" if the constant can't be read.
    private static String billingLibraryVersion() {
        try {
            return (String) Class.forName("com.android.billingclient.BuildConfig").getField("VERSION_NAME").get(null);
        } catch (Throwable e) {
            return "unknown";
        }
    }

    @Override
    public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
    }

    @Override
    public void onBillingServiceDisconnected() {
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult, @Nullable List<Purchase> purchaseList) {
        if (purchaseList == null) {
            return;
        }

        try {
            for (Purchase purchase : purchaseList) {
                for (String purchaseSku : purchase.getProducts()) {
                    final Map<String, Object> payload = new HashMap<>();
                    payload.put("purchase_token", purchase.getPurchaseToken());
                    payload.put("purchase_time", purchase.getPurchaseTime());
                    payload.put("product_id", purchaseSku);
                    payload.put("order_id", purchase.getOrderId());

                    final QueryProductDetailsParams params = QueryProductDetailsParams
                                                                 .newBuilder()
                                                                 .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                                                                         .setProductId(purchaseSku)
                                                                         .setProductType(BillingClient.ProductType.INAPP)
                                                                         .build()))
                                                                 .build();

                    this.billingClient.queryProductDetailsAsync(params, productDetailsListener(purchaseSku, payload));
                }
            }
        } catch (Exception e) {
            Teak.log.exception(e);
        }
    }

    // Billing 6-7 invokes the listener with a List<ProductDetails>; billing 8+ invokes it with a
    // QueryProductDetailsResult. We implement whichever
    // ProductDetailsResponseListener the runtime declares via a dynamic proxy so the same code
    // links against every version, and normalize the second argument in onProductDetails.
    private ProductDetailsResponseListener productDetailsListener(final String purchaseSku, final Map<String, Object> payload) {
        final InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if (method.getDeclaringClass() == Object.class) {
                    return objectMethod(proxy, method, args);
                }
                if ("onProductDetailsResponse".equals(method.getName()) && args != null && args.length == 2) {
                    onProductDetails(purchaseSku, payload, normalizeProductDetails(args[1]));
                }
                return defaultReturn(method.getReturnType());
            }
        };
        return (ProductDetailsResponseListener) Proxy.newProxyInstance(
            ProductDetailsResponseListener.class.getClassLoader(),
            new Class[] {ProductDetailsResponseListener.class},
            handler);
    }

    private void onProductDetails(String purchaseSku, Map<String, Object> payload, @Nullable List<ProductDetails> productDetailsList) {
        try {
            if (productDetailsList != null && !productDetailsList.isEmpty()) {
                final ProductDetails.OneTimePurchaseOfferDetails otpDetails = productDetailsList.get(0).getOneTimePurchaseOfferDetails();
                payload.put("price_amount_micros", otpDetails.getPriceAmountMicros());
                payload.put("price_currency_code", otpDetails.getPriceCurrencyCode());

                Teak.log.i("billing.google.sku", "SKU Details retrieved.", payload);
            } else {
                Teak.log.e("billing.google.sku", "SKU Details query failed.", payload);
            }

            TeakEvent.postEvent(new PurchaseEvent(payload));
        } catch (Exception e) {
            Teak.log.exception(e);
        }
    }

    // Billing 6-7: the argument is already the List<ProductDetails>. Billing 8+: it is a
    // QueryProductDetailsResult whose getProductDetailsList() yields the List. Reflection is
    // confined to this single unwrap — there is no second code path to fall into.
    // Package-private so the version-divergence unwrap is unit-testable without a real BillingClient.
    @SuppressWarnings("unchecked")
    @Nullable
    static List<ProductDetails> normalizeProductDetails(@Nullable Object response) {
        if (response instanceof List) {
            return (List<ProductDetails>) response;
        }
        if (response == null) {
            return null;
        }
        try {
            final Method getProductDetailsList = response.getClass().getMethod("getProductDetailsList");
            return (List<ProductDetails>) getProductDetailsList.invoke(response);
        } catch (Exception e) {
            Teak.log.exception(e);
            return null;
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            default:
                return "GooglePlayBilling.ProductDetailsResponseListener";
        }
    }

    private static Object defaultReturn(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType.isPrimitive() && returnType != void.class) {
            return 0;
        }
        return null;
    }
}
