package io.teak.sdk;

import java.math.BigInteger;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.core.Executors;
import io.teak.sdk.core.Session;
import io.teak.sdk.event.RemoteConfigurationEvent;
import io.teak.sdk.event.TrackEventEvent;
import io.teak.sdk.io.DefaultHttpRequest;
import io.teak.sdk.io.IHttpRequest;
import io.teak.sdk.json.JSONObject;

public class Request implements Runnable {
    public static final int DEFAULT_PORT = 443;
    public static final int MOCKED_PORT = 8080;
    private final String endpoint;
    private final String hostname;
    private final String method;
    protected final Map<String, Object> payload;
    private final Session session;
    private final String requestId;
    private final Callback callback;
    protected boolean sent;

    @SuppressWarnings("WeakerAccess")
    protected final boolean blackhole;
    @SuppressWarnings("WeakerAccess")
    protected final RetryConfiguration retry;
    @SuppressWarnings("WeakerAccess")
    protected final BatchConfiguration batch;

    ///// Mini-configs

    static class RetryConfiguration {
        float jitter;
        float[] times;
        int retryIndex;

        RetryConfiguration() {
            this.jitter = 0.0f;
            this.times = new float[] {};
            this.retryIndex = 0;
        }
    }

    public static class BatchConfiguration {
        public long count;
        public float time;
        public float maximumWaitTime;

        BatchConfiguration() {
            this.count = 1;
            this.time = 0.0f;
            this.maximumWaitTime = 0.0f;
        }
    }

    ///// Callback

    public interface Callback {
        void onRequestCompleted(int responseCode, String responseBody);
    }

    ///// Common configuration

    private static String teakApiKey;
    public static void setTeakApiKey(@NonNull String apiKey) {
        Request.teakApiKey = apiKey;
    }
    public static boolean hasTeakApiKey() {
        return Request.teakApiKey != null && Request.teakApiKey.length() > 0;
    }

    private static final Map<String, Object> configurationPayload = new HashMap<>();

    // TODO: Can't do this as a static-init block, will screw up unit tests
    static {
        TeakConfiguration.addEventListener(configuration -> {
            Request.teakApiKey = configuration.appConfiguration.apiKey;

            configurationPayload.put("sdk_version", Teak.Version);
            configurationPayload.put("game_id", configuration.appConfiguration.appId);
            configurationPayload.put("app_version", String.valueOf(configuration.appConfiguration.appVersion));
            configurationPayload.put("app_version_name", String.valueOf(configuration.appConfiguration.appVersionName));
            configurationPayload.put("bundle_id", configuration.appConfiguration.bundleId);
            configurationPayload.put("appstore_name", configuration.appConfiguration.storeId);
            if (configuration.appConfiguration.installerPackage != null) {
                configurationPayload.put("installer_package", configuration.appConfiguration.installerPackage);
            }

            configurationPayload.put("device_id", configuration.deviceConfiguration.deviceId);
            configurationPayload.put("sdk_platform", configuration.deviceConfiguration.platformString);
            configurationPayload.put("device_manufacturer", configuration.deviceConfiguration.deviceManufacturer);
            configurationPayload.put("device_model", configuration.deviceConfiguration.deviceModel);
            configurationPayload.put("device_fallback", configuration.deviceConfiguration.deviceFallback);
            configurationPayload.put("device_memory_class", configuration.deviceConfiguration.memoryClass);

            if (configuration.debugConfiguration.isDebug()) {
                configurationPayload.put("debug", true);
            }
        });
    }

    ///// Remote Configuration

    protected static RemoteConfiguration remoteConfiguration;

    public static void registerStaticEventListeners() {
        TeakEvent.addEventListener(event -> {
            if (event.eventType.equals(RemoteConfigurationEvent.Type)) {
                Request.remoteConfiguration = ((RemoteConfigurationEvent) event).remoteConfiguration;
                configurationPayload.putAll(Request.remoteConfiguration.dynamicParameters);
            }
        });
    }

    ///// Batching

    private static abstract class BatchedRequest extends Request {
        private ScheduledFuture<?> scheduledFuture;
        private final List<Callback> callbacks = new LinkedList<>();
        final List<Map<String, Object>> batchContents = new LinkedList<>();
        long firstAddTime = 0L;

        BatchedRequest(@Nullable String hostname, @NonNull String endpoint, @NonNull Session session, boolean addStandardAttributes) {
            super(hostname, endpoint, new HashMap<>(), session, null, addStandardAttributes);
        }

