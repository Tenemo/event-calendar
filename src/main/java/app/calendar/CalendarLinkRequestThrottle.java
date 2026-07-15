package app.calendar;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

@ApplicationScoped
public class CalendarLinkRequestThrottle {
    static final int MAXIMUM_REQUESTS_PER_SOURCE = 300;
    static final Duration REQUEST_WINDOW = Duration.ofMinutes(1);
    static final int MAXIMUM_TRACKED_SOURCES = 10_000;
    static final int MAXIMUM_CONCURRENT_REQUESTS = 16;

    private static final int MAXIMUM_SOURCE_IDENTIFIER_LENGTH = 128;
    private static final String UNKNOWN_SOURCE_KEY = "<unknown-source>";
    private static final String OVERSIZED_SOURCE_KEY = "<oversized-source>";

    private final Clock clock;
    private final int maximumRequestsPerSource;
    private final Duration requestWindow;
    private final int maximumTrackedSources;
    private final Map<String, RequestWindow> sourceRequestWindows;
    private final Semaphore concurrentRequestPermits;

    public CalendarLinkRequestThrottle() {
        this(
                Clock.systemUTC(),
                MAXIMUM_REQUESTS_PER_SOURCE,
                REQUEST_WINDOW,
                MAXIMUM_TRACKED_SOURCES,
                MAXIMUM_CONCURRENT_REQUESTS);
    }

    CalendarLinkRequestThrottle(
            Clock clock,
            int maximumRequestsPerSource,
            Duration requestWindow,
            int maximumTrackedSources,
            int maximumConcurrentRequests) {
        this.clock = Objects.requireNonNull(clock);
        requirePositive(maximumRequestsPerSource, "Maximum requests per source");
        requirePositive(requestWindow, "Request window");
        requirePositive(maximumTrackedSources, "Maximum tracked sources");
        requirePositive(maximumConcurrentRequests, "Maximum concurrent requests");
        this.maximumRequestsPerSource = maximumRequestsPerSource;
        this.requestWindow = requestWindow;
        this.maximumTrackedSources = maximumTrackedSources;
        this.sourceRequestWindows = new LinkedHashMap<>(16, 0.75f, true);
        this.concurrentRequestPermits = new Semaphore(maximumConcurrentRequests, true);
    }

    public Admission tryAcquire(String sourceIdentifier) {
        int retryAfterSeconds = recordAndCheckSourceRequest(sourceIdentifier);
        if (retryAfterSeconds > 0) {
            return Admission.rejected(retryAfterSeconds);
        }
        if (!concurrentRequestPermits.tryAcquire()) {
            return Admission.rejected(1);
        }
        return Admission.accepted(concurrentRequestPermits);
    }

    synchronized int trackedSourceCount() {
        return sourceRequestWindows.size();
    }

    int availableConcurrentRequestPermits() {
        return concurrentRequestPermits.availablePermits();
    }

    private synchronized int recordAndCheckSourceRequest(String sourceIdentifier) {
        Instant now = clock.instant();
        String sourceKey = sourceKey(sourceIdentifier);
        RequestWindow existingWindow = sourceRequestWindows.get(sourceKey);
        if (existingWindow != null && existingWindow.hasExpired(now, requestWindow)) {
            sourceRequestWindows.remove(sourceKey);
            existingWindow = null;
        }
        if (existingWindow == null) {
            makeRoomForNewSource(now);
            existingWindow = new RequestWindow(now);
            sourceRequestWindows.put(sourceKey, existingWindow);
        }
        if (existingWindow.requestCount >= maximumRequestsPerSource) {
            long remainingSeconds = Duration.between(now, existingWindow.startedAt.plus(requestWindow)).toSeconds();
            return Math.max(1, Math.toIntExact(remainingSeconds));
        }
        existingWindow.requestCount++;
        return 0;
    }

    private void makeRoomForNewSource(Instant now) {
        if (sourceRequestWindows.size() < maximumTrackedSources) {
            return;
        }
        removeExpiredWindows(now);
        if (sourceRequestWindows.size() < maximumTrackedSources) {
            return;
        }
        Iterator<String> sourceKeys = sourceRequestWindows.keySet().iterator();
        if (sourceKeys.hasNext()) {
            sourceKeys.next();
            sourceKeys.remove();
        }
    }

    private void removeExpiredWindows(Instant now) {
        Iterator<RequestWindow> requestWindows = sourceRequestWindows.values().iterator();
        while (requestWindows.hasNext()) {
            if (requestWindows.next().hasExpired(now, requestWindow)) {
                requestWindows.remove();
            }
        }
    }

    private String sourceKey(String sourceIdentifier) {
        if (sourceIdentifier == null || sourceIdentifier.isBlank()) {
            return UNKNOWN_SOURCE_KEY;
        }
        String normalizedSourceIdentifier = sourceIdentifier.trim();
        if (normalizedSourceIdentifier.length() > MAXIMUM_SOURCE_IDENTIFIER_LENGTH) {
            return OVERSIZED_SOURCE_KEY;
        }
        return normalizedSourceIdentifier;
    }

    private static void requirePositive(int value, String valueName) {
        if (value < 1) {
            throw new IllegalArgumentException(valueName + " must be positive.");
        }
    }

    private static void requirePositive(Duration value, String valueName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(valueName + " must be positive.");
        }
    }

    private static final class RequestWindow {
        private final Instant startedAt;
        private int requestCount;

        private RequestWindow(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private boolean hasExpired(Instant now, Duration requestWindow) {
            return !now.isBefore(startedAt.plus(requestWindow));
        }
    }

    public static final class Admission implements AutoCloseable {
        private final Semaphore concurrentRequestPermits;
        private final int retryAfterSeconds;
        private boolean released;

        private Admission(Semaphore concurrentRequestPermits, int retryAfterSeconds) {
            this.concurrentRequestPermits = concurrentRequestPermits;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        private static Admission accepted(Semaphore concurrentRequestPermits) {
            return new Admission(concurrentRequestPermits, 0);
        }

        private static Admission rejected(int retryAfterSeconds) {
            return new Admission(null, retryAfterSeconds);
        }

        public boolean isAccepted() {
            return concurrentRequestPermits != null;
        }

        public int getRetryAfterSeconds() {
            return retryAfterSeconds;
        }

        @Override
        public synchronized void close() {
            if (concurrentRequestPermits != null && !released) {
                concurrentRequestPermits.release();
                released = true;
            }
        }
    }
}
