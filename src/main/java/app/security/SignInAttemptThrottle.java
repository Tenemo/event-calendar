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
public class SignInAttemptThrottle {
    static final int MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE = 5;
    static final int MAXIMUM_FAILED_ATTEMPTS_PER_SOURCE = 25;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final Duration BLOCK_DURATION = Duration.ofMinutes(15);
    static final int MAXIMUM_TRACKED_USERNAME_AND_SOURCE_COMBINATIONS = 10_000;
    static final int MAXIMUM_TRACKED_SOURCES = 2_000;

    /**
     * How long a saturation refusal lasts. Saturation means the tracker cannot admit a new key
     * without forgetting an active block, so it refuses every caller: the alternative is to forget
     * a block and let a brute-force attempt through. That refusal is global, so it is deliberately
     * far shorter than {@link #BLOCK_DURATION}, which punishes one identified attacker. Distinct
     * client sources are cheap to obtain — a single IPv6 allocation yields far more than
     * {@link #MAXIMUM_TRACKED_SOURCES} distinct /64 keys — so a long global refusal would let an
     * attacker deny sign-in to everyone. It re-arms on every attempt while saturation persists, so
     * a sustained attack still fails closed continuously.
     */
    static final Duration SATURATION_BLOCK_DURATION = Duration.ofSeconds(30);

    /**
     * How long a client source stays exempt from saturation refusals after signing in successfully.
     *
     * <p>Shortening {@link #SATURATION_BLOCK_DURATION} alone does not keep legitimate users signing
     * in: saturation re-arms on every attempt for as long as the tracker sits at capacity with every
     * entry blocked, so the refusal really lasts until those per-identity blocks expire. This exempt
     * set is what breaks that. A source only joins it by presenting correct credentials, which an
     * attacker filling the tracker with distinct sources cannot do. Membership permits the attempt
     * to use separately bounded reserved tracking capacity, while the source's own limits and blocks
     * still apply and no active block is ever forgotten.
     */
    static final Duration PROVEN_SOURCE_RETENTION = Duration.ofDays(1);

    static final int MAXIMUM_PROVEN_SOURCES = 1_000;

    private static final int MAXIMUM_TRACKED_USERNAME_LENGTH = 80;
    private static final String OVERSIZED_USERNAME_KEY = "<oversized-username>";

    private final Clock clock;
    private final int maximumFailedAttemptsPerUsernameAndSource;
    private final int maximumFailedAttemptsPerSource;
    private final Duration failureWindow;
    private final Duration blockDuration;
    private final int maximumTrackedUsernameAndSourceCombinations;
    private final int maximumTrackedSources;
    private final int maximumReservedProvenUsernameAndSourceCombinations;
    private final int maximumReservedProvenSources;
    private final Map<UsernameAndSourceKey, FailedSignInState> usernameAndSourceFailures;
    private final Map<String, FailedSignInState> sourceFailures;
    private final Map<UsernameAndSourceKey, FailedSignInState> reservedProvenUsernameAndSourceFailures;
    private final Map<String, FailedSignInState> reservedProvenSourceFailures;
    private final Map<String, Instant> provenSources;
    private Instant saturationBlockedUntil;

    public SignInAttemptThrottle() {
        this(
                Clock.systemUTC(),
                MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE,
                MAXIMUM_FAILED_ATTEMPTS_PER_SOURCE,
                FAILURE_WINDOW,
                BLOCK_DURATION,
                MAXIMUM_TRACKED_USERNAME_AND_SOURCE_COMBINATIONS,
                MAXIMUM_TRACKED_SOURCES);
    }

    SignInAttemptThrottle(
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
        // One source can create at most its source-wide attempt limit in distinct username states
        // before being blocked, so this bounded product preserves both throttle dimensions.
        this.maximumReservedProvenSources = Math.min(maximumTrackedSources, MAXIMUM_PROVEN_SOURCES);
        this.maximumReservedProvenUsernameAndSourceCombinations = saturatedProduct(
                maximumReservedProvenSources,
                maximumFailedAttemptsPerSource);
        this.usernameAndSourceFailures = new LinkedHashMap<>(16, 0.75f, true);
        this.sourceFailures = new LinkedHashMap<>(16, 0.75f, true);
        this.reservedProvenUsernameAndSourceFailures = new LinkedHashMap<>(16, 0.75f, true);
        this.reservedProvenSourceFailures = new LinkedHashMap<>(16, 0.75f, true);
        this.provenSources = new LinkedHashMap<>(16, 0.75f, true);
    }

