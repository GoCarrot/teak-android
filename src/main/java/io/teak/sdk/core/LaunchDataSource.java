package io.teak.sdk.core;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.teak.sdk.Helpers;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.Unobfuscable;
import io.teak.sdk.json.JSONObject;
import io.teak.sdk.referrer.InstallReferrerFuture;
import io.teak.sdk.io.DefaultAndroidNotification;

public class LaunchDataSource implements Future<Teak.LaunchData>, Unobfuscable {
    public static final LaunchDataSource Unattributed = new LaunchDataSource(Helpers.futureForValue(Teak.LaunchData.Unattributed));

    private final Future<Teak.LaunchData> launchDataFuture;

    private LaunchDataSource(@NonNull final Future<Teak.LaunchData> launchDataFuture) {
        this.launchDataFuture = launchDataFuture;
    }

    public static LaunchDataSource sourceWithUpdatedDeepLink(@NonNull final Teak.LaunchData launchData, @NonNull final Uri deepLink, @Nullable final Uri launchLink) {
        // Update the existing launch data, if it's attributed
        if (launchData instanceof Teak.AttributedLaunchData) {
            final Teak.AttributedLaunchData attributedLaunchData = (Teak.AttributedLaunchData) launchData;
            return new LaunchDataSource(Helpers.futureForValue(attributedLaunchData.mergeDeepLink(deepLink)));
        }

        // Re-determine what kind of attribution this should be, with the deep link sent back by the server
        return new LaunchDataSource(Helpers.futureForValue(launchDataFromUriPair(deepLink, launchLink)));
    }

    public static LaunchDataSource sourceFromIntent(@NonNull final Intent intent, @NonNull final Context context) {
        // If there is a teakNotifId, then we do not need to wait for the resolution of a link.
        final String teakNotifId = Helpers.getStringOrNullFromIntentExtra(intent, "teakNotifId");

        if (teakNotifId != null) {
            // This is the fast and easy path. There is a teakNotifId, meaning we launched via a push
            // notification.
            final Bundle extras = intent.getExtras();
            final int platformId = extras.getInt("platformId");
            if (platformId != 0) {
                final String groupKey = extras.getString("teakGroupKey", "teak");
                DefaultAndroidNotification.get(context).cancelNotification(platformId, context, groupKey);
            }
            return new LaunchDataSource(Helpers.futureForValue(new Teak.NotificationLaunchData(extras)));
        } else {
            // Not a notification launch, so check for a launch url
            final String intentDataString = intent.getDataString();

            // If there's no launch url, check to see if it's the first launch for an install referral
            if (Helpers.isNullOrEmpty(intentDataString)) {
                final boolean isFirstLaunch = intent.getBooleanExtra("teakIsFirstLaunch", false);

                if (isFirstLaunch) {
                    // This is the first launch, which means we need to check for an install referrer.
                    //
                    // The install referrer may be something like a link from an email, which means that it
                    // could also contain a teak_notif_id.
                    final TeakConfiguration teakConfiguration = TeakConfiguration.get();
                    final Future<String> installReferrer = InstallReferrerFuture.get(teakConfiguration.appConfiguration.applicationContext);
                    return new LaunchDataSource(LaunchDataSource.futureFromLinkResolution(installReferrer));
                }

                // There's no notification launch, no link launch, and it's not the first launch
                // so this is an unattributed launch,
                return LaunchDataSource.Unattributed;
            }

            // There is a launch url, so resolve that URL
            return new LaunchDataSource(LaunchDataSource.futureFromLinkResolution(Helpers.futureForValue(intentDataString)));
        }
    }

    private static Teak.LaunchData launchDataFromUriPair(final Uri uri, final Uri httpsUri) {
        // If this is a link from a Teak email, then it's a notification launch data
        if (Teak.NotificationLaunchData.isTeakEmailUri(uri)) {
            return new Teak.NotificationLaunchData(uri);
        } else if (Teak.RewardlinkLaunchData.isTeakRewardLink(uri)) {
            // If it has a 'teak_rewardlink_id' then it's a reward link
            return new Teak.RewardlinkLaunchData(uri, httpsUri);
        } else {
            // Otherwise this is not a Teak attributed launch
            return new Teak.LaunchData(uri);
        }
    }

    ////// Create a Future which will contain AttributionData from a resolved link

