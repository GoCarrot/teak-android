package io.teak.sdk.core;

import android.net.Uri;
import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import io.teak.sdk.Request;

/**
 * Production HTTP boundary for {@link RewardClaimManager}. Routes through {@link Request}
 * so signing, mocking, and endpoint configuration carry over from the rest of the SDK.
 *
 * <p>Both the poll ({@code GET /claim_status}) and the ack ({@code POST /claim_ack}) target
 * the same {@code rewards.gocarrot.com} host as the click POST. Per the taro spec, params
 * ride on the query string for the GET and in the body for the POST.
 */
class DefaultClaimRequestSender implements RewardClaimManager.ClaimRequestSender {
    static final DefaultClaimRequestSender INSTANCE = new DefaultClaimRequestSender();

    private static final String HOSTNAME = "rewards.gocarrot.com";

    @Override
    public void sendPoll(@NonNull String eventId, @NonNull String teakAppId,
        @NonNull String clickingUserId, @NonNull RewardClaimManager.ReplyHandler handler) {
        final String endpoint = new Uri.Builder()
                                    .path("/claim_status")
                                    .appendQueryParameter("teak_app_id", teakAppId)
                                    .appendQueryParameter("clicking_user_id", clickingUserId)
                                    .appendQueryParameter("event_id", eventId)
                                    .build()
                                    .toString();
        Request.submit(HOSTNAME, "GET", endpoint, new HashMap<>(), Session.NullSession,
            (responseCode, responseBody) -> handler.onReply(responseCode, responseBody));
    }

    @Override
    public void sendAck(@NonNull String eventId, @NonNull String teakAppId,
        @NonNull String clickingUserId, @NonNull RewardClaimManager.ReplyHandler handler) {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("event_id", eventId);
        payload.put("teak_app_id", teakAppId);
        payload.put("clicking_user_id", clickingUserId);
        Request.submit(HOSTNAME, "/claim_ack", payload, Session.NullSession,
            (responseCode, responseBody) -> handler.onReply(responseCode, responseBody));
    }

    @Override
    public void sendSweep(@NonNull String teakAppId, @NonNull String clickingUserId,
        @NonNull RewardClaimManager.ReplyHandler handler) {
        final String endpoint = new Uri.Builder()
                                    .path("/claims")
                                    .appendQueryParameter("teak_app_id", teakAppId)
                                    .appendQueryParameter("clicking_user_id", clickingUserId)
                                    .build()
                                    .toString();
        Request.submit(HOSTNAME, "GET", endpoint, new HashMap<>(), Session.NullSession,
            (responseCode, responseBody) -> handler.onReply(responseCode, responseBody));
    }
}