    synchronized boolean isAuthenticationAllowed(String normalizedUsername, String sourceIdentifier) {
        Instant now = clock.instant();
        String normalizedSourceKey = ClientSourceKey.of(sourceIdentifier);
        UsernameAndSourceKey usernameAndSourceKey = new UsernameAndSourceKey(
                usernameKey(normalizedUsername), normalizedSourceKey);
        boolean provenSource = isProvenSource(normalizedSourceKey, now);
        if (!provenSource && isSaturationBlockActive(now)) {
            return false;
        }
        if (!isAuthenticationAllowed(sourceFailures, normalizedSourceKey, now)
                || !isAuthenticationAllowed(reservedProvenSourceFailures, normalizedSourceKey, now)
                || !isAuthenticationAllowed(usernameAndSourceFailures, usernameAndSourceKey, now)
                || !isAuthenticationAllowed(
                        reservedProvenUsernameAndSourceFailures,
                        usernameAndSourceKey,
                        now)) {
            return false;
        }

        return trackingTargets(usernameAndSourceKey, normalizedSourceKey, provenSource, now) != null;
    }

    synchronized void recordFailedAuthentication(String normalizedUsername, String sourceIdentifier) {
        Instant now = clock.instant();
        String normalizedSourceKey = ClientSourceKey.of(sourceIdentifier);
        UsernameAndSourceKey usernameAndSourceKey = new UsernameAndSourceKey(
                usernameKey(normalizedUsername), normalizedSourceKey);
        recordFailedAuthentication(
                usernameAndSourceKey,
                normalizedSourceKey,
                isProvenSource(normalizedSourceKey, now),
                now);
    }

    /**
     * Admits one attempt and immediately counts it as failed, in one atomic step.
     *
     * <p>Callers verify the password after this returns and call
     * {@link #releaseSuccessfulAttempt(String, String)} only when it turns out correct. Counting
     * first is what lets the expensive key derivation run outside this monitor: concurrent attempts
     * from one source cannot all pass an admission check before any of them is counted. An attempt
     * abandoned by an exception therefore stays counted, which fails closed.
     */
    synchronized boolean reserveAuthenticationAttempt(String normalizedUsername, String sourceIdentifier) {
        Instant now = clock.instant();
        String normalizedSourceKey = ClientSourceKey.of(sourceIdentifier);
        UsernameAndSourceKey usernameAndSourceKey = new UsernameAndSourceKey(
                usernameKey(normalizedUsername), normalizedSourceKey);
        boolean provenSource = isProvenSource(normalizedSourceKey, now);
        if ((!provenSource && isSaturationBlockActive(now))
                || !isAuthenticationAllowed(sourceFailures, normalizedSourceKey, now)
                || !isAuthenticationAllowed(reservedProvenSourceFailures, normalizedSourceKey, now)
                || !isAuthenticationAllowed(usernameAndSourceFailures, usernameAndSourceKey, now)
                || !isAuthenticationAllowed(
                        reservedProvenUsernameAndSourceFailures,
                        usernameAndSourceKey,
                        now)) {
            return false;
        }
        return recordFailedAuthentication(usernameAndSourceKey, normalizedSourceKey, provenSource, now);
    }

    /** Withdraws the reservation taken by {@link #reserveAuthenticationAttempt} after a correct password. */
    synchronized void releaseSuccessfulAttempt(String normalizedUsername, String sourceIdentifier) {
        clearUsernameAndSourceFailures(normalizedUsername, sourceIdentifier);
        String normalizedSourceKey = ClientSourceKey.of(sourceIdentifier);
        recordProvenSource(normalizedSourceKey, clock.instant());
        withdrawSourceReservation(sourceFailures, normalizedSourceKey);
        withdrawSourceReservation(reservedProvenSourceFailures, normalizedSourceKey);
    }

