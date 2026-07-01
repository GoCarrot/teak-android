package io.teak.app.test;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.raven.Raven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

// The uncaught-exception handler (Raven, registered as the default uncaught handler) emits the same
// observable log event the caught path emits via Log.exception: event_type "exception", level ERROR,
// event_data {type, value}. Native-crash markers (signal-prefixed) and OutOfMemoryError stay
// Sentry-only and emit no event. These drive raven.uncaughtException(thread, ex) against the real
// handler, so they fail if the emit is reverted, and they positively prove the event fires (not just
// the absence of a crash).
@RunWith(MockitoJUnitRunner.class)
public class UncaughtExceptionLogEventTest extends TeakUnitTest {

    // The LogListener receives the full event payload, which carries event_type/log_level/event_data.
    private final List<Map<String, Object>> events = new ArrayList<>();

    private Raven makeRaven() {
        return new Raven(context, "sdk", TeakConfiguration.get(), objectFactory);
    }

    // Capture every emitted log event, then flip Log to drain immediately (no DSN/Raven needed).
    private void captureLogEvents() {
        Teak.log.setLogListener(new Teak.LogListener() {
            @Override
            public void logEvent(String logEvent, String logLevel, Map<String, Object> logData) {
                events.add(logData);
            }
        });
        Teak.log.markConfigurationReady();
    }

    private Map<String, Object> firstExceptionEvent() {
        for (Map<String, Object> event : events) {
            if ("exception".equals(event.get("event_type"))) {
                return event;
            }
        }
        return null;
    }

    @After
    public void tearDown() {
        Teak.log.setLogListener(null);
        Teak.log.setSdkRaven(null);
    }

    @Test
    public void uncaughtExceptionEmitsObservableLogEvent() {
        captureLogEvents();

        makeRaven().uncaughtException(Thread.currentThread(), new RuntimeException("boom"));

        final Map<String, Object> event = firstExceptionEvent();
        assertNotNull("uncaught exception must emit an \"exception\" log event", event);
        assertEquals("ERROR", event.get("log_level"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> data = (Map<String, Object>) event.get("event_data");
        assertNotNull("event must carry event_data", data);
        assertEquals("RuntimeException", data.get("type"));
        assertEquals("boom", data.get("value"));
    }

    @Test
    public void signalPrefixedThrowableEmitsNoLogEvent() {
        captureLogEvents();

        makeRaven().uncaughtException(Thread.currentThread(), new RuntimeException("signal 11 (SIGSEGV)"));

        assertNull("signal-prefixed native crash must emit no log event", firstExceptionEvent());
    }

    @Test
    public void outOfMemoryErrorEmitsNoLogEvent() {
        captureLogEvents();

        makeRaven().uncaughtException(Thread.currentThread(), new OutOfMemoryError("oom"));

        assertNull("OutOfMemoryError must emit no log event", firstExceptionEvent());
    }
}
