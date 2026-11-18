package io.teak.sdk.core;

import java.security.InvalidParameterException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import io.teak.sdk.Request;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.event.RemoteConfigurationEvent;
import io.teak.sdk.json.JSONObject;

public class UserProfile extends Request {
    private final Map<String, Object> stringAttributes;
    private final Map<String, Object> numberAttributes;
    private final String context;

    private long firstSetTime = 0L;
    private ScheduledFuture<?> scheduledSend;

    UserProfile(@NonNull Session session, @NonNull Map<String, Object> userProfile) {
        super(RemoteConfiguration.getHostnameForEndpoint("/me/profile", Request.remoteConfiguration), "/me/profile", new HashMap<>(), session, null, true);

        if (!(userProfile.get("context") instanceof String)) {
            throw new InvalidParameterException("User Profile value 'context' is not a String");
        }
        if (!(userProfile.get("string_attributes") instanceof Map)) {
            throw new InvalidParameterException("User Profile value 'string_attributes' is not a Map");
        }
        if (!(userProfile.get("number_attributes") instanceof Map)) {
            throw new InvalidParameterException("User Profile value 'number_attributes' is not a Map");
        }

        // Insert JSONObject.NULL so that we feed the server what it wants
        @SuppressWarnings("unchecked")
        Map<String, Object> string_attributes = (Map<String, Object>) userProfile.get("string_attributes");
        for (String key : string_attributes.keySet()) {
            if (string_attributes.get(key) == null) {
                string_attributes.put(key, JSONObject.NULL);
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> number_attributes = (Map<String, Object>) userProfile.get("number_attributes");
        for (String key : number_attributes.keySet()) {
            if (number_attributes.get(key) == null) {
                number_attributes.put(key, JSONObject.NULL);
            }
        }

        this.stringAttributes = string_attributes;
        this.numberAttributes = number_attributes;
        this.context = (String) userProfile.get("context");
    }

    @Override
    public void run() {
        // If this has no scheduledSend then it has no pending updates
        if (this.scheduledSend != null) {
            this.scheduledSend.cancel(false);
            this.scheduledSend = null;

            this.payload.put("context", this.context);
            this.payload.put("string_attributes", this.stringAttributes);
            this.payload.put("number_attributes", this.numberAttributes);

            final long msElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this.firstSetTime);
            this.payload.put("ms_since_first_event", msElapsed);

            renewRequestId();
            super.run();

            this.firstSetTime = 0;
        }
    }

    public void setNumericAttribute(@NonNull String key, double value) {
        setAttribute(this.numberAttributes, key, value);
    }

    public void setStringAttribute(@NonNull String key, String value) {
        setAttribute(this.stringAttributes, key, value);
    }

    private void setAttribute(@NonNull final Map<String, Object> map, @NonNull final String key, @NonNull final Object value) {
        if (map.containsKey(key)) {
            if (this.firstSetTime == 0) {
                this.firstSetTime = System.nanoTime();
            }

            TeakCore.operationQueue.execute(() -> {
                boolean safeNotEqual = true;
                try {
                    safeNotEqual = !value.equals(map.get(key));
                } catch (Exception ignored) {
                }

                if (safeNotEqual) {
                    if (UserProfile.this.scheduledSend != null) {
                        UserProfile.this.scheduledSend.cancel(false);
                    }

                    // Update value
                    map.put(key, value);

                    UserProfile.this.scheduledSend = TeakCore.operationQueue.schedule(UserProfile.this,
                        (long) (UserProfile.this.batch.time * 1000.0f), TimeUnit.MILLISECONDS);
                }
            });
        }
    }

    static {
        TeakEvent.addEventListener(event -> {
            if (event.eventType.equals(RemoteConfigurationEvent.Type)) {
                RemoteConfiguration remoteConfiguration = ((RemoteConfigurationEvent) event).remoteConfiguration;
            }
        });
    }
}