        synchronized boolean add(@NonNull String endpoint, @Nullable Map<String, Object> payload, @Nullable Callback callback) {
            if (this.sent) {
                return false;
            }

            if (this.scheduledFuture != null && !this.scheduledFuture.cancel(false)) {
                requestExecutor.execute(this);
                return false;
            }

            if (this.batchContents.size() >= this.batch.count || this.batch.time < 0.0f) {
                return false;
            }

            if (this.firstAddTime == 0) {
                this.firstAddTime = System.nanoTime();

                if (this.batch.maximumWaitTime > 0.0f) {
                    Request.requestExecutor.schedule(this, (long) (this.batch.maximumWaitTime * 1000.0f), TimeUnit.MILLISECONDS);
                }
            }

            if (callback != null) {
                this.callbacks.add(callback);
            }

            if (payload != null) {
                this.batchContents.add(payload);
            }

            if (this.batch.time == 0.0f) {
                Request.requestExecutor.execute(this);
            } else {
                this.scheduledFuture = Request.requestExecutor.schedule(this, (long) (this.batch.time * 1000.0f), TimeUnit.MILLISECONDS);
            }
            return true;
        }

        @Override
        public synchronized void run() {
            final long elapsedSinceFirstAdd = System.nanoTime() - this.firstAddTime;
            this.payload.put("ms_since_first_event", TimeUnit.NANOSECONDS.toMillis(elapsedSinceFirstAdd));
            super.run();
        }

        @Override
        protected void onRequestCompleted(int responseCode, String responseBody) {
            super.onRequestCompleted(responseCode, responseBody);
            for (Callback callback : this.callbacks) {
                callback.onRequestCompleted(responseCode, responseBody);
            }
        }
    }

    private static class BatchedParsnipRequest extends BatchedRequest {
        private static final Object mutex = new Object();
        private static BatchedParsnipRequest currentBatch;

        static BatchedParsnipRequest getCurrentBatch(@Nullable String hostname, @NonNull Session session) {
            synchronized (mutex) {
                if (currentBatch == null || currentBatch.sent) {
                    currentBatch = new BatchedParsnipRequest(hostname, session);
                }
                return currentBatch;
            }
        }

        BatchedParsnipRequest(@Nullable String hostname, @NonNull Session session) {
            super(hostname, "/batch", session, false);
        }

        @Override
        synchronized boolean add(@NonNull String endpoint, @Nullable Map<String, Object> payload, @Nullable Callback callback) {
            final Map<String, Object> batchPayload = new HashMap<>(payload);

            // Parsnip needs the standard attributes in every event
            batchPayload.putAll(Request.configurationPayload);

            // Parsnip event name
            batchPayload.put("name", endpoint.startsWith("/") ? endpoint.substring(1) : endpoint);

            return super.add(endpoint, batchPayload, callback);
        }

        @Override
        public synchronized void run() {
            synchronized (mutex) {
                // Add batch elements
                this.payload.put("events", this.batchContents);
                super.run();
            }
        }
    }

    private static class BatchedTrackEventRequest extends BatchedRequest {
        private static final Object mutex = new Object();
        private static BatchedTrackEventRequest currentBatch;

        static BatchedTrackEventRequest getCurrentBatch(@Nullable String hostname, @NonNull Session session) {
            synchronized (mutex) {
                if (currentBatch == null || currentBatch.sent) {
                    currentBatch = new BatchedTrackEventRequest(hostname, session);
                }
                return currentBatch;
            }
        }

        private BatchedTrackEventRequest(@Nullable String hostname, @NonNull Session session) {
            super(hostname, "/me/events", session, true);
        }

        @Override
        synchronized boolean add(@NonNull String endpoint, @Nullable Map<String, Object> payload, @Nullable Callback callback) {
            // Check to see if current batchContents has action, and if so, sum the durations
            ListIterator<Map<String, Object>> itr = this.batchContents.listIterator();
            while (itr.hasNext()) {
                final Map<String, Object> current = itr.next();
                try {
                    if (TrackEventEvent.payloadEquals(current, payload)) {
                        current.put(TrackEventEvent.DurationKey,
                            integerSafeSumOrCurrent(current.get(TrackEventEvent.DurationKey),
                                payload.get(TrackEventEvent.DurationKey)));
                        current.put(TrackEventEvent.CountKey,
                            integerSafeSumOrCurrent(current.get(TrackEventEvent.CountKey),
                                payload.get(TrackEventEvent.CountKey)));
                        current.put(TrackEventEvent.SumOfSquaresKey,
                            integerSafeSumOrCurrent(current.get(TrackEventEvent.SumOfSquaresKey),
                                payload.get(TrackEventEvent.SumOfSquaresKey)));

                        itr.set(current);
                        return super.add(endpoint, null, callback);
                    }
                } catch (Exception ignored) {
                }
            }

            return super.add(endpoint, payload, callback);
        }

