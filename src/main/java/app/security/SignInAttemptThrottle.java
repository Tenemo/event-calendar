package app.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class SignInAttemptThrottle {
    static final int MAXIMUM_FAILED_ATTEMPTS = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final int MAXIMUM_TRACKED_KEYS = 1_000;

    private static final int MAXIMUM_USERNAME_LENGTH = 80;
    private static final int MAXIMUM_SOURCE_LENGTH = 128;

    private final Clock clock;
    private final Map<AttemptKey, AttemptWindow> attempts =
            new LinkedHashMap<>(16, 0.75f, true);

    public SignInAttemptThrottle() {
        this(Clock.systemUTC());
    }

    SignInAttemptThrottle(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    synchronized boolean reserveAuthenticationAttempt(String username, String source) {
        Instant now = clock.instant();
        removeExpiredAttempts(now);
        AttemptKey key = new AttemptKey(bounded(username, MAXIMUM_USERNAME_LENGTH), bounded(source, MAXIMUM_SOURCE_LENGTH));
        AttemptWindow window = attempts.get(key);
        if (window != null && window.attemptCount >= MAXIMUM_FAILED_ATTEMPTS) {
            return false;
        }
        if (window == null) {
            if (attempts.size() >= MAXIMUM_TRACKED_KEYS) {
                attempts.remove(attempts.keySet().iterator().next());
            }
            window = new AttemptWindow(now);
            attempts.put(key, window);
        }
        window.attemptCount++;
        return true;
    }

    synchronized void withdrawAuthenticationAttempt(String username, String source) {
        decrement(new AttemptKey(bounded(username, MAXIMUM_USERNAME_LENGTH), bounded(source, MAXIMUM_SOURCE_LENGTH)));
    }

    synchronized void releaseSuccessfulAttempt(String username, String source) {
        attempts.remove(new AttemptKey(bounded(username, MAXIMUM_USERNAME_LENGTH), bounded(source, MAXIMUM_SOURCE_LENGTH)));
    }

    private void decrement(AttemptKey key) {
        AttemptWindow window = attempts.get(key);
        if (window == null || --window.attemptCount <= 0) {
            attempts.remove(key);
        }
    }

    private void removeExpiredAttempts(Instant now) {
        attempts.values().removeIf(window -> !now.isBefore(window.startedAt.plus(FAILURE_WINDOW)));
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
