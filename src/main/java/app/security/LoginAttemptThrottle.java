package app.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class LoginAttemptThrottle {
    static final int MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE = 5;
    static final int MAXIMUM_FAILED_ATTEMPTS_PER_SOURCE = 25;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final Duration BLOCK_DURATION = Duration.ofMinutes(15);
    static final int MAXIMUM_TRACKED_USERNAME_AND_SOURCE_COMBINATIONS = 10_000;
    static final int MAXIMUM_TRACKED_SOURCES = 2_000;

    private static final int VALIDATION_LOCK_COUNT = 64;
    private static final int MAXIMUM_TRACKED_USERNAME_LENGTH = 80;
    private static final int MAXIMUM_TRACKED_SOURCE_IDENTIFIER_LENGTH = 128;
    private static final String OVERSIZED_USERNAME_KEY = "<oversized-username>";
    private static final String OVERSIZED_SOURCE_KEY = "<oversized-source>";
    private static final String UNKNOWN_SOURCE_KEY = "<unknown-source>";

    private final Clock clock;
    private final int maximumFailedAttemptsPerUsernameAndSource;
    private final int maximumFailedAttemptsPerSource;
    private final Duration failureWindow;
    private final Duration blockDuration;
    private final int maximumTrackedUsernameAndSourceCombinations;
    private final int maximumTrackedSources;
    private final Map<UsernameAndSourceKey, FailedLoginState> usernameAndSourceFailures;
    private final Map<String, FailedLoginState> sourceFailures;
    private final Object[] validationLocks;
    private Instant saturationBlockedUntil;

    public LoginAttemptThrottle() {
        this(
                Clock.systemUTC(),
                MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE,
                MAXIMUM_FAILED_ATTEMPTS_PER_SOURCE,
                FAILURE_WINDOW,
                BLOCK_DURATION,
                MAXIMUM_TRACKED_USERNAME_AND_SOURCE_COMBINATIONS,
                MAXIMUM_TRACKED_SOURCES);
    }

    LoginAttemptThrottle(
            Clock clock,
            int maximumFailedAttemptsPerUsernameAndSource,
            int maximumFailedAttemptsPerSource,
            Duration failureWindow,
            Duration blockDuration,
            int maximumTrackedUsernameAndSourceCombinations,
            int maximumTrackedSources) {
        this.clock = Objects.requireNonNull(clock);
        requirePositive(maximumFailedAttemptsPerUsernameAndSource, "Maximum username and source failures");
        requirePositive(maximumFailedAttemptsPerSource, "Maximum source failures");
        requirePositive(failureWindow, "Failure window");
        requirePositive(blockDuration, "Block duration");
        requirePositive(maximumTrackedUsernameAndSourceCombinations, "Maximum tracked username and source combinations");
        requirePositive(maximumTrackedSources, "Maximum tracked sources");

        this.maximumFailedAttemptsPerUsernameAndSource = maximumFailedAttemptsPerUsernameAndSource;
        this.maximumFailedAttemptsPerSource = maximumFailedAttemptsPerSource;
        this.failureWindow = failureWindow;
        this.blockDuration = blockDuration;
        this.maximumTrackedUsernameAndSourceCombinations = maximumTrackedUsernameAndSourceCombinations;
        this.maximumTrackedSources = maximumTrackedSources;
        this.usernameAndSourceFailures = new LinkedHashMap<>(16, 0.75f, true);
        this.sourceFailures = new LinkedHashMap<>(16, 0.75f, true);
        this.validationLocks = new Object[VALIDATION_LOCK_COUNT];
        for (int validationLockIndex = 0; validationLockIndex < validationLocks.length; validationLockIndex++) {
            validationLocks[validationLockIndex] = new Object();
        }
    }

    Object validationLock(String sourceIdentifier) {
        String sourceKey = sourceKey(sourceIdentifier);
        int validationLockIndex = Math.floorMod(sourceKey.hashCode(), validationLocks.length);
        return validationLocks[validationLockIndex];
    }

    synchronized boolean isAuthenticationAllowed(String normalizedUsername, String sourceIdentifier) {
        Instant now = clock.instant();
        String normalizedSourceKey = sourceKey(sourceIdentifier);
        UsernameAndSourceKey usernameAndSourceKey = new UsernameAndSourceKey(
                usernameKey(normalizedUsername), normalizedSourceKey);
        if (isSaturationBlockActive(now)) {
            return false;
        }
        if (cannotTrackNewState(
                        sourceFailures,
                        normalizedSourceKey,
                        maximumTrackedSources,
                        now)
                || cannotTrackNewState(
                        usernameAndSourceFailures,
                        usernameAndSourceKey,
                        maximumTrackedUsernameAndSourceCombinations,
                        now)) {
            saturationBlockedUntil = now.plus(blockDuration);
            return false;
        }
        if (!isAuthenticationAllowed(sourceFailures, normalizedSourceKey, now)) {
            return false;
        }
        return isAuthenticationAllowed(usernameAndSourceFailures, usernameAndSourceKey, now);
    }

    synchronized void recordFailedAuthentication(String normalizedUsername, String sourceIdentifier) {
        Instant now = clock.instant();
        String normalizedSourceKey = sourceKey(sourceIdentifier);
        UsernameAndSourceKey usernameAndSourceKey = new UsernameAndSourceKey(
                usernameKey(normalizedUsername), normalizedSourceKey);
        boolean usernameAndSourceFailureRecorded = recordFailedAuthentication(
                usernameAndSourceFailures,
                usernameAndSourceKey,
                maximumFailedAttemptsPerUsernameAndSource,
                maximumTrackedUsernameAndSourceCombinations,
                now);
        boolean sourceFailureRecorded = recordFailedAuthentication(
                sourceFailures,
                normalizedSourceKey,
                maximumFailedAttemptsPerSource,
                maximumTrackedSources,
                now);
        if (!usernameAndSourceFailureRecorded || !sourceFailureRecorded) {
            saturationBlockedUntil = now.plus(blockDuration);
        }
    }

    synchronized void clearUsernameAndSourceFailures(String normalizedUsername, String sourceIdentifier) {
        String normalizedSourceKey = sourceKey(sourceIdentifier);
        usernameAndSourceFailures.remove(new UsernameAndSourceKey(usernameKey(normalizedUsername), normalizedSourceKey));
    }

    synchronized int trackedUsernameAndSourceCount() {
        return usernameAndSourceFailures.size();
    }

    synchronized int trackedSourceCount() {
        return sourceFailures.size();
    }

    private <KeyType> boolean isAuthenticationAllowed(
            Map<KeyType, FailedLoginState> failedLogins,
            KeyType loginKey,
            Instant now) {
        FailedLoginState failedLoginState = failedLogins.get(loginKey);
        if (failedLoginState == null) {
            return true;
        }
        if (failedLoginState.isActivelyBlocked(now)) {
            return false;
        }
        if (failedLoginState.hasExpired(now, failureWindow)) {
            failedLogins.remove(loginKey);
        }
        return true;
    }

    private <KeyType> boolean recordFailedAuthentication(
            Map<KeyType, FailedLoginState> failedLogins,
            KeyType loginKey,
            int maximumFailedAttempts,
            int maximumTrackedKeys,
            Instant now) {
        FailedLoginState failedLoginState = failedLogins.get(loginKey);
        if (failedLoginState != null && failedLoginState.hasExpired(now, failureWindow)) {
            failedLogins.remove(loginKey);
            failedLoginState = null;
        }
        if (failedLoginState == null) {
            if (!makeRoomForNewState(failedLogins, maximumTrackedKeys, now)) {
                return false;
            }
            failedLoginState = new FailedLoginState(now);
            failedLogins.put(loginKey, failedLoginState);
        }
        if (failedLoginState.isActivelyBlocked(now)) {
            return true;
        }

        failedLoginState.failedAttemptCount++;
        if (failedLoginState.failedAttemptCount >= maximumFailedAttempts) {
            failedLoginState.blockedUntil = now.plus(blockDuration);
        }
        return true;
    }

    private boolean isSaturationBlockActive(Instant now) {
        if (saturationBlockedUntil == null) {
            return false;
        }
        if (now.isBefore(saturationBlockedUntil)) {
            return true;
        }
        saturationBlockedUntil = null;
        return false;
    }

    private <KeyType> boolean cannotTrackNewState(
            Map<KeyType, FailedLoginState> failedLogins,
            KeyType loginKey,
            int maximumTrackedKeys,
            Instant now) {
        removeExpiredStates(failedLogins, now);
        if (failedLogins.containsKey(loginKey) || failedLogins.size() < maximumTrackedKeys) {
            return false;
        }
        return failedLogins.values().stream().allMatch(failedLoginState -> failedLoginState.isActivelyBlocked(now));
    }

    private <KeyType> boolean makeRoomForNewState(
            Map<KeyType, FailedLoginState> failedLogins,
            int maximumTrackedKeys,
            Instant now) {
        removeExpiredStates(failedLogins, now);
        if (failedLogins.size() < maximumTrackedKeys) {
            return true;
        }

        Iterator<Map.Entry<KeyType, FailedLoginState>> failedLoginEntries = failedLogins.entrySet().iterator();
        while (failedLoginEntries.hasNext()) {
            Map.Entry<KeyType, FailedLoginState> failedLoginEntry = failedLoginEntries.next();
            if (!failedLoginEntry.getValue().isActivelyBlocked(now)) {
                failedLoginEntries.remove();
                return true;
            }
        }
        return false;
    }

    private <KeyType> void removeExpiredStates(Map<KeyType, FailedLoginState> failedLogins, Instant now) {
        Iterator<Map.Entry<KeyType, FailedLoginState>> failedLoginEntries = failedLogins.entrySet().iterator();
        while (failedLoginEntries.hasNext()) {
            if (failedLoginEntries.next().getValue().hasExpired(now, failureWindow)) {
                failedLoginEntries.remove();
            }
        }
    }

    private String usernameKey(String normalizedUsername) {
        if (normalizedUsername == null) {
            return "";
        }
        if (normalizedUsername.length() > MAXIMUM_TRACKED_USERNAME_LENGTH) {
            return OVERSIZED_USERNAME_KEY;
        }
        return normalizedUsername;
    }

    private String sourceKey(String sourceIdentifier) {
        if (sourceIdentifier == null || sourceIdentifier.isBlank()) {
            return UNKNOWN_SOURCE_KEY;
        }
        String normalizedSourceIdentifier = sourceIdentifier.trim();
        if (normalizedSourceIdentifier.length() > MAXIMUM_TRACKED_SOURCE_IDENTIFIER_LENGTH) {
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

    private record UsernameAndSourceKey(String username, String sourceIdentifier) {
    }

    private static final class FailedLoginState {
        private final Instant windowStartedAt;
        private int failedAttemptCount;
        private Instant blockedUntil;

        private FailedLoginState(Instant windowStartedAt) {
            this.windowStartedAt = windowStartedAt;
        }

        private boolean isActivelyBlocked(Instant now) {
            return blockedUntil != null && now.isBefore(blockedUntil);
        }

        private boolean hasExpired(Instant now, Duration failureWindow) {
            if (blockedUntil != null) {
                return !now.isBefore(blockedUntil);
            }
            return !now.isBefore(windowStartedAt.plus(failureWindow));
        }
    }
}
