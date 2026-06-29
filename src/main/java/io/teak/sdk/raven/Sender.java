package io.teak.sdk.raven;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HttpsURLConnection;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import io.teak.sdk.json.JSONObject;

public class Sender extends Worker {
    public static final int SENTRY_VERSION = 7;
    public static final String TEAK_SENTRY_VERSION = "1.1.0";
    public static final String SENTRY_CLIENT = "teak-android/" + TEAK_SENTRY_VERSION;

    private final URL endpoint;
    private final String payload;
    private final String sentryKey;
    private final String sentrySecret;
    private final long timestamp;
    private final boolean isDebug;

    public static final String ENDPOINT_KEY = "endpoint";
    public static final String PAYLOAD_KEY = "payload";
    public static final String SENTRY_KEY_KEY = "SENTRY_KEY";
    public static final String SENTRY_SECRET_KEY = "SENTRY_SECRET";
    public static final String TIMESTAMP_KEY = "timestamp";
    public static final String DEBUG_KEY = "debug";

    public Sender(@NonNull Context context, @NonNull WorkerParameters workerParams) throws MalformedURLException {
        super(context, workerParams);

        final Data inputData = workerParams.getInputData();
        this.endpoint = new URL(inputData.getString(ENDPOINT_KEY));
        this.payload = inputData.getString(PAYLOAD_KEY);
        this.sentryKey = inputData.getString(SENTRY_KEY_KEY);
        this.sentrySecret = inputData.getString(SENTRY_SECRET_KEY);
        this.timestamp = inputData.getLong(TIMESTAMP_KEY, new Date().getTime() / 1000L);
        this.isDebug = inputData.getBoolean(DEBUG_KEY, false);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (this.payload == null || this.endpoint == null) {
            return Result.failure();
        }

        HttpsURLConnection connection = null;
        BufferedReader rd = null;

        try {
            connection = (HttpsURLConnection) endpoint.openConnection();
            connection.setRequestProperty("Accept-Charset", "UTF-8");
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Content-Encoding", "gzip");
            connection.setRequestProperty("User-Agent", SENTRY_CLIENT);
            connection.setRequestProperty("X-Sentry-Auth",
                String.format(Locale.US, "Sentry sentry_version=%d,sentry_timestamp=%d,sentry_key=%s,sentry_secret=%s,sentry_client=%s",
                    SENTRY_VERSION, this.timestamp, this.sentryKey, this.sentrySecret, SENTRY_CLIENT));

            GZIPOutputStream wr = new GZIPOutputStream(connection.getOutputStream());
            wr.write(this.payload.getBytes());
            wr.flush();
            wr.close();

            final int responseCode = connection.getResponseCode();
            if (this.isDebug) {
                Log.d(Raven.LOG_TAG, "Sentry POST HTTP " + responseCode);
            }
            InputStream is;
            if (responseCode < 400) {
                is = connection.getInputStream();
            } else {
                is = connection.getErrorStream();
            }
            rd = new BufferedReader(new InputStreamReader(is));
            String line;
            StringBuilder response = new StringBuilder();
            while ((line = rd.readLine()) != null) {
                response.append(line);
                response.append('\r');
            }

            try {
                // TODO: If this was throttled etc, return false and it will be retried
                JSONObject jsonResponse = new JSONObject(response.toString());
                if (jsonResponse.getBoolean("foo")) {
                    return Result.failure();
                }
            } catch (Exception ignored) {
            }

            return Result.success();
        } catch (Exception e) {
            if (this.isDebug) {
                Log.d(Raven.LOG_TAG, "Sentry POST exception: " + Log.getStackTraceString(e));
            }
            return Result.failure();
        } finally {
            if (rd != null) {
                try {
                    rd.close();
                } catch (Exception ignored) {
                }
            }

            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