    synchronized void clearUsernameAndSourceFailures(String normalizedUsername, String sourceIdentifier) {
        String normalizedSourceKey = ClientSourceKey.of(sourceIdentifier);
        UsernameAndSourceKey usernameAndSourceKey = new UsernameAndSourceKey(
                usernameKey(normalizedUsername), normalizedSourceKey);
        usernameAndSourceFailures.remove(usernameAndSourceKey);
        reservedProvenUsernameAndSourceFailures.remove(usernameAndSourceKey);
    }

    synchronized int trackedUsernameAndSourceCount() {
        return usernameAndSourceFailures.size() + reservedProvenUsernameAndSourceFailures.size();
    }

    synchronized int trackedSourceCount() {
        return sourceFailures.size() + reservedProvenSourceFailures.size();
    }

    synchronized int reservedProvenUsernameAndSourceCount() {
        return reservedProvenUsernameAndSourceFailures.size();
    }

    synchronized int reservedProvenSourceCount() {
        return reservedProvenSourceFailures.size();
    }

    private boolean recordFailedAuthentication(
            UsernameAndSourceKey usernameAndSourceKey,
            String normalizedSourceKey,
            boolean provenSource,
            Instant now) {
        AuthenticationTrackingTargets trackingTargets =
                trackingTargets(usernameAndSourceKey, normalizedSourceKey, provenSource, now);
        if (trackingTargets == null) {
            return false;
        }

        boolean usernameAndSourceFailureRecorded = recordFailedAuthentication(
                trackingTargets.usernameAndSourceFailures(),
                usernameAndSourceKey,
                maximumFailedAttemptsPerUsernameAndSource,
                trackingTargets.maximumTrackedUsernameAndSourceCombinations(),
                now);
        boolean sourceFailureRecorded = recordFailedAuthentication(
                trackingTargets.sourceFailures(),
                normalizedSourceKey,
                maximumFailedAttemptsPerSource,
                trackingTargets.maximumTrackedSources(),
                now);
        if (!usernameAndSourceFailureRecorded || !sourceFailureRecorded) {
            saturationBlockedUntil = now.plus(saturationBlockDuration());
            return false;
        }
        return true;
    }

    private AuthenticationTrackingTargets trackingTargets(
            UsernameAndSourceKey usernameAndSourceKey,
            String normalizedSourceKey,
            boolean provenSource,
            Instant now) {
        boolean primaryUsernameAndSourceCapacityUnavailable = cannotTrackNewState(
                usernameAndSourceFailures,
                usernameAndSourceKey,
                maximumTrackedUsernameAndSourceCombinations,
                now);
        boolean primarySourceCapacityUnavailable = cannotTrackNewState(
                sourceFailures,
                normalizedSourceKey,
                maximumTrackedSources,
                now);
        if (primaryUsernameAndSourceCapacityUnavailable || primarySourceCapacityUnavailable) {
            saturationBlockedUntil = now.plus(saturationBlockDuration());
        }

        Map<UsernameAndSourceKey, FailedSignInState> usernameAndSourceTrackingMap = trackingMap(
                usernameAndSourceFailures,
                maximumTrackedUsernameAndSourceCombinations,
                reservedProvenUsernameAndSourceFailures,
                maximumReservedProvenUsernameAndSourceCombinations,
                usernameAndSourceKey,
                provenSource,
                now);
        Map<String, FailedSignInState> sourceTrackingMap = trackingMap(
                sourceFailures,
                maximumTrackedSources,
                reservedProvenSourceFailures,
                maximumReservedProvenSources,
                normalizedSourceKey,
                provenSource,
                now);
        if (usernameAndSourceTrackingMap == null || sourceTrackingMap == null) {
            return null;
        }

        return new AuthenticationTrackingTargets(
                usernameAndSourceTrackingMap,
                sourceTrackingMap,
                usernameAndSourceTrackingMap == usernameAndSourceFailures
                        ? maximumTrackedUsernameAndSourceCombinations
                        : maximumReservedProvenUsernameAndSourceCombinations,
                sourceTrackingMap == sourceFailures
                        ? maximumTrackedSources
                        : maximumReservedProvenSources);
    }

