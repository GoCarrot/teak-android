package io.teak.sdk.io;

import android.app.Notification;
import android.content.Context;
import androidx.annotation.NonNull;
import io.teak.sdk.TeakNotification;

public interface IAndroidNotification {
    void cancelNotification(int platformId, @NonNull Context context, String groupKey);

    void displayNotification(@NonNull Context context, @NonNull TeakNotification teakNotification, @NonNull Notification nativeNotification);
}
