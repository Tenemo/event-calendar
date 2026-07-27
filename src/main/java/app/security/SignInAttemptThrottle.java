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
    static final int MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE = 5;
    static final int MAXIMUM_FAILED_ATTEMPTS_PER_SOURCE = 25;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final int MAXIMUM_TRACKED_KEYS = 1_000;

    private static final int MAXIMUM_TRACKED_USERNAME_LENGTH = 80;
    private static final String OVERSIZED_USERNAME_KEY = "<oversized-username>";

    private final Clock clock;
    private final int maximumUsernameAndSourceAttempts;
    private final int maximumSourceAttempts;
    private final Duration failureWindow;
    private final int maximumTrackedKeys;
    private final Map<UsernameAndSourceKey, AttemptWindow> usernameAndSourceAttempts =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, AttemptWindow> sourceAttempts =
            new LinkedHashMap<>(16, 0.75f, true);

    public SignInAttemptThrottle() {
        this(
                Clock.systemUTC(),
                MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE,
                MAXIMUM_FAILED_ATTEMPTS_PER_SOURCE,
                FAILURE_WINDOW,
                MAXIMUM_TRACKED_KEYS);
    }

    SignInAttemptThrottle(
            Clock clock,
            int maximumUsernameAndSourceAttempts,
            int maximumSourceAttempts,
            Duration failureWindow,
            int maximumTrackedKeys) {
        this.clock = Objects.requireNonNull(clock);
        this.maximumUsernameAndSourceAttempts = requirePositive(
                maximumUsernameAndSourceAttempts,
                "Maximum username and source attempts");
        this.maximumSourceAttempts = requirePositive(
                maximumSourceAttempts,
                "Maximum source attempts");
        this.failureWindow = requirePositive(failureWindow, "Failure window");
        this.maximumTrackedKeys = requirePositive(maximumTrackedKeys, "Maximum tracked keys");
    }

    synchronized boolean reserveAuthenticationAttempt(
            String normalizedUsername,
            String sourceIdentifier) {
        Instant now = clock.instant();
        String sourceKey = ClientSourceKey.of(sourceIdentifier);
        UsernameAndSourceKey usernameAndSourceKey = new UsernameAndSourceKey(
                usernameKey(normalizedUsername),
                sourceKey);
        AttemptWindow usernameAndSourceWindow = activeWindow(
                usernameAndSourceAttempts,
                usernameAndSourceKey,
                now);
        AttemptWindow sourceWindow = activeWindow(sourceAttempts, sourceKey, now);
        if (attemptCount(usernameAndSourceWindow) >= maximumUsernameAndSourceAttempts
                || attemptCount(sourceWindow) >= maximumSourceAttempts) {
            return false;
        }

        increment(usernameAndSourceAttempts, usernameAndSourceKey, usernameAndSourceWindow, now);
        increment(sourceAttempts, sourceKey, sourceWindow, now);
        return true;
    }

    synchronized void withdrawAuthenticationAttempt(
            String normalizedUsername,
            String sourceIdentifier) {
        String sourceKey = ClientSourceKey.of(sourceIdentifier);
        decrement(
                usernameAndSourceAttempts,
                new UsernameAndSourceKey(usernameKey(normalizedUsername), sourceKey));
        decrement(sourceAttempts, sourceKey);
    }

    synchronized void releaseSuccessfulAttempt(
            String normalizedUsername,
            String sourceIdentifier) {
        String sourceKey = ClientSourceKey.of(sourceIdentifier);
        usernameAndSourceAttempts.remove(
                new UsernameAndSourceKey(usernameKey(normalizedUsername), sourceKey));
        decrement(sourceAttempts, sourceKey);
    }

    synchronized int trackedUsernameAndSourceCount() {
        removeExpiredWindows(usernameAndSourceAttempts, clock.instant());
        return usernameAndSourceAttempts.size();
    }

    synchronized int trackedSourceCount() {
        removeExpiredWindows(sourceAttempts, clock.instant());
        return sourceAttempts.size();
    }

    private <KeyType> AttemptWindow activeWindow(
            Map<KeyType, AttemptWindow> windows,
            KeyType key,
            Instant now) {
        AttemptWindow window = windows.get(key);
        if (window != null && window.hasExpired(now, failureWindow)) {
            windows.remove(key);
            return null;
        }
        return window;
    }

    private <KeyType> void increment(
            Map<KeyType, AttemptWindow> windows,
            KeyType key,
            AttemptWindow window,
            Instant now) {
        AttemptWindow activeWindow = window;
        if (activeWindow == null) {
            if (windows.size() >= maximumTrackedKeys) {
                windows.remove(windows.keySet().iterator().next());
            }
            activeWindow = new AttemptWindow(now);
            windows.put(key, activeWindow);
        }
        activeWindow.attemptCount++;
    }

    private static <KeyType> void decrement(
            Map<KeyType, AttemptWindow> windows,
            KeyType key) {
        AttemptWindow window = windows.get(key);
        if (window == null) {
            return;
        }
        window.attemptCount--;
        if (window.attemptCount <= 0) {
            windows.remove(key);
        }
    }

    private <KeyType> void removeExpiredWindows(
            Map<KeyType, AttemptWindow> windows,
            Instant now) {
        windows.values().removeIf(window -> window.hasExpired(now, failureWindow));
    }

    private static int attemptCount(AttemptWindow window) {
        return window == null ? 0 : window.attemptCount;
    }

    private static String usernameKey(String normalizedUsername) {
        if (normalizedUsername == null) {
            return "";
        }
        return normalizedUsername.length() <= MAXIMUM_TRACKED_USERNAME_LENGTH
                ? normalizedUsername
                : OVERSIZED_USERNAME_KEY;
    }

    private static int requirePositive(int value, String description) {
        if (value < 1) {
            throw new IllegalArgumentException(description + " must be positive.");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String description) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(description + " must be positive.");
        }
        return value;
    }

    private record UsernameAndSourceKey(String username, String sourceIdentifier) {
    }

    private static final class AttemptWindow {
        private final Instant startedAt;
        private int attemptCount;

        private AttemptWindow(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private boolean hasExpired(Instant now, Duration duration) {
            return !now.isBefore(startedAt.plus(duration));
        }
    }
}