    private <KeyType> Map<KeyType, FailedSignInState> trackingMap(
            Map<KeyType, FailedSignInState> primaryFailures,
            int maximumPrimaryTrackedKeys,
            Map<KeyType, FailedSignInState> reservedProvenFailures,
            int maximumReservedProvenTrackedKeys,
            KeyType signInKey,
            boolean provenSource,
            Instant now) {
        removeExpiredStates(primaryFailures, now);
        removeExpiredStates(reservedProvenFailures, now);
        if (primaryFailures.containsKey(signInKey)) {
            return primaryFailures;
        }
        if (reservedProvenFailures.containsKey(signInKey)) {
            return reservedProvenFailures;
        }
        if (hasRoomForNewState(primaryFailures, maximumPrimaryTrackedKeys, now)) {
            return primaryFailures;
        }
        if (provenSource
                && hasRoomForNewState(
                        reservedProvenFailures,
                        maximumReservedProvenTrackedKeys,
                        now)) {
            return reservedProvenFailures;
        }
        return null;
    }

    private void withdrawSourceReservation(
            Map<String, FailedSignInState> trackedSourceFailures,
            String normalizedSourceKey) {
        FailedSignInState sourceState = trackedSourceFailures.get(normalizedSourceKey);
        if (sourceState == null) {
            return;
        }
        sourceState.withdrawAttempt(maximumFailedAttemptsPerSource);
        if (sourceState.failedAttemptCount == 0) {
            trackedSourceFailures.remove(normalizedSourceKey);
        }
    }

    private <KeyType> boolean isAuthenticationAllowed(
            Map<KeyType, FailedSignInState> failedSignIns,
            KeyType signInKey,
            Instant now) {
        FailedSignInState failedSignInState = failedSignIns.get(signInKey);
        if (failedSignInState == null) {
            return true;
        }
        if (failedSignInState.isActivelyBlocked(now)) {
            return false;
        }
        if (failedSignInState.hasExpired(now, failureWindow)) {
            failedSignIns.remove(signInKey);
        }
        return true;
    }

    private <KeyType> boolean recordFailedAuthentication(
            Map<KeyType, FailedSignInState> failedSignIns,
            KeyType signInKey,
            int maximumFailedAttempts,
            int maximumTrackedKeys,
            Instant now) {
        FailedSignInState failedSignInState = failedSignIns.get(signInKey);
        if (failedSignInState != null && failedSignInState.hasExpired(now, failureWindow)) {
            failedSignIns.remove(signInKey);
            failedSignInState = null;
        }
        if (failedSignInState == null) {
            if (!makeRoomForNewState(failedSignIns, maximumTrackedKeys, now)) {
                return false;
            }
            failedSignInState = new FailedSignInState(now);
            failedSignIns.put(signInKey, failedSignInState);
        }
        if (failedSignInState.isActivelyBlocked(now)) {
            return true;
        }

        failedSignInState.failedAttemptCount++;
        if (failedSignInState.failedAttemptCount >= maximumFailedAttempts) {
            failedSignInState.blockedUntil = now.plus(blockDuration);
        }
        return true;
    }

    private boolean isProvenSource(String normalizedSourceKey, Instant now) {
        removeStaleProvenSources(now);
        return provenSources.containsKey(normalizedSourceKey);
    }

    private void recordProvenSource(String normalizedSourceKey, Instant now) {
        removeStaleProvenSources(now);
        if (!provenSources.containsKey(normalizedSourceKey) && provenSources.size() >= MAXIMUM_PROVEN_SOURCES) {
            Iterator<String> provenSourceKeys = provenSources.keySet().iterator();
            if (provenSourceKeys.hasNext()) {
                provenSourceKeys.next();
                provenSourceKeys.remove();
            }
        }
        provenSources.put(normalizedSourceKey, now);
    }

