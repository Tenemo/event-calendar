package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class SignInAttemptThrottleConcurrencyTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-17T10:00:00Z"),
            ZoneOffset.UTC);
    private static final String TEST_SOURCE = "192.0.2.10";

    @Test
    void concurrentFailuresForOneIdentityCannotLoseUpdatesOrCreateDuplicateState() throws Exception {
        SignInAttemptThrottle throttle = throttle(5, 100, 100, 100);

        runConcurrently(32, taskIndex ->
                throttle.recordFailedAuthentication("piotr", TEST_SOURCE));

        assertAll(
                () -> assertFalse(throttle.isAuthenticationAllowed("piotr", TEST_SOURCE)),
                () -> assertEquals(1, throttle.trackedUsernameAndSourceCount()),
                () -> assertEquals(1, throttle.trackedSourceCount()),
                () -> assertTrue(throttle.isAuthenticationAllowed("piotr", "198.51.100.20")));
    }

    @Test
    void concurrentFailuresFromIndependentSourcesRemainSourceAware() throws Exception {
        SignInAttemptThrottle throttle = throttle(2, 2, 100, 100);
        int sourceCount = 24;

        runConcurrently(sourceCount, sourceIndex -> throttle.recordFailedAuthentication(
                "shared-username",
                "198.51.100." + sourceIndex));

        assertAll(
                () -> assertEquals(sourceCount, throttle.trackedUsernameAndSourceCount()),
                () -> assertEquals(sourceCount, throttle.trackedSourceCount()));
        for (int sourceIndex = 0; sourceIndex < sourceCount; sourceIndex++) {
            String source = "198.51.100." + sourceIndex;
            assertTrue(
                    throttle.isAuthenticationAllowed("shared-username", source),
                    () -> "A single failure must not block the identity at source " + source + ".");
        }
    }

    @Test
    void concurrentIdentifierSprayingCannotExceedBoundsOrEvictAnActiveBlock() throws Exception {
        SignInAttemptThrottle throttle = throttle(1, 1, 8, 8);
        throttle.recordFailedAuthentication("protected-user", TEST_SOURCE);

        runConcurrently(128, sprayIndex -> throttle.recordFailedAuthentication(
                "sprayed-user-" + sprayIndex,
                "203.0.113." + sprayIndex));

        assertAll(
                () -> assertEquals(8, throttle.trackedUsernameAndSourceCount()),
                () -> assertEquals(8, throttle.trackedSourceCount()),
                () -> assertFalse(throttle.isAuthenticationAllowed("protected-user", TEST_SOURCE)),
                () -> assertFalse(throttle.isAuthenticationAllowed("unseen-user", "198.51.100.200")));
    }

    @Test
    void concurrentReservationsForOneIdentityCannotExceedTheFailureLimit() throws Exception {
        SignInAttemptThrottle throttle = throttle(5, 100, 100, 100);
        List<Boolean> reservations = synchronizedResults();

        runConcurrently(32, taskIndex ->
                reservations.add(throttle.reserveAuthenticationAttempt("piotr", TEST_SOURCE)));

        assertAll(
                () -> assertEquals(
                        5,
                        reservations.stream().filter(Boolean::booleanValue).count(),
                        "Reserving before verifying is what bounds concurrent guesses at the limit."),
                () -> assertFalse(throttle.isAuthenticationAllowed("piotr", TEST_SOURCE)),
                () -> assertTrue(throttle.isAuthenticationAllowed("piotr", "198.51.100.20")));
    }

    @Test
    void concurrentProvenSourceReservationsCannotBypassLimitsWhenPrimaryTrackingIsSaturated()
            throws Exception {
        SignInAttemptThrottle throttle = throttle(5, 5, 1, 1);
        String provenSource = "192.0.2.50";
        assertTrue(throttle.reserveAuthenticationAttempt("piotr", provenSource));
        throttle.releaseSuccessfulAttempt("piotr", provenSource);
        for (int failureIndex = 0; failureIndex < 5; failureIndex++) {
            throttle.recordFailedAuthentication("intruder", "198.51.100.10");
        }
        List<Boolean> reservations = synchronizedResults();

        runConcurrently(32, taskIndex ->
                reservations.add(throttle.reserveAuthenticationAttempt("target", provenSource)));

        assertAll(
                () -> assertEquals(
                        5,
                        reservations.stream().filter(Boolean::booleanValue).count(),
                        "Only reservations recorded in the bounded proven-source pool may be admitted."),
                () -> assertFalse(throttle.isAuthenticationAllowed("target", provenSource)),
                () -> assertEquals(1, throttle.reservedProvenUsernameAndSourceCount()),
                () -> assertEquals(1, throttle.reservedProvenSourceCount()));
    }

    @Test
    void releasingASuccessfulAttemptLeavesNoTrackedStateBehind() {
        SignInAttemptThrottle throttle = throttle(5, 25, 100, 100);

        assertTrue(throttle.reserveAuthenticationAttempt("piotr", TEST_SOURCE));
        throttle.releaseSuccessfulAttempt("piotr", TEST_SOURCE);

        assertAll(
                () -> assertEquals(0, throttle.trackedUsernameAndSourceCount()),
                () -> assertEquals(0, throttle.trackedSourceCount()),
                () -> assertTrue(throttle.isAuthenticationAllowed("piotr", TEST_SOURCE)));
    }

    @Test
    void repeatedSuccessfulSignInsFromOneSourceNeverExhaustTheSourceLimit() {
        SignInAttemptThrottle throttle = throttle(5, 3, 100, 100);

        for (int signInIndex = 0; signInIndex < 20; signInIndex++) {
            assertTrue(
                    throttle.reserveAuthenticationAttempt("person-" + signInIndex, TEST_SOURCE),
                    "A successful sign-in must not consume the source failure budget.");
            throttle.releaseSuccessfulAttempt("person-" + signInIndex, TEST_SOURCE);
        }

        assertEquals(0, throttle.trackedSourceCount());
    }

    @Test
    void withdrawingAReservationRestoresExactlyTheGenuineFailureCount() {
        SignInAttemptThrottle throttle = throttle(5, 3, 100, 100);

        throttle.recordFailedAuthentication("intruder", TEST_SOURCE);
        throttle.recordFailedAuthentication("intruder", TEST_SOURCE);
        assertTrue(throttle.reserveAuthenticationAttempt("piotr", TEST_SOURCE));
        assertFalse(
                throttle.isAuthenticationAllowed("unseen-person", TEST_SOURCE),
                "The reservation is the third counted attempt, so the source limit is reached.");

        throttle.releaseSuccessfulAttempt("piotr", TEST_SOURCE);

        assertAll(
                () -> assertEquals(1, throttle.trackedSourceCount(), "Other identities' failures must survive."),
                () -> assertTrue(
                        throttle.isAuthenticationAllowed("unseen-person", TEST_SOURCE),
                        "Withdrawing the reservation must also lift the block it armed."));

        throttle.recordFailedAuthentication("intruder", TEST_SOURCE);

        assertFalse(
                throttle.isAuthenticationAllowed("unseen-person", TEST_SOURCE),
                "Withdrawal must restore the count to two, so one more genuine failure reaches the limit again.");
    }

    private static List<Boolean> synchronizedResults() {
        return java.util.Collections.synchronizedList(new ArrayList<>());
    }

    private static SignInAttemptThrottle throttle(
            int maximumFailedAttemptsPerUsernameAndSource,
            int maximumFailedAttemptsPerSource,
            int maximumTrackedUsernameAndSourceCombinations,
            int maximumTrackedSources) {
        return new SignInAttemptThrottle(
                FIXED_CLOCK,
                maximumFailedAttemptsPerUsernameAndSource,
                maximumFailedAttemptsPerSource,
                Duration.ofMinutes(15),
                Duration.ofMinutes(15),
                maximumTrackedUsernameAndSourceCombinations,
                maximumTrackedSources);
    }

    private static void runConcurrently(int taskCount, IndexedTask task) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(taskCount);
        CountDownLatch readyTasks = new CountDownLatch(taskCount);
        CountDownLatch startTasks = new CountDownLatch(1);
        List<Future<?>> taskResults = new ArrayList<>();
        try {
            for (int taskIndex = 0; taskIndex < taskCount; taskIndex++) {
                int capturedTaskIndex = taskIndex;
                taskResults.add(executorService.submit(() -> {
                    readyTasks.countDown();
                    assertTrue(startTasks.await(10, TimeUnit.SECONDS));
                    task.run(capturedTaskIndex);
                    return null;
                }));
            }

            assertTrue(readyTasks.await(10, TimeUnit.SECONDS));
            startTasks.countDown();
            for (Future<?> taskResult : taskResults) {
                taskResult.get(10, TimeUnit.SECONDS);
            }
        } finally {
            startTasks.countDown();
            executorService.shutdownNow();
            assertTrue(executorService.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @FunctionalInterface
    private interface IndexedTask {
        void run(int taskIndex);
    }
}
