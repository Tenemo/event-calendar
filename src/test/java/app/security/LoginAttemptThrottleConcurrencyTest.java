package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

final class LoginAttemptThrottleConcurrencyTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-17T10:00:00Z"),
            ZoneOffset.UTC);
    private static final String TEST_SOURCE = "192.0.2.10";

    @Test
    void concurrentFailuresForOneIdentityCannotLoseUpdatesOrCreateDuplicateState() throws Exception {
        LoginAttemptThrottle throttle = throttle(5, 100, 100, 100);

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
        LoginAttemptThrottle throttle = throttle(2, 2, 100, 100);
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
        LoginAttemptThrottle throttle = throttle(1, 1, 8, 8);
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
    void validationLocksUseTheSameBoundedSourceIdentityAsFailureTracking() {
        LoginAttemptThrottle throttle = throttle(5, 25, 100, 100);

        assertAll(
                () -> assertSame(
                        throttle.validationLock(" 192.0.2.10 "),
                        throttle.validationLock("192.0.2.10")),
                () -> assertSame(
                        throttle.validationLock("a".repeat(129)),
                        throttle.validationLock("b".repeat(256))),
                () -> assertSame(
                        throttle.validationLock(null),
                        throttle.validationLock("   ")));
    }

    private static LoginAttemptThrottle throttle(
            int maximumFailedAttemptsPerUsernameAndSource,
            int maximumFailedAttemptsPerSource,
            int maximumTrackedUsernameAndSourceCombinations,
            int maximumTrackedSources) {
        return new LoginAttemptThrottle(
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
