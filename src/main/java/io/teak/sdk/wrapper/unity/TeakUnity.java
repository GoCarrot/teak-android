package io.teak.sdk.wrapper.unity;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import io.teak.sdk.Teak;
import io.teak.sdk.Unobfuscable;
import io.teak.sdk.core.Executors;
import io.teak.sdk.json.JSONObject;
import io.teak.sdk.raven.Raven;
import io.teak.sdk.wrapper.TeakInterface;

public class TeakUnity implements Unobfuscable {
    private static Method unitySendMessage;
    private static TeakInterface teakInterface;
    private static final ExecutorService unitySendMessageExecutor = Executors.newSingleThreadExecutor();

    static {
        try {
            Class<?> unityPlayerClass = Class.forName("com.unity3d.player.UnityPlayer");
            TeakUnity.unitySendMessage = unityPlayerClass.getMethod("UnitySendMessage", String.class, String.class, String.class);

            Teak.setLogListener(new Teak.LogListener() {
                @Override
                public void logEvent(String logEvent, String logLevel, Map<String, Object> logData) {
                    unitySendMessage("LogEvent", new JSONObject(logData).toString());
                }
            });
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("WeakerAccess")
    public static void initialize() {
        teakInterface = new TeakInterface((eventType, eventData) -> {
            String eventName = null;
            switch (eventType) {
                case NotificationLaunch: {
                    eventName = "NotificationLaunch";
                } break;
                case RewardClaim: {
                    eventName = "RewardClaimAttempt";
                } break;
                case RewardJwtIssued: {
                    eventName = "RewardJwtIssued";
                } break;
                case RewardClaimPending: {
                    eventName = "RewardClaimPending";
                } break;
                case RewardClaimResolved: {
                    eventName = "RewardClaimResolved";
                } break;
                case ForegroundNotification: {
                    eventName = "ForegroundNotification";
                } break;
                case AdditionalData: {
                    eventName = "AdditionalData";
                } break;
                case LaunchedFromLink: {
                    eventName = "LaunchedFromLink";
                } break;
                case PostLaunchSummary: {
                    eventName = "PostLaunchSummary";
                } break;
                case UserData: {
                    eventName = "UserDataEvent";
                } break;
                case ConfigurationData: {
                    eventName = "InConfigurationData";
                } break;
            }
            unitySendMessage(eventName, eventData);
        });
    }

    public static boolean isAvailable() {
        return TeakUnity.unitySendMessage != null;
    }

    private static void unitySendMessage(final String method, final String message) {
        TeakUnity.unitySendMessageExecutor.submit(() -> {
            if (TeakUnity.isAvailable()) {
                try {
                    TeakUnity.unitySendMessage.invoke(null, "TeakGameObject", method, message);
                } catch (UnsatisfiedLinkError ignored) {
                    // TEAK-ANDROID-SDK-K4
                    // TEAK-ANDROID-SDK-K5
                    // TEAK-ANDROID-SDK-K6
                    // TEAK-ANDROID-SDK-K9
                } catch (Exception e) {
                    Teak.log.exception(e);
                }
            }
        });
    }

    @SuppressWarnings("unused")
    public static void registerRoute(final String route, final String name, final String description) {
        try {
            Teak.registerDeepLink(route, name, description, new Teak.DeepLink() {
                @Override
                public void call(Map<String, Object> parameters) {
                    try {
                        if (TeakUnity.isAvailable()) {
                            JSONObject eventData = new JSONObject();
                            eventData.put("route", route);
                            eventData.put("parameters", new JSONObject(parameters));
                            TeakUnity.unitySendMessage("DeepLink", eventData.toString());
                        }
                    } catch (Exception e) {
                        Teak.log.exception(e);
                    }
                }
            });
        } catch (Exception e) {
            Teak.log.exception(e);
        }
    }

    @SuppressWarnings({"unused", "deprecation"})
    public static void testExceptionReporting() {
        Teak.log.exception(new Raven.ReportTestException(Teak.SDKVersion));
    }
}
