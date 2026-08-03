package app.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class SignInAttemptThrottle {
    static final int MAXIMUM_FAILED_ATTEMPTS = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final int MAXIMUM_TRACKED_KEYS = 1_000;
    static final int ATTEMPT_SEGMENT_COUNT = 50;
    static final int MAXIMUM_TRACKED_KEYS_PER_SEGMENT =
            MAXIMUM_TRACKED_KEYS / ATTEMPT_SEGMENT_COUNT;

    private static final int MAXIMUM_USERNAME_LENGTH = 80;
    private static final int MAXIMUM_SOURCE_LENGTH = 128;

    private final Clock clock;
    private final List<Map<AttemptKey, AttemptWindow>> attemptSegments;

    public SignInAttemptThrottle() {
        this(Clock.systemUTC());
    }

    SignInAttemptThrottle(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        List<Map<AttemptKey, AttemptWindow>> segments =
                new ArrayList<>(ATTEMPT_SEGMENT_COUNT);
        for (int segmentNumber = 0;
                segmentNumber < ATTEMPT_SEGMENT_COUNT;
                segmentNumber++) {
            segments.add(new LinkedHashMap<>(16, 0.75f, true));
        }
        attemptSegments = List.copyOf(segments);
    }

    synchronized boolean reserveAuthenticationAttempt(String username, String source) {
        Instant now = clock.instant();
        AttemptKey key = new AttemptKey(bounded(username, MAXIMUM_USERNAME_LENGTH), bounded(source, MAXIMUM_SOURCE_LENGTH));
        Map<AttemptKey, AttemptWindow> attempts = attemptSegment(key);
        removeExpiredAttempts(attempts, now);
        AttemptWindow window = attempts.get(key);
        if (window != null && window.attemptCount >= MAXIMUM_FAILED_ATTEMPTS) {
            return false;
        }
        if (window == null) {
            if (attempts.size() >= MAXIMUM_TRACKED_KEYS_PER_SEGMENT
                    && !removeLeastRecentlyUsedUnblockedAttempt(attempts)) {
                return false;
            }
            window = new AttemptWindow(now);
            attempts.put(key, window);
        }
        window.attemptCount++;
        return true;
    }

    synchronized void withdrawAuthenticationAttempt(String username, String source) {
        AttemptKey key = new AttemptKey(bounded(username, MAXIMUM_USERNAME_LENGTH), bounded(source, MAXIMUM_SOURCE_LENGTH));
        decrement(attemptSegment(key), key);
    }

    synchronized void releaseSuccessfulAttempt(String username, String source) {
        AttemptKey key = new AttemptKey(bounded(username, MAXIMUM_USERNAME_LENGTH), bounded(source, MAXIMUM_SOURCE_LENGTH));
        attemptSegment(key).remove(key);
    }

    private void decrement(Map<AttemptKey, AttemptWindow> attempts, AttemptKey key) {
        AttemptWindow window = attempts.get(key);
        if (window == null || --window.attemptCount <= 0) {
            attempts.remove(key);
        }
    }

    private void removeExpiredAttempts(
            Map<AttemptKey, AttemptWindow> attempts,
            Instant now) {
        attempts.values().removeIf(window -> !now.isBefore(window.startedAt.plus(FAILURE_WINDOW)));
    }

    private boolean removeLeastRecentlyUsedUnblockedAttempt(
            Map<AttemptKey, AttemptWindow> attempts) {
        Iterator<Map.Entry<AttemptKey, AttemptWindow>> entries =
                attempts.entrySet().iterator();
        while (entries.hasNext()) {
            AttemptWindow window = entries.next().getValue();
            if (window.attemptCount < MAXIMUM_FAILED_ATTEMPTS) {
                entries.remove();
                return true;
            }
        }
        return false;
    }

    private Map<AttemptKey, AttemptWindow> attemptSegment(AttemptKey key) {
        return attemptSegments.get(attemptSegmentIndex(key));
    }

    static int attemptSegmentIndex(String source) {
        return Math.floorMod(
                bounded(source, MAXIMUM_SOURCE_LENGTH).hashCode(),
                ATTEMPT_SEGMENT_COUNT);
    }

    private static int attemptSegmentIndex(AttemptKey key) {
        return attemptSegmentIndex(key.source());
    }

    private static String bounded(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return "<unknown>";
        }
        String normalizedValue = value.trim();
        return normalizedValue.length() <= maximumLength ? normalizedValue : "<oversized>";
    }

    private record AttemptKey(String username, String source) {
    }

    private static final class AttemptWindow {
        private final Instant startedAt;
        private int attemptCount;

        private AttemptWindow(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }
}
