package io.teak.sdk.wrapper.cocos2dx;

import java.lang.reflect.Method;
import java.util.Map;

import io.teak.sdk.Teak;
import io.teak.sdk.Unobfuscable;
import io.teak.sdk.json.JSONObject;
import io.teak.sdk.raven.Raven;
import io.teak.sdk.wrapper.TeakInterface;

// Future-Pat: Prefix all Cocos2dx events with 'Teak' since they seem to use global event dispatch

public class TeakCocos2dx implements Unobfuscable {
    private static TeakInterface teakInterface;
    private static Method runOnGLThread;

    static {
        try {
            Class<?> cocos2dxHelper = Class.forName("org.cocos2dx.lib.Cocos2dxHelper");
            TeakCocos2dx.runOnGLThread = cocos2dxHelper.getMethod("runOnGLThread", Runnable.class);

            Teak.setLogListener(new Teak.LogListener() {
                @Override
                public void logEvent(String logEvent, String logLevel, Map<String, Object> logData) {
                    TeakCocos2dx.sendMessage("TeakLogEvent", new JSONObject(logData).toString());
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
                    eventName = "TeakNotificationLaunch";
                } break;
                case RewardClaim: {
                    eventName = "TeakRewardClaimAttempt";
                } break;
                case RewardJwtIssued: {
                    eventName = "TeakOnRewardJwtIssued";
                } break;
                case RewardClaimPending: {
                    eventName = "TeakOnRewardClaimPending";
                } break;
                case RewardClaimResolved: {
                    eventName = "TeakOnRewardClaimResolved";
                } break;
                case ForegroundNotification: {
                    eventName = "TeakForegroundNotification";
                } break;
                case AdditionalData: {
                    eventName = "TeakAdditionalData";
                } break;
                case LaunchedFromLink: {
                    eventName = "TeakLaunchedFromLink";
                } break;
            }
            TeakCocos2dx.sendMessage(eventName, eventData);
        });
    }

    public static boolean isAvailable() {
        return TeakCocos2dx.runOnGLThread != null;
    }

    @SuppressWarnings("unused")
    public static void registerRoute(final String route, final String name, final String description) {
        try {
            Teak.registerDeepLink(route, name, description, new Teak.DeepLink() {
                @Override
                public void call(Map<String, Object> parameters) {
                    try {
                        if (TeakCocos2dx.isAvailable()) {
                            JSONObject eventData = new JSONObject();
                            eventData.put("route", route);
                            eventData.put("parameters", new JSONObject(parameters));
                            TeakCocos2dx.sendMessage("TeakDeepLink", eventData.toString());
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

    private static native void nativeSendMessage(String event, String json);

    private static void sendMessage(final String event, final String eventData) {
        try {
            TeakCocos2dx.runOnGLThread.invoke(null, (Runnable) () -> TeakCocos2dx.nativeSendMessage(event, eventData));
        } catch (Exception e) {
            Teak.log.exception(e);
        }
    }
}
