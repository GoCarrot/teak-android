package io.teak.app.java.dev.store;

import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import io.teak.app.java.dev.R;
import io.teak.sdk.Teak;

/**
 * Launcher for the amazon flavor: a minimal screen that identifies a Teak user, verifies the
 * Appstore SDK license at launch so getAppstoreSDKMode resolves, and exposes a button that starts
 * an Amazon purchase. Teak's registered listener reports is_sandbox in the purchase-tracking
 * payload.
 *
 * Deliberately separate from the shared demo MainActivity so the google flavor stays untouched.
 */
public class AmazonSandboxActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_amazon_sandbox);

        final String userId = "native-" + Build.MODEL.toLowerCase();
        Teak.identifyUser(userId, new Teak.UserConfiguration());

        AmazonSandboxHarness.verifyLicenseAtLaunch(this);

        final TextView skuLabel = findViewById(R.id.sku_label);
        skuLabel.setText(getString(R.string.amazon_sandbox_sku));

        final Button purchaseButton = findViewById(R.id.purchase_button);
        purchaseButton.setOnClickListener(v -> AmazonSandboxHarness.purchase(this));
    }
}