        private static Object integerSafeSumOrCurrent(Object current, Object addition) {
            if (current instanceof Number && addition instanceof Number) {
                if (current instanceof BigInteger && addition instanceof BigInteger) {
                    return ((BigInteger) current).add((BigInteger) addition);
                } else {
                    return ((Number) current).longValue() + ((Number) addition).longValue();
                }
            }
            return current;
        }

        @Override
        public synchronized void run() {
            synchronized (mutex) {
                // Turn SumOfSquares BigInteger into a string
                ListIterator<Map<String, Object>> itr = this.batchContents.listIterator();
                while (itr.hasNext()) {
                    final Map<String, Object> current = itr.next();
                    try {
                        if (current.containsKey(TrackEventEvent.SumOfSquaresKey)) {
                            Object sumOfSquares = current.get(TrackEventEvent.SumOfSquaresKey);
                            if (sumOfSquares instanceof BigInteger) {
                                current.put(TrackEventEvent.SumOfSquaresKey, ((BigInteger) sumOfSquares).toString(10));
                                itr.set(current);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }

                // Add batch elements
                this.payload.put("batch", this.batchContents);
                super.run();
            }
        }
    }

    ///// SDK interface

    static final ScheduledExecutorService requestExecutor = Executors.newSingleThreadScheduledExecutor();

    public static void submit(@NonNull String endpoint, @NonNull Map<String, Object> payload, @NonNull Session session) {
        submit(endpoint, payload, session, null);
    }

    public static void submit(@NonNull String endpoint, @NonNull Map<String, Object> payload, @NonNull Session session, @Nullable Callback callback) {
        submit(null, endpoint, payload, session, callback);
    }

    public static void submit(@Nullable String hostname, @NonNull String endpoint, @NonNull Map<String, Object> payload, @NonNull Session session) {
        submit(hostname, endpoint, payload, session, null);
    }

    public static void submit(@Nullable String hostname, final @NonNull String endpoint, final @NonNull Map<String, Object> payload, final @NonNull Session session, final @Nullable Callback callback) {
        submit(hostname, "POST", endpoint, payload, session, callback);
    }

    public static void submit(@Nullable String hostname, final @NonNull String method, final @NonNull String endpoint, final @NonNull Map<String, Object> payload, final @NonNull Session session, final @Nullable Callback callback) {
        if (hostname == null) {
            hostname = RemoteConfiguration.getHostnameForEndpoint(endpoint, Request.remoteConfiguration);
        }
        final String finalHostname = hostname;

        BatchedRequest batch = null;

        if ("parsnip.gocarrot.com".equals(hostname) &&
            !("/notification_received".equals(endpoint))) {
            batch = BatchedParsnipRequest.getCurrentBatch(hostname, session);
        } else if ("/me/events".equals(endpoint)) {
            batch = BatchedTrackEventRequest.getCurrentBatch(hostname, session);
        }

        if (batch != null) {
            if (!batch.add(endpoint, payload, callback)) {
                requestExecutor.execute(() -> submit(finalHostname, endpoint, payload, session, callback));
            }
        } else {
            requestExecutor.execute(new Request(hostname, method, endpoint, payload, session, callback, true));
        }
    }

    /////

    public Request(@Nullable String hostname, @NonNull String endpoint, @NonNull Map<String, Object> payload, @NonNull Session session, @Nullable Callback callback, boolean addStandardAttributes) {
        this(hostname, "POST", endpoint, payload, session, callback, addStandardAttributes);
    }

    public Request(@Nullable String hostname, @NonNull String method, @NonNull String endpoint, @NonNull Map<String, Object> payload, @NonNull Session session, @Nullable Callback callback, boolean addStandardAttributes) {
        if (!endpoint.startsWith("/")) {
            throw new IllegalArgumentException("Parameter 'endpoint' must start with '/' or things will break, and you will lose an hour of your life debugging. Number of times this exception has saved an ass: 1.");
        }

        this.hostname = hostname;
        this.method = method;
        this.endpoint = endpoint;
        this.payload = new HashMap<>(payload);
        this.session = session;
        this.requestId = UUID.randomUUID().toString().replace("-", "");
        this.callback = callback;
        this.sent = false;

        if (addStandardAttributes) {
            if (session.userId() != null) {
                this.payload.put("api_key", session.userId());
            }

            this.payload.putAll(Request.configurationPayload);
        }

        // Defaults
        boolean blackhole = false;
        RetryConfiguration retry = new RetryConfiguration();
        BatchConfiguration batch = new BatchConfiguration();

        // Configure if possible
        try {
            if (Request.remoteConfiguration != null) {
                Object objHost = Request.remoteConfiguration.endpointConfigurations.containsKey(hostname) ? Request.remoteConfiguration.endpointConfigurations.get(hostname) : null;
                @SuppressWarnings("unchecked")
                Map<String, Object> host = (objHost instanceof Map) ? (Map<String, Object>) objHost : null;
                if (host != null && host.containsKey(endpoint) && host.get(endpoint) instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> endpointConfig = (Map<String, Object>) host.get(endpoint);

                    blackhole = endpointConfig.containsKey("blackhole") ? (boolean) endpointConfig.get("blackhole") : blackhole;

                    // Retry configuration
                    if (endpointConfig.containsKey("retry") && endpointConfig.get("retry") instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> retryConfig = (Map<String, Object>) endpointConfig.get("retry");

                        try {
                            retry.jitter = retryConfig.containsKey("jitter") ? (retryConfig.get("jitter") instanceof Number ? ((Number) retryConfig.get("jitter")).floatValue()
                                                                                                                            : Float.parseFloat(retryConfig.get("jitter").toString()))
                                                                             : retry.jitter;
                        } catch (Exception ignored) {
                        }

                        if (retryConfig.containsKey("times") && retryConfig.get("times") instanceof List) {
                            List timesList = (List) retryConfig.get("times");
                            float[] timesArray = new float[timesList.size()];
                            int i = 0;
                            for (Object o : timesList) {
                                try {
                                    timesArray[i] = o instanceof Number ? ((Number) o).floatValue()
                                                                        : Float.parseFloat(o.toString());
                                } catch (Exception ignored) {
                                    timesArray[i] = 10.0f;
                                }
                                i++;
                            }
                            retry.times = timesArray;
                        }
                    }

                    // Batch configuration
                    if (endpointConfig.containsKey("batch") && endpointConfig.get("batch") instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> batchConfig = (Map<String, Object>) endpointConfig.get("batch");

                        try {
                            batch.count = batchConfig.containsKey("count") ? (batchConfig.get("count") instanceof Number ? ((Number) batchConfig.get("count")).longValue()
                                                                                                                         : Long.parseLong(batchConfig.get("count").toString()))
                                                                           : batch.count;
                        } catch (Exception ignored) {
                        }

                        try {
                            batch.time = batchConfig.containsKey("time") ? (batchConfig.get("time") instanceof Number ? ((Number) batchConfig.get("time")).floatValue()
                                                                                                                      : Float.parseFloat(batchConfig.get("time").toString()))
                                                                         : batch.time;
                        } catch (Exception ignored) {
                        }

                        try {
                            batch.maximumWaitTime = batchConfig.containsKey("maximum_wait_time") ? (batchConfig.get("maximum_wait_time") instanceof Number ? ((Number) batchConfig.get("maximum_wait_time")).floatValue()
                                                                                                                                                           : Float.parseFloat(batchConfig.get("maximum_wait_time").toString()))
                                                                                                 : batch.maximumWaitTime;
                        } catch (Exception ignored) {
                        }

                        if (batchConfig.containsKey("lww")) {
                            boolean lww = false;
                            try {
                                lww = batchConfig.containsKey("lww") ? (batchConfig.get("lww") instanceof Boolean ? (Boolean) batchConfig.get("lww")
                                                                                                                  : Boolean.parseBoolean(batchConfig.get("lww").toString()))
                                                                     : lww;
                            } catch (Exception ignored) {
                            }
                            if (lww) {
                                batch.count = Long.MAX_VALUE;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Teak.log.exception(e);
        }

        // Assign to the finals
        this.blackhole = blackhole;
        this.retry = retry;
        this.batch = batch;
    }

    @Override
    public void run() {
        this.sent = true;

        if (this.blackhole) return;

        final boolean isMockedRequest = Request.remoteConfiguration != null && Request.remoteConfiguration.isMocked;

        final SecretKeySpec keySpec = new SecretKeySpec(Request.teakApiKey.getBytes(), "HmacSHA256");
        String sig;
        final JSONObject jsonPayload = new JSONObject(this.payload);
        final String requestBody = jsonPayload.toString();

        try {
            if (this.hostname == null) {
                throw new IllegalArgumentException("Hostname is NULL for " + this.endpoint);
            }

            if (isMockedRequest) {
                sig = "unit_test_request_sig";
            } else {
                String requestBodyHash;
                {
                    final Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(keySpec);
                    final byte[] result = mac.doFinal(requestBody.getBytes());
                    requestBodyHash = Helpers.bytesToHex(result);
                }
                {
                    final String stringToSign = "TeakV2-HMAC-SHA256\n" + this.method + "\n" + this.hostname + "\n" + this.endpoint + "\n" + requestBodyHash + "\n";
                    final Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(keySpec);
                    final byte[] result = mac.doFinal(stringToSign.getBytes());
                    sig = Helpers.bytesToHex(result);
                }
            }
        } catch (Exception e) {
            Teak.log.exception(e);
            return;
        }

        try {
            Teak.log.i("request.send", this.toMap());
            final long startTime = System.nanoTime();
            final URL url = new URL(isMockedRequest ? "http" : "https",
                this.hostname,
                isMockedRequest ? Request.MOCKED_PORT : Request.DEFAULT_PORT,
                this.endpoint);
            final IHttpRequest request = new DefaultHttpRequest();
            final IHttpRequest.Response response = request.synchronousRequest(url, this.method, requestBody, sig);

            final int statusCode = response == null ? 0 : response.statusCode;
            final String body = response == null ? null : response.body;

            final Map<String, Object> h = this.toMap();
            h.remove("payload");
            h.put("response_time", (System.nanoTime() - startTime) / 1000000.0);
            if (response != null && response.headers != null) {
                h.put("response_headers", response.headers);
            }

            Map<String, Object> responseAsMap = null;
            if (response != null) {
                responseAsMap = Helpers.fromResponseBody(response.body, "internal").toMap();
                h.put("payload", responseAsMap);
            }

            Teak.log.i("request.reply", h);

            // The server can reply with a 'report_client_error' key and then we will display it
            // in a dialog box, if enhanced integration checks are enabled
            if (responseAsMap != null &&
                responseAsMap.containsKey("report_client_error")) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> clientError = (Map<String, Object>) responseAsMap.get("report_client_error");
                final String title = clientError.containsKey("title") ? (String) clientError.get("title") : "client.error";
                final String message = clientError.containsKey("message") ? (String) clientError.get("message") : null;
                if (message != null) {
                    IntegrationChecker.addErrorToReport(title, message);
                }
            }

            this.onRequestCompleted(statusCode, body);
        } catch (NullPointerException e) {
            // com.android.okhttp is an Android system lib (API < 27); no typed handle exists.
            // Top-of-stack class prefix is the only available discriminator for this Android 7.x bug.
            if (isOkhttpNpe(e)) {
                Teak.log.i("request.error_transient", Helpers.mm.h("error", e.toString()));
            } else {
                Teak.log.exception(e);
            }
        } catch (Exception e) {
            // DeadSystemException added in API 24 — typed instanceof check is safe.
            if (android.os.Build.VERSION.SDK_INT >= 24 && e instanceof android.os.DeadSystemException) {
                Teak.log.i("request.error_transient", Helpers.mm.h("error", e.toString()));
            } else {
                Teak.log.exception(e);
            }
        }
    }

    static boolean isOkhttpNpe(NullPointerException e) {
        final StackTraceElement[] frames = e.getStackTrace();
        return frames.length > 0 && frames[0].getClassName().startsWith("com.android.okhttp");
    }

    protected void onRequestCompleted(int responseCode, String responseBody) {
        if (responseCode >= 500 && this.retry.retryIndex < this.retry.times.length) {
            // Retry with delay + jitter
            float jitter = (new Random().nextFloat() * 2.0f - 1.0f) * this.retry.jitter;
            float delay = this.retry.times[this.retry.retryIndex] + jitter;
            if (delay < 0.0f) delay = 0.0f;

            this.retry.retryIndex++;

            Request.requestExecutor.schedule(this, (long) (delay * 1000.0f), TimeUnit.MILLISECONDS);
        } else if (this.callback != null) {
            this.callback.onRequestCompleted(responseCode, responseBody);
        }
    }

    private Map<String, Object> toMap() {
        HashMap<String, Object> map = new HashMap<>();
        map.put("request_id", this.requestId);
        map.put("hostname", this.hostname);
        map.put("endpoint", this.endpoint);
        map.put("session", Integer.toHexString(this.session.hashCode()));
        map.put("payload", this.payload);
        return map;
    }

    @Override
    @NonNull
    public String toString() {
        try {
            return String.format(Locale.US, "%s: %s", super.toString(), Teak.formatJSONForLogging(new JSONObject(this.toMap())));
        } catch (Exception ignored) {
            return super.toString();
        }
    }
}
