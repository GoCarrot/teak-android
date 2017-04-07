/* Teak -- Copyright (C) 2016 GoCarrot Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.teak.sdk;

import android.support.annotation.NonNull;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

class RemoteConfiguration {
    private static final String LOG_TAG = "Teak.RemoteConfig";

    public final AppConfiguration appConfiguration;
    private final String hostname;
    public final String sdkSentryDsn;
    public final String appSentryDsn;
    public final String gcmSenderId;

    // region Event Listener
    public interface EventListener {
        void onConfigurationReady(RemoteConfiguration configuration);
    }

    private static final Object eventListenersMutex = new Object();
    private static ArrayList<EventListener> eventListeners = new ArrayList<>();

    public static void addEventListener(EventListener e) {
        synchronized (eventListenersMutex) {
            if (!eventListeners.contains(e)) {
                eventListeners.add(e);
            }
        }
    }

    public static void removeEventListener(EventListener e) {
        synchronized (eventListenersMutex) {
            eventListeners.remove(e);
        }
    }
    // endregion

    private RemoteConfiguration(@NonNull AppConfiguration appConfiguration, @NonNull String hostname, String sdkSentryDsn, String appSentryDsn, String gcmSenderId) {
        this.appConfiguration = appConfiguration;
        this.hostname = hostname;
        this.appSentryDsn = appSentryDsn;
        this.sdkSentryDsn = sdkSentryDsn;
        this.gcmSenderId = gcmSenderId;
    }

    public static void requestConfigurationForApp(final Session session) {
        HashMap<String, Object> payload = new HashMap<>();
        payload.put("id", session.appConfiguration.appId);
        payload.put("deep_link_routes", DeepLink.getRouteNamesAndDescriptions());

        new Thread(new Request("gocarrot.com", "/games/" + session.appConfiguration.appId + "/settings.json", payload, session) {
            @Override
            protected void done(int responseCode, String responseBody) {
                try {
                    JSONObject response = new JSONObject(responseBody);

                    RemoteConfiguration configuration = new RemoteConfiguration(session.appConfiguration,
                            response.isNull("auth") ? "gocarrot.com" : response.getString("auth"),
                            nullInsteadOfEmpty(response.isNull("sdk_sentry_dsn") ? null : response.getString("sdk_sentry_dsn")),
                            nullInsteadOfEmpty(response.isNull("app_sentry_dsn") ? null : response.getString("app_sentry_dsn")),
                            nullInsteadOfEmpty(response.isNull("gcm_sender_id") ? null : response.getString("gcm_sender_id")));

                    synchronized (eventListenersMutex) {
                        for (EventListener e : RemoteConfiguration.eventListeners) {
                            e.onConfigurationReady(configuration);
                        }
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Error processing settings.json " + Log.getStackTraceString(e));
                }
            }
        }).start();
    }

    // region Accessors
    public String getHostnameForEndpoint(@NonNull String endpoint) {
        return this.hostname;
    }
    // endregion

    // region Helpers
    private static String nullInsteadOfEmpty(String input) {
        if(input != null && !input.isEmpty()) {
            return input;
        }
        return null;
    }
    // endregion

    public Map<String, Object> to_h() {
        HashMap<String, Object> ret = new HashMap<>();
        ret.put("hostname", this.hostname);
        ret.put("sdkSentryDsn", this.sdkSentryDsn);
        ret.put("appSentryDsn", this.appSentryDsn);
        return ret;
    }

    @Override
    public String toString() {
        try {
            return String.format(Locale.US, "%s: %s", super.toString(), Teak.formatJSONForLogging(new JSONObject(this.to_h())));
        } catch (Exception ignored) {
            return super.toString();
        }
    }
}
