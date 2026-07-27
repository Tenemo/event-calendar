package app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class SignInAttemptThrottleTest {
    private static final Instant TEST_START = Instant.parse("2026-07-16T10:00:00Z");

    @Test
    void blocksOnlyTheRepeatedUsernameAndSourcePairAtThePairLimit() {
        SignInAttemptThrottle throttle = throttle(
                Clock.fixed(TEST_START, ZoneOffset.UTC),
                5,
                25,
                100);

        for (int attemptIndex = 0; attemptIndex < 5; attemptIndex++) {
            assertTrue(throttle.reserveAuthenticationAttempt("piotr", "192.0.2.10"));
        }

        assertFalse(throttle.reserveAuthenticationAttempt("piotr", "192.0.2.10"));
        assertTrue(throttle.reserveAuthenticationAttempt("piotr", "192.0.2.11"));
        assertTrue(throttle.reserveAuthenticationAttempt("anna", "192.0.2.10"));
    }

    @Test
    void blocksUsernameSprayingAtTheSourceLimit() {
        SignInAttemptThrottle throttle = throttle(
                Clock.fixed(TEST_START, ZoneOffset.UTC),
                5,
                3,
                100);

        assertTrue(throttle.reserveAuthenticationAttempt("first", "192.0.2.10"));
        assertTrue(throttle.reserveAuthenticationAttempt("second", "192.0.2.10"));
        assertTrue(throttle.reserveAuthenticationAttempt("third", "192.0.2.10"));

        assertFalse(throttle.reserveAuthenticationAttempt("fourth", "192.0.2.10"));
        assertTrue(throttle.reserveAuthenticationAttempt("fourth", "192.0.2.11"));
    }

    @Test
    void successAndAbortedWorkReleaseTheirReservedAttempt() {
        SignInAttemptThrottle throttle = throttle(
                Clock.fixed(TEST_START, ZoneOffset.UTC),
                2,
                2,
                100);

        assertTrue(throttle.reserveAuthenticationAttempt("piotr", "192.0.2.10"));
        throttle.withdrawAuthenticationAttempt("piotr", "192.0.2.10");
        assertTrue(throttle.reserveAuthenticationAttempt("piotr", "192.0.2.10"));
        throttle.releaseSuccessfulAttempt("piotr", "192.0.2.10");

        assertTrue(throttle.reserveAuthenticationAttempt("piotr", "192.0.2.10"));
        assertTrue(throttle.reserveAuthenticationAttempt("piotr", "192.0.2.10"));
        assertFalse(throttle.reserveAuthenticationAttempt("piotr", "192.0.2.10"));
    }

    @Test
    void expiredWindowsStartFresh() {
        MutableClock clock = new MutableClock(TEST_START);
        SignInAttemptThrottle throttle = throttle(clock, 1, 1, 100);

        assertTrue(throttle.reserveAuthenticationAttempt("piotr", "192.0.2.10"));
        assertFalse(throttle.reserveAuthenticationAttempt("piotr", "192.0.2.10"));

        clock.advance(Duration.ofMinutes(15));

        assertTrue(throttle.reserveAuthenticationAttempt("piotr", "192.0.2.10"));
    }

    @Test
    void trackingStaysBoundedUnderRotatingInputs() {
        SignInAttemptThrottle throttle = throttle(
                Clock.fixed(TEST_START, ZoneOffset.UTC),
                5,
                25,
                3);

        for (int sourceIndex = 0; sourceIndex < 100; sourceIndex++) {
            assertTrue(throttle.reserveAuthenticationAttempt(
                    "user-" + sourceIndex,
                    "203.0.113." + sourceIndex));
        }

        assertEquals(3, throttle.trackedUsernameAndSourceCount());
        assertEquals(3, throttle.trackedSourceCount());
    }

    @Test
    void concurrentAttemptsCannotExceedThePairLimit() throws Exception {
        int attemptLimit = 5;
        int concurrentAttemptCount = 64;
        SignInAttemptThrottle throttle = throttle(
                Clock.fixed(TEST_START, ZoneOffset.UTC),
                attemptLimit,
                100,
                100);
        CountDownLatch ready = new CountDownLatch(concurrentAttemptCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int attemptIndex = 0; attemptIndex < concurrentAttemptCount; attemptIndex++) {
                results.add(executorService.submit(() -> {
                    ready.countDown();
                    start.await();
                    return throttle.reserveAuthenticationAttempt("piotr", "192.0.2.10");
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            int acceptedAttemptCount = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    acceptedAttemptCount++;
                }
            }
            assertEquals(attemptLimit, acceptedAttemptCount);
        } finally {
            start.countDown();
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> throttle(Clock.systemUTC(), 0, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> throttle(Clock.systemUTC(), 1, 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> throttle(Clock.systemUTC(), 1, 1, 0));
    }

    private static SignInAttemptThrottle throttle(
            Clock clock,
            int pairLimit,
            int sourceLimit,
            int trackedKeyLimit) {
        return new SignInAttemptThrottle(
                clock,
                pairLimit,
                sourceLimit,
                Duration.ofMinutes(15),
                trackedKeyLimit);
    }

    private static final class MutableClock extends Clock {
        private Instant currentInstant;

        private MutableClock(Instant currentInstant) {
            this.currentInstant = currentInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }

        private void advance(Duration duration) {
            currentInstant = currentInstant.plus(duration);
        }
    }
}