    // Reported (non-fatal) to Sentry when a well-formed deep-link resolution response omits
    // AndroidPath, which is unexpected for a link that resolved. Named so Sentry groups these
    // distinctly, carrying the URL and body in `extra` to surface which links omit the key.
    private static class MissingAndroidPathException extends Exception implements Unobfuscable {
        MissingAndroidPathException(@NonNull String message) {
            super(message);
        }
    }

    protected static Future<Teak.LaunchData> futureFromLinkResolution(@NonNull final Future<String> futureForUriOrNull) {
        final TeakConfiguration teakConfiguration = TeakConfiguration.get();

        final FutureTask<Teak.LaunchData> returnTask = new FutureTask<Teak.LaunchData>(() -> {
            Uri httpsUri = null;

            // Wait on the incoming Future
            Uri uri = null;
            try {
                uri = Uri.parse(futureForUriOrNull.get(5, TimeUnit.SECONDS));
            } catch (Exception ignored) {
            }

            if (uri == null) {
                return null;
            }

            // Try and resolve any Teak links
            if (uri.getScheme() != null && (uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                HttpsURLConnection connection = null;
                try {
                    httpsUri = uri.buildUpon().scheme("https").build();
                    URL url = new URL(httpsUri.toString());

                    Teak.log.i("deep_link.request.send", url.toString());

                    connection = (HttpsURLConnection) url.openConnection();
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Accept-Charset", "UTF-8");
                    connection.setRequestProperty("X-Teak-DeviceType", "API");
                    connection.setRequestProperty("X-Teak-Supports-Templates", "TRUE");

                    // Get Response
                    InputStream is;
                    if (connection.getResponseCode() < 400) {
                        is = connection.getInputStream();
                    } else {
                        is = connection.getErrorStream();
                    }
                    BufferedReader rd = new BufferedReader(new InputStreamReader(is));
                    String line;
                    StringBuilder response = new StringBuilder();
                    while ((line = rd.readLine()) != null) {
                        response.append(line);
                        response.append('\r');
                    }
                    rd.close();

                    Teak.log.i("deep_link.request.reply", response.toString());

                    try {
                        final JSONObject teakData = Helpers.fromResponseBody(response.toString(), "deep_link_resolution");
                        final String androidPath = teakData.optString("AndroidPath", null);
                        if (androidPath != null) {
                            final Pattern pattern = Pattern.compile("^[a-zA-Z0-9+.\\-_]*:");
                            final Matcher matcher = pattern.matcher(androidPath);
                            if (matcher.find()) {
                                uri = Uri.parse(androidPath);
                            } else {
                                uri = Uri.parse(String.format(Locale.US, "teak%s://%s", teakConfiguration.appConfiguration.appId, androidPath));
                            }
                        } else if (teakData.length() > 0) {
                            // A resolved link is expected to carry an AndroidPath; a well-formed response
                            // that omits it (e.g. an iOS-path-only link) is anomalous, so report it to Sentry
                            // with the URL and body to pin down the cause. Behavior is unchanged: uri keeps the
                            // original launch link and httpsUri is retained so it survives as launchLink
                            // (matching the iOS resolver, which always keeps the launch link; httpsUri is only
                            // consumed by the RewardlinkLaunchData branch of launchDataFromUriPair).
                            // Malformed bodies fall back to {} (length 0) and are skipped here — they are
                            // already breadcrumbed by Helpers.fromResponseBody and kept out of Sentry by design.
                            Teak.log.exception(new MissingAndroidPathException("Deep link resolution response had no AndroidPath."),
                                Helpers.mm.h("url", httpsUri.toString(), "response", response.toString()));
                        }

                        Teak.log.i("deep_link.request.resolve", uri.toString());
                    } catch (Exception e) {
                        Teak.log.exception(e);
                    }
                } catch (Exception e) {
                    Teak.log.exception(e);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }

            return launchDataFromUriPair(uri, httpsUri);
        });

        // Start it running, and return the Future
        ThreadFactory.autoStart(returnTask);
        return returnTask;
    }

    ////// implements Future

    @Override
    public boolean cancel(boolean b) {
        return this.launchDataFuture.cancel(b);
    }

    @Override
    public boolean isCancelled() {
        return this.launchDataFuture.isCancelled();
    }

    @Override
    public boolean isDone() {
        return this.launchDataFuture.isDone();
    }

    @Override
    public Teak.LaunchData get() throws ExecutionException, InterruptedException {
        return this.launchDataFuture.get();
    }

    @Override
    public Teak.LaunchData get(long l, TimeUnit timeUnit) throws ExecutionException, InterruptedException, TimeoutException {
        return this.launchDataFuture.get(l, timeUnit);
    }
}
