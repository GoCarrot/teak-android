package io.teak.sdk.raven;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import io.teak.sdk.IObjectFactory;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.event.UserIdEvent;
import io.teak.sdk.json.JSONObject;

public class Raven implements Thread.UncaughtExceptionHandler {
    public static final String LOG_TAG = "Teak.Raven";
    private static final String TEAK_SENTRY_PROGUARD_UUID = "io_teak_sentry_proguard_uuid";

    public static class ReportTestException extends Exception {
        public ReportTestException(@NonNull String version) {
            super("Version: " + version);
        }
    }

    private enum Level {
        FATAL("fatal"),
        ERROR("error"),
        WARNING("warning"),
        INFO("info"),
        DEBUG("debug");

        private final String value;

        Level(String value) {
            this.value = value;
        }

        @Override
        @NonNull
        public String toString() {
            return this.value;
        }
    }

    // Upper bound on breadcrumbs retained in memory (bounds history growth between reports).
    // The number actually attached to a report is governed by PAYLOAD_BUDGET_BYTES, not this.
    private static final int BREADCRUMB_LIMIT = 100;
    private final ArrayDeque<Map<String, Object>> breadcrumbs = new ArrayDeque<>();

    // Max serialized JSON bytes of the report payload (including breadcrumbs) we allow before
    // it is handed to a WorkManager Data value. WorkManager caps a Data blob at 10240 bytes
    // total (Data.MAX_DATA_BYTES) and build() throws past it. We budget the JSON conservatively,
    // leaving headroom for the endpoint/key/timestamp entries and WorkManager's own marshalling
    // overhead, which also count toward the cap. Breadcrumbs are trimmed newest-first to fit; the
    // breadcrumb-free core report always sends.
    private static final int PAYLOAD_BUDGET_BYTES = 8 * 1024;

    private final List<Report> queuedReports = new ArrayList<>();
    private final HashMap<String, Object> payloadTemplate = new HashMap<>();
    private final Context applicationContext;
    private final String appId;
    private Thread.UncaughtExceptionHandler previousUncaughtExceptionHandler;

    private String SENTRY_KEY;
    private String SENTRY_SECRET;
    private URL endpoint;

