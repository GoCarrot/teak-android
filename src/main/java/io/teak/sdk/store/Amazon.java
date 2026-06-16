package io.teak.sdk.store;

import android.content.Context;

import com.amazon.device.iap.PurchasingListener;
import com.amazon.device.iap.PurchasingService;
import com.amazon.device.iap.model.Product;
import com.amazon.device.iap.model.ProductDataResponse;
import com.amazon.device.iap.model.PurchaseResponse;
import com.amazon.device.iap.model.PurchaseUpdatesResponse;
import com.amazon.device.iap.model.Receipt;
import com.amazon.device.iap.model.RequestId;
import com.amazon.device.iap.model.UserData;
import com.amazon.device.iap.model.UserDataResponse;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import io.teak.sdk.Helpers.mm;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.Unobfuscable;
import io.teak.sdk.event.LifecycleEvent;
import io.teak.sdk.event.PurchaseEvent;
import io.teak.sdk.event.PurchaseFailedEvent;

public class Amazon implements Unobfuscable, IStore, PurchasingListener {
    private final HashMap<RequestId, Map<String, Object>> skuDetailsRequestMap = new HashMap<>();

    public Amazon(Context context) {
        try {
            PurchasingService.registerListener(context, this);

            Teak.log.i("billing.amazon.v2", "Amazon In-App Purchasing 2.0 registered.", mm.h("sandboxMode", AmazonSandbox.isSandboxMode()));

            TeakEvent.addEventListener(event -> {
                if (event.eventType.equals(LifecycleEvent.Resumed)) {
                    PurchasingService.getUserData();
                }
            });
        } catch (Exception e) {
            Teak.log.exception(e);
        }
    }

    @Override
    public void onUserDataResponse(UserDataResponse userDataResponse) {
        //            if (userDataResponse.getRequestStatus() == UserDataResponse.RequestStatus.SUCCESSFUL) {
        //                final UserData userData = userDataResponse.getUserData();
        //
        //                final String storeUserId = userData.getUserId();
        //                final String storeMarketplace = userData.getMarketplace();
        //            }
    }

    @Override
    public void onProductDataResponse(ProductDataResponse productDataResponse) {
        final RequestId requestId = productDataResponse.getRequestId();
        final Map<String, Object> payload = skuDetailsRequestMap.remove(requestId);

        if (productDataResponse.getRequestStatus() == ProductDataResponse.RequestStatus.SUCCESSFUL && payload != null) {
            final Map<String, Product> skuMap = productDataResponse.getProductData();

            for (Map.Entry<String, Product> entry : skuMap.entrySet()) {
                final String price = entry.getValue().getPrice();
                Teak.log.i("billing.amazon.v2.sku", "SKU Details retrieved.", mm.h(entry.getKey(), price));
                payload.put("price_string", price);

                TeakEvent.postEvent(new PurchaseEvent(payload));
            }
        } else {
            Teak.log.e("billing.amazon.v2.sku", "SKU Details query failed.");
        }
    }

    @Override
    public void onPurchaseResponse(PurchaseResponse purchaseResponse) {
        if (purchaseResponse.getRequestStatus() == PurchaseResponse.RequestStatus.SUCCESSFUL) {
            try {
                final Receipt receipt = purchaseResponse.getReceipt();
                final UserData userData = purchaseResponse.getUserData();

                final Map<String, Object> payload = new HashMap<>();
                payload.put("purchase_token", receipt.getReceiptId());
                payload.put("purchase_time_string", receipt.getPurchaseDate());
                payload.put("product_id", receipt.getSku());
                payload.put("store_marketplace", userData.getMarketplace());
                payload.put("is_sandbox", AmazonSandbox.isSandboxMode());

                final HashSet<String> skus = new HashSet<>();
                skus.add(receipt.getSku());
                this.skuDetailsRequestMap.put(PurchasingService.getProductData(skus), payload);
            } catch (Exception e) {
                Teak.log.exception(e);
            }
        } else {
            TeakEvent.postEvent(new PurchaseFailedEvent(-1, null));
        }
    }

    @Override
    public void onPurchaseUpdatesResponse(PurchaseUpdatesResponse purchaseUpdatesResponse) {
    }
}
