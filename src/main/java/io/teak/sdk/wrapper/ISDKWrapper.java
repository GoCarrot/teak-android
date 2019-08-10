package io.teak.sdk.wrapper;

import androidx.annotation.NonNull;

public interface ISDKWrapper {
    enum EventType {
        NotificationLaunch,
        RewardClaim,
        ForegroundNotification
    }
    void sdkSendMessage(@NonNull EventType eventType, @NonNull String eventData);
}