    private static final SimpleDateFormat timestampFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);

    static {
        Raven.timestampFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public Raven(@NonNull Context context, @NonNull String appId, @NonNull TeakConfiguration configuration, @NonNull IObjectFactory objectFactory) {
        // noinspection deprecation - This must be a string as per Sentry API
        @SuppressWarnings("deprecation")
        final String teakSdkVersion = Teak.SDKVersion;

        this.applicationContext = context;
        this.appId = appId;

        final String proguardUuid = objectFactory.getAndroidResources().getStringResource(Raven.TEAK_SENTRY_PROGUARD_UUID);
        if (proguardUuid != null && proguardUuid.length() > 0) {
            HashMap<String, Object> debug_meta = new HashMap<>();
            ArrayList<Object> debugImages = new ArrayList<>();
            HashMap<String, Object> proguard = new HashMap<>();
            proguard.put("type", "proguard");
            proguard.put("uuid", proguardUuid);
            debugImages.add(proguard);
            debug_meta.put("images", debugImages);
            payloadTemplate.put("debug_meta", debug_meta);
        }

        // Fill in as much of the payload template as we can
        payloadTemplate.put("logger", "teak");
        payloadTemplate.put("platform", "java");
        payloadTemplate.put("release", teakSdkVersion);

        final HashMap<String, Object> sdkAttribute = new HashMap<>();
        sdkAttribute.put("name", "teak");
        sdkAttribute.put("version", Sender.TEAK_SENTRY_VERSION);
        this.payloadTemplate.put("sdk", sdkAttribute);

        String deviceFamily = null;
        try {
            deviceFamily = Build.MODEL.split(" ")[0];
        } catch (Exception ignored) {
        }

        class Deprecated {
            @SuppressWarnings("deprecation")
            private String getCPU_ABI() {
                return Build.CPU_ABI;
            }
        }

        final HashMap<String, Object> device = new HashMap<>();
        device.put("manufacturer", Build.MANUFACTURER);
        device.put("brand", Build.BRAND);
        device.put("model", Build.MODEL);
        device.put("family", deviceFamily);
        device.put("model_id", Build.ID);
        device.put("arch", new Deprecated().getCPU_ABI());

        final HashMap<String, Object> os = new HashMap<>();
        os.put("name", "Android");
        os.put("version", Build.VERSION.RELEASE);
        os.put("build", Build.DISPLAY);

        final HashMap<String, Object> app = new HashMap<>();
        app.put("app_identifier", configuration.appConfiguration.bundleId);
        app.put("teak_app_identifier", configuration.appConfiguration.appId);
        app.put("app_version", String.valueOf(configuration.appConfiguration.appVersion));
        app.put("app_version_name", configuration.appConfiguration.appVersionName);
        app.put("build_type", configuration.debugConfiguration.isDebug() ? "debug" : "production");
        app.put("target_sdk_version", configuration.appConfiguration.targetSdkVersion);

        final HashMap<String, Object> contexts = new HashMap<>();
        contexts.put("device", device);
        contexts.put("os", os);
        contexts.put("app", app);
        this.payloadTemplate.put("contexts", contexts);

        final HashMap<String, Object> user = new HashMap<>();
        user.put("device_id", configuration.deviceConfiguration.deviceId);
        this.payloadTemplate.put("user", user);

        TeakEvent.addEventListener(event -> {
            if (event instanceof UserIdEvent) {
                user.put("id", ((UserIdEvent) event).userId);
            }
        });

        final HashMap<String, Object> tagsAttribute = new HashMap<>();
        tagsAttribute.put("app_id", configuration.appConfiguration.appId);
        tagsAttribute.put("app_version", configuration.appConfiguration.appVersion);
        tagsAttribute.put("app_version_name", configuration.appConfiguration.appVersionName);
        tagsAttribute.put("run_id", Teak.log.runId);
        this.payloadTemplate.put("tags", tagsAttribute);
    }

    public void setAsUncaughtExceptionHandler() {
        if (Thread.getDefaultUncaughtExceptionHandler() instanceof Raven) {
            Raven raven = (Raven) Thread.getDefaultUncaughtExceptionHandler();
            raven.unsetAsUncaughtExceptionHandler();
        }
        previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    private void unsetAsUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousUncaughtExceptionHandler);
        previousUncaughtExceptionHandler = null;
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable ex) {
        if (!(ex instanceof OutOfMemoryError)) {
            reportException(ex, null);
        }
    }

    public void setDsn(@NonNull String dsn) {
        if (dsn.isEmpty()) {
            Log.e(LOG_TAG, "DSN empty for app: " + this.appId);
            return;
        }

        final Uri uri = Uri.parse(dsn);

        String port = "";
        if (uri.getPort() >= 0) {
            port = ":" + uri.getPort();
        }

        try {
            final String project = uri.getPath().substring(uri.getPath().lastIndexOf("/"));
            final String[] userInfo = uri.getUserInfo().split(":");

            this.SENTRY_KEY = userInfo[0];
            this.SENTRY_SECRET = userInfo[1];

            this.endpoint = new URL(String.format("%s://%s%s/api%s/store/",
                uri.getScheme(), uri.getHost(), port, project));

            synchronized (this.queuedReports) {
                for (Report report : this.queuedReports) {
                    report.send();
                }
                this.queuedReports.clear();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error parsing DSN: '" + uri.toString() + "'" + Log.getStackTraceString(e));
        }
    }

    public static boolean shouldSuppressThrowable(Throwable t) {
        final String message = t.getMessage();
        if (message == null) {
            return false;
        }
        return message.startsWith("signal");
    }

    public static Map<String, Object> throwableToMap(Throwable t) {
        Throwable throwable = t;
        if (throwable instanceof InvocationTargetException && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }

        if (Raven.shouldSuppressThrowable(throwable)) {
            return null;
        }

        HashMap<String, Object> exception = new HashMap<>();

        exception.put("type", throwable.getClass().getSimpleName());
        exception.put("value", throwable.getMessage());
        exception.put("module", throwable.getClass().getPackage().getName());

        HashMap<String, Object> stacktrace = new HashMap<>();
        ArrayList<Object> stackFrames = new ArrayList<>();

        StackTraceElement[] steArray = throwable.getStackTrace();
        for (int i = steArray.length - 1; i >= 0; i--) {
            StackTraceElement ste = steArray[i];
            HashMap<String, Object> frame = new HashMap<>();

            frame.put("filename", ste.getFileName());

            String method = ste.getMethodName();
            if (method.length() != 0) {
                frame.put("function", method);
            }

            int lineno = ste.getLineNumber();
            if (!ste.isNativeMethod() && lineno >= 0) {
                frame.put("lineno", lineno);
            }

            String module = ste.getClassName();
            frame.put("module", module);

            boolean in_app = true;
            if (module.startsWith("android.") || module.startsWith("java.") || module.startsWith("dalvik.") || module.startsWith("com.android.")) {
                in_app = false;
            }

            frame.put("in_app", in_app);

            stackFrames.add(frame);
        }
        stacktrace.put("frames", stackFrames);

        exception.put("stacktrace", stacktrace);

        return exception;
    }

    public void reportException(Throwable t, Map<String, Object> extras) {
        if (t == null) {
            return;
        }

        Throwable throwable = t;
        if (throwable instanceof InvocationTargetException && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }

        HashMap<String, Object> additions = new HashMap<>();
        ArrayList<Object> exceptions = new ArrayList<>();
        Map<String, Object> exception = Raven.throwableToMap(throwable);
        if (exception == null) {
            return;
        }

        exceptions.add(exception);
        additions.put("exception", exceptions);
        additions.put("extra", extras);

        try {
            Report report = new Report(t.getMessage(), Level.ERROR, additions);
            synchronized (this.queuedReports) {
                if (this.endpoint == null) {
                    this.queuedReports.add(report);
                } else {
                    report.send();
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Unable to report Teak SDK exception. " + Log.getStackTraceString(t) + "\n" + Log.getStackTraceString(e));
        }
    }

    public void addBreadcrumb(@NonNull String level, @NonNull String message, @Nullable Map<String, Object> data) {
        final HashMap<String, Object> breadcrumb = new HashMap<>();
        breadcrumb.put("timestamp", Raven.timestampFormatter.format(new Date()));
        breadcrumb.put("level", level);
        breadcrumb.put("category", message);
        breadcrumb.put("message", message);
        if (data != null) {
            breadcrumb.put("data", data);
        }
        synchronized (this.breadcrumbs) {
            this.breadcrumbs.addLast(breadcrumb);
            if (this.breadcrumbs.size() > BREADCRUMB_LIMIT) {
                this.breadcrumbs.removeFirst();
            }
        }
    }

    public List<Map<String, Object>> snapshotBreadcrumbs() {
        synchronized (this.breadcrumbs) {
            return new ArrayList<>(this.breadcrumbs);
        }
    }

    // Returns the breadcrumbs (in chronological order) that fit within budgetBytes when attached
    // to basePayload, keeping the NEWEST crumbs. PRECONDITION: basePayload carries no `breadcrumbs`
    // key -- the caller owns attaching the fitted set; withEmpty and toData's final attach both
    // put() that key, so a pre-existing one would be silently replaced (not double-counted -- the
    // measurement stays self-consistent either way). Sizing is exact and O(n): `withEmpty` -- the
    // payload carrying an empty breadcrumbs wrapper -- is serialized once and captures the
    // `,"breadcrumbs":{"values":[]}` attach overhead without hardcoding it. A crumb serializes
    // byte-identically standalone as it does inside the "values" array, so the assembled size of
    // k crumbs is exactly withEmpty + sum(crumb sizes) + (k-1) inter-crumb commas. Keep the newest
    // crumbs whose running total stays within budget, then reverse once into Sentry's chronological
    // order. If basePayload alone is already over budget, returns empty (the core report still
    // sends). Static + public so it can be unit-tested without an Android runtime.
    public static List<Map<String, Object>> fitBreadcrumbsToBudget(@NonNull Map<String, Object> basePayload,
        @NonNull List<Map<String, Object>> snapshot, int budgetBytes) {
        final List<Map<String, Object>> kept = new ArrayList<>();
        if (snapshot.isEmpty() || jsonByteLength(basePayload) > budgetBytes) {
            return kept;
        }

        // Baseline = the payload with an empty breadcrumbs wrapper, serialized once. The first
        // crumb's size adds directly onto this; each subsequent crumb also costs one comma byte.
        final HashMap<String, Object> withEmpty = new HashMap<>(basePayload);
        final HashMap<String, Object> wrapper = new HashMap<>();
        wrapper.put("values", new ArrayList<>());
        withEmpty.put("breadcrumbs", wrapper);
        int used = jsonByteLength(withEmpty);

        // Walk newest-first, accumulating exact serialized size, until the next crumb would exceed
        // the budget. Reversed below so `kept` ends up oldest-first.
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            final int crumbBytes = jsonByteLength(snapshot.get(i)) + (kept.isEmpty() ? 0 : 1);
            if (used + crumbBytes > budgetBytes) {
                break;
            }
            used += crumbBytes;
            kept.add(snapshot.get(i));
        }
        Collections.reverse(kept);
        return kept;
    }

    private static int jsonByteLength(@NonNull Map<String, Object> map) {
        return new JSONObject(map).toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private Map<String, Object> toMap() {
        HashMap<String, Object> ret = new HashMap<>();
        ret.put("appId", this.appId);
        ret.put("applicationContext", this.applicationContext);
        ret.put("payloadTemplate", this.payloadTemplate);
        return ret;
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

    private class Report {
        final HashMap<String, Object> payload = new HashMap<>();
        final List<Map<String, Object>> breadcrumbSnapshot;
        final Date timestamp = new Date();
        final String uuid = UUID.randomUUID().toString().replace("-", "");

        Report(String m, @NonNull Level level, HashMap<String, Object> additions) {
            String message = m;
            if (message == null || message.length() < 1) {
                message = "undefined";
            }

            payload.put("event_id", this.uuid);
            payload.put("message", message.substring(0, Math.min(message.length(), 1000)));

            payload.put("timestamp", Raven.timestampFormatter.format(timestamp));

            payload.put("level", level.toString());

            try {
                // 0 dalvik.system.VMStack.getThreadStackTrace
                // 1 java.lang.Thread.getStackTrace
                // 2 io.teak.sdk.Raven$Request.<init>
                // 3 Raven.report*
                // 4 culprit method
                final int depth = 4;
                final StackTraceElement[] ste = Thread.currentThread().getStackTrace();
                payload.put("culprit", ste[depth].toString());
                // for (StackTraceElement elem : ste) {
                //     Log.d(LOG_TAG, elem.toString());
                // }
            } catch (Exception e) {
                payload.put("culprit", "unknown");
            }

            if (additions != null) {
                payload.putAll(additions);
            }

            // Snapshot breadcrumbs as-of-now (i.e. as-of-exception). They are fitted to the size
            // budget and attached at toData() time, when the full Data envelope is known.
            this.breadcrumbSnapshot = Raven.this.snapshotBreadcrumbs();
        }

        void send() {
            final Data data = this.toData();
            if (data == null) {
                // toData() already logged loudly; don't enqueue a null-Data request.
                return;
            }
            final Constraints constraints = new Constraints.Builder()
                                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                                .build();
            final OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(Sender.class)
                                                   .setInputData(data)
                                                   .setConstraints(constraints)
                                                   .build();
            WorkManager.getInstance(Raven.this.applicationContext)
                .enqueueUniqueWork(this.uuid, ExistingWorkPolicy.KEEP, request);
        }

        Data toData() {
            this.payload.putAll(Raven.this.payloadTemplate);

            // Attach as many breadcrumbs as fit within the size budget, newest-first. The core
            // breadcrumb-free payload is what's measured against; if it's already over budget no
            // breadcrumbs are added and it still sends.
            final List<Map<String, Object>> fitted =
                Raven.fitBreadcrumbsToBudget(this.payload, this.breadcrumbSnapshot, PAYLOAD_BUDGET_BYTES);
            if (!fitted.isEmpty()) {
                final HashMap<String, Object> breadcrumbsPayload = new HashMap<>();
                breadcrumbsPayload.put("values", fitted);
                this.payload.put("breadcrumbs", breadcrumbsPayload);
            }

            // Serialize the final payload exactly once; the same String feeds both the size log
            // below and the Data value in buildData().
            final String payloadJson = new JSONObject(this.payload).toString();

            // Observability: how many breadcrumbs survived size-budgeting and the resulting
            // payload size. Fires once per exception report (rare); trimming is normal operation,
            // hence debug, not warn/error.
            Log.d(LOG_TAG, "Sentry report: " + fitted.size() + " of " + this.breadcrumbSnapshot.size()
                               + " breadcrumbs kept, payload " + payloadJson.getBytes(StandardCharsets.UTF_8).length
                               + " bytes (size budget " + PAYLOAD_BUDGET_BYTES + ").");

            Data data = this.buildData(payloadJson);
            if (data != null) {
                return data;
            }

            // Backstop: the payload still exceeds WorkManager's Data cap after size-budgeting
            // breadcrumbs. Don't silently drop the report — log loudly and retry with the core
            // exception only (matching pre-breadcrumbs 4.3.12 behavior). This should essentially
            // never fire given the conservative budget above, and is the only path that serializes
            // a second time.
            Log.e(LOG_TAG, "Sentry report exceeded WorkManager Data cap; retrying without breadcrumbs.");
            this.payload.remove("breadcrumbs");
            data = this.buildData(new JSONObject(this.payload).toString());
            if (data == null) {
                Log.e(LOG_TAG, "Sentry report exceeds WorkManager Data cap even without breadcrumbs; dropping.");
            }
            return data;
        }

        private Data buildData(String payloadJson) {
            try {
                return new Data.Builder()
                    .putLong(Sender.TIMESTAMP_KEY, this.timestamp.getTime() / 1000L)
                    .putString(Sender.PAYLOAD_KEY, payloadJson)
                    .putString(Sender.ENDPOINT_KEY, Raven.this.endpoint.toString())
                    .putString(Sender.SENTRY_KEY_KEY, Raven.this.SENTRY_KEY)
                    .putString(Sender.SENTRY_SECRET_KEY, Raven.this.SENTRY_SECRET)
                    .build();
            } catch (Exception e) {
                Log.e(LOG_TAG, Log.getStackTraceString(e));
                return null;
            }
        }

        Map<String, Object> toMap() {
            HashMap<String, Object> ret = new HashMap<>();
            ret.put("payload", this.payload);
            ret.put("timestamp", this.timestamp);
            return ret;
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
}
