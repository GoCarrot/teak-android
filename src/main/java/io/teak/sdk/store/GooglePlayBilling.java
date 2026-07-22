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
// supplies the runtime library; this class is runtime-compatible across billing 7, 8, and 9.
//
// Only one billing API the tracking path uses diverges across those versions: the
// product-details query callback hands back a List<ProductDetails> on 7 and a
// QueryProductDetailsResult on 8+. That listener is registered through a dynamic proxy whose
// sole job is to normalize the second argument back to List<ProductDetails>. Every other call —
// client construction, enablePendingPurchases, purchases-updated, the product-details query
// itself, and the price read — is a single native call site valid on all three versions
// (verified against the 7.1.1 / 8.3.0 / 9.1.0 runtime interfaces).
public class GooglePlayBilling implements Unobfuscable, IStore, PurchasesUpdatedListener, BillingClientStateListener {
    private final BillingClient billingClient;

    public GooglePlayBilling(Context context) {
        this.billingClient = BillingClient.newBuilder(context)
                                 .setListener(this)
                                 .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                                 .build();
        Teak.log.i("billing.google", "Google Play Billing registered.");

        this.billingClient.startConnection(this);
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

    // The one version-divergent call. Billing 7 invokes the listener with a List<ProductDetails>;
    // billing 8+ invokes it with a QueryProductDetailsResult. We implement whichever
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

                Teak.log.i("billing.google.sku", "SKU Details retrieved.", Helpers.mm.h(purchaseSku, otpDetails.getPriceAmountMicros()));
            } else {
                Teak.log.e("billing.google.sku", "SKU Details query failed.");
            }

            TeakEvent.postEvent(new PurchaseEvent(payload));
        } catch (Exception e) {
            Teak.log.exception(e);
        }
    }

    // Billing 7: the argument is already the List<ProductDetails>. Billing 8+: it is a
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
