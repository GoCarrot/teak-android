package io.teak.sdk.activity;

import android.os.Bundle;

import io.teak.sdk.Teak;
import io.teak.sdk.Helpers;
import android.app.Activity;
import io.teak.sdk.Unobfuscable;
import android.content.pm.PackageManager;

public class PermissionRequest extends Activity implements Unobfuscable {
    static final int REQUEST_CODE = 1946157056;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        doRequest();
    }

    private void doRequest() {
        Teak.log.i("requestNotificationPermissions.doRequest", "Requesting push permissions");

        if (!Teak.isEnabled()) {
            Teak.log.e("requestNotificationPermissions.doRequest", "Teak is not enabled");
            return;
        }

        requestPermissions(new String[] {Teak.NOTIFICATION_PERMISSION}, REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        Teak.log.trace("requestNotificationPermissions.onRequestPermissionsResult");
        if (requestCode != REQUEST_CODE) {
            Teak.log.e("permission_request.onRequestPermissionsResult", "Result not for our request?", Helpers.mm.h("requestCode", requestCode, "permissions", permissions));
            finish();
            return;
        }

        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Teak.Instance.onNotificationPermissionResult(granted);

        finish();
        return;
    }
}
