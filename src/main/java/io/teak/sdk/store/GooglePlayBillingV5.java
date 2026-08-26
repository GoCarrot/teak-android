package io.teak.sdk.store;

import android.content.Context;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;

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

public class GooglePlayBillingV5 implements Unobfuscable, IStore, PurchasesUpdatedListener, BillingClientStateListener {
    private final BillingClient billingClient;

    public GooglePlayBillingV5(Context context) {
        this.billingClient = BillingClient.newBuilder(context)
                                 .setListener(this)
                                 .enablePendingPurchases()
                                 .build();
        Teak.log.i("billing.google.v5", "Google Play Billing v5 registered.");

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
                final List<String> skusInPurchase = purchase.getProducts();
                for (String purchaseSku : skusInPurchase) {
                    final Map<String, Object> payload = new HashMap<>();
                    payload.put("purchase_token", purchase.getPurchaseToken());
                    payload.put("purchase_time", purchase.getPurchaseTime());
                    payload.put("product_id", purchaseSku);
                    payload.put("order_id", purchase.getOrderId());

                    final QueryProductDetailsParams params = QueryProductDetailsParams
                                                                 .newBuilder()
                                                                 .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                                                                                                               .setProductId(purchaseSku)
                                                                                                               .setProductType(BillingClient.ProductType.SUBS)
                                                                                                               .build()))
                                                                 .build();

                    this.billingClient.queryProductDetailsAsync(params, (ignored, productDetailsList) -> {
                        try {
                            if (!productDetailsList.isEmpty()) {
                                final ProductDetails productDetails = productDetailsList.get(0);
                                final ProductDetails.OneTimePurchaseOfferDetails otpDetails = productDetails.getOneTimePurchaseOfferDetails();
                                payload.put("price_amount_micros", otpDetails.getPriceAmountMicros());
                                payload.put("price_currency_code", otpDetails.getPriceCurrencyCode());

                                Teak.log.i("billing.google.v5.sku", "SKU Details retrieved.", Helpers.mm.h(purchaseSku, otpDetails.getPriceAmountMicros()));
                            } else {
                                Teak.log.e("billing.google.v5.sku", "SKU Details query failed.");
                            }

                            TeakEvent.postEvent(new PurchaseEvent(payload));
                        } catch (Exception e) {
                            Teak.log.exception(e);
                        }
                    });
                }
            }
        } catch (Exception e) {
            Teak.log.exception(e);
        }
    }
}