    private void removeStaleProvenSources(Instant now) {
        provenSources.values().removeIf(provenAt -> !now.isBefore(provenAt.plus(PROVEN_SOURCE_RETENTION)));
    }

    synchronized int provenSourceCount() {
        removeStaleProvenSources(clock.instant());
        return provenSources.size();
    }

    private Duration saturationBlockDuration() {
        return SATURATION_BLOCK_DURATION.compareTo(blockDuration) < 0 ? SATURATION_BLOCK_DURATION : blockDuration;
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
            Map<KeyType, FailedSignInState> failedSignIns,
            KeyType signInKey,
            int maximumTrackedKeys,
            Instant now) {
        removeExpiredStates(failedSignIns, now);
        if (failedSignIns.containsKey(signInKey) || failedSignIns.size() < maximumTrackedKeys) {
            return false;
        }
        return failedSignIns.values().stream().allMatch(failedSignInState -> failedSignInState.isActivelyBlocked(now));
    }

    private <KeyType> boolean hasRoomForNewState(
            Map<KeyType, FailedSignInState> failedSignIns,
            int maximumTrackedKeys,
            Instant now) {
        if (failedSignIns.size() < maximumTrackedKeys) {
            return true;
        }
        return failedSignIns.values().stream()
                .anyMatch(failedSignInState -> !failedSignInState.isActivelyBlocked(now));
    }

    private <KeyType> boolean makeRoomForNewState(
            Map<KeyType, FailedSignInState> failedSignIns,
            int maximumTrackedKeys,
            Instant now) {
        removeExpiredStates(failedSignIns, now);
        if (failedSignIns.size() < maximumTrackedKeys) {
            return true;
        }

        Iterator<Map.Entry<KeyType, FailedSignInState>> failedSignInEntries = failedSignIns.entrySet().iterator();
        while (failedSignInEntries.hasNext()) {
            Map.Entry<KeyType, FailedSignInState> failedSignInEntry = failedSignInEntries.next();
            if (!failedSignInEntry.getValue().isActivelyBlocked(now)) {
                failedSignInEntries.remove();
                return true;
            }
        }
        return false;
    }

    private <KeyType> void removeExpiredStates(Map<KeyType, FailedSignInState> failedSignIns, Instant now) {
        Iterator<Map.Entry<KeyType, FailedSignInState>> failedSignInEntries = failedSignIns.entrySet().iterator();
        while (failedSignInEntries.hasNext()) {
            if (failedSignInEntries.next().getValue().hasExpired(now, failureWindow)) {
                failedSignInEntries.remove();
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

    private static int saturatedProduct(int firstFactor, int secondFactor) {
        long product = (long) firstFactor * secondFactor;
        return product >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) product;
    }

    private record AuthenticationTrackingTargets(
            Map<UsernameAndSourceKey, FailedSignInState> usernameAndSourceFailures,
            Map<String, FailedSignInState> sourceFailures,
            int maximumTrackedUsernameAndSourceCombinations,
            int maximumTrackedSources) {
    }

    private record UsernameAndSourceKey(String username, String sourceIdentifier) {
    }

    private static final class FailedSignInState {
        private final Instant windowStartedAt;
        private int failedAttemptCount;
        private Instant blockedUntil;

        private FailedSignInState(Instant windowStartedAt) {
            this.windowStartedAt = windowStartedAt;
        }

        private boolean isActivelyBlocked(Instant now) {
            return blockedUntil != null && now.isBefore(blockedUntil);
        }

        /**
         * Undoes one counted attempt. A block can only have been armed by the reservation being
         * withdrawn, because an already-blocked state refuses admission, so dropping back below the
         * limit also lifts that block.
         */
        private void withdrawAttempt(int maximumFailedAttempts) {
            if (failedAttemptCount > 0) {
                failedAttemptCount--;
            }
            if (failedAttemptCount < maximumFailedAttempts) {
                blockedUntil = null;
            }
        }

        private boolean hasExpired(Instant now, Duration failureWindow) {
            if (blockedUntil != null) {
                return !now.isBefore(blockedUntil);
            }
            return !now.isBefore(windowStartedAt.plus(failureWindow));
        }
    }
}
