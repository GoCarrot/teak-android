package io.teak.sdk.wrapper;

import androidx.annotation.NonNull;

public interface ISDKWrapper {
    enum EventType {
        NotificationLaunch,
        RewardClaim,
        RewardJwtIssued,
        RewardClaimPending,
        RewardClaimResolved,
        ForegroundNotification,
        AdditionalData,
        LaunchedFromLink,
        PostLaunchSummary,
        UserData,
        ConfigurationData
    }
    void sdkSendMessage(@NonNull EventType eventType, @NonNull String eventData);
}
