package io.teak.sdk;

import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

import androidx.annotation.NonNull;
import io.teak.sdk.core.Executors;
import io.teak.sdk.core.ThreadFactory;

public class TeakEvent implements Unobfuscable {
    public final String eventType;

    public static final TeakEvent StopEvent = new TeakEvent(null);

    protected TeakEvent(String eventType) {
        this.eventType = eventType;
    }

    public static boolean postEvent(@NonNull TeakEvent event) {
        synchronized (eventProcessingThreadMutex) {
            if (eventProcessingThread == null || !eventProcessingThread.isAlive()) {
                eventProcessingThread = ThreadFactory.autoStart(() -> {
                    try {
                        TeakEvent event1;
                        while ((event1 = eventQueue.take()).eventType != null) {
                            TeakEvent.eventListeners.processEvent(event1);
                        }
                    } catch (Exception e) {
                        Teak.log.exception(e);
                    }
                });
            }
        }

        return TeakEvent.eventQueue.offer(event);
    }

    private static Thread eventProcessingThread;
    private static final BlockingQueue<TeakEvent> eventQueue = new LinkedBlockingQueue<>();
    private static final Object eventProcessingThreadMutex = new Object();

    ///// Event Listener

    public interface EventListener {
        void onNewEvent(@NonNull TeakEvent event);
    }

    public static void addEventListener(EventListener e) {
        TeakEvent.eventListeners.add(e);
    }

    public static void removeEventListener(EventListener e) {
        TeakEvent.eventListeners.remove(e);
    }

    public static class EventListeners {
        private final Object eventListenersMutex = new Object();
        private ArrayList<EventListener> eventListeners = new ArrayList<>();

        private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();

        void add(EventListener e) {
            synchronized (this.eventListenersMutex) {
                if (!this.eventListeners.contains(e)) {
                    this.eventListeners.add(e);
                }
            }
        }

        void remove(EventListener e) {
            synchronized (this.eventListenersMutex) {
                this.eventListeners.remove(e);
            }
        }

        void processEvent(final TeakEvent event) {
            synchronized (this.eventExecutor) {
                this.eventExecutor.execute(() -> dispatchEvent(event));
            }
        }

        // The listeners for an event are picked when its turn comes on the executor, not when it is dequeued.
        // A handler that registers a listener (a new Session does) is then seen by every event queued behind it.
        private void dispatchEvent(final TeakEvent event) {
            final ArrayList<EventListener> eventListenersForEvent;
            synchronized (this.eventListenersMutex) {
                eventListenersForEvent = new ArrayList<>(this.eventListeners);
            }

            if (false && BuildConfig.DEBUG) {
                debugProcessEventOnListeners(event, eventListenersForEvent);
            } else {
                releaseProcessEventOnListeners(event, eventListenersForEvent);
            }
        }

        // A throwing listener must not stop the listeners after it from seeing the event, but the failure
        // still has to reach the thread's uncaught exception handler, so rethrow once all have run.
        private void releaseProcessEventOnListeners(final TeakEvent event, ArrayList<EventListener> listeners) {
            Throwable failure = null;
            for (EventListener e : listeners) {
                try {
                    e.onNewEvent(event);
                } catch (Throwable t) {
                    if (failure == null) {
                        failure = t;
                    } else {
                        failure.addSuppressed(t);
                    }
                }
            }

            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            if (failure != null) throw new RuntimeException(failure);
        }

        // This version makes sure no event handler is taking longer than 5 seconds to process.
        // If something does, it will kill the thread, and print the trace out as an error.
        private void debugProcessEventOnListeners(final TeakEvent event, ArrayList<EventListener> listeners) {
            for (EventListener e : listeners) {
                // TODO: This seems...kind of horrible, but maybe the Java runtime will be fine with it
                final EventListener currentListener = e;
                final Thread thread = ThreadFactory.autoStart(() -> currentListener.onNewEvent(event));
                try {
                    thread.join(5000);
                } catch (Exception ignored) {
                }

                if (thread.isAlive()) {
                    StackTraceElement[] trace = thread.getStackTrace();
                    StringBuilder backTrace = new StringBuilder();
                    for (StackTraceElement element : trace) {
                        backTrace.append("\n\t").append(element.toString());
                    }

                    String errorText = "Took too long processing '" + event.eventType + "' in:" + backTrace;

                    // TODO: Probably shouldn't throw here, but report it somehow
                    if (android.os.Debug.isDebuggerConnected()) {
                        android.util.Log.e(Teak.LOG_TAG, errorText);
                    } else {
                        thread.interrupt();
                        throw new IllegalStateException(errorText);
                    }
                }
            }
        }
    }

    private static final EventListeners eventListeners = new EventListeners();
}
