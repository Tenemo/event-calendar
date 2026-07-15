package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class LoginAttemptThrottleTest {
    private static final Instant TEST_START = Instant.parse("2026-07-15T10:00:00Z");
    private static final String TEST_SOURCE = "192.0.2.10";

    @Test
    void usernameAndSourceBlockExpiresAtTheConfiguredTimeWithoutSleeping() {
        MutableClock clock = new MutableClock(TEST_START);
        LoginAttemptThrottle throttle = throttle(clock, 2, 20, 10, 10);

        throttle.recordFailedAuthentication("piotr", TEST_SOURCE);
        throttle.recordFailedAuthentication("piotr", TEST_SOURCE);

        assertFalse(throttle.isAuthenticationAllowed("piotr", TEST_SOURCE));
        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertFalse(throttle.isAuthenticationAllowed("piotr", TEST_SOURCE));
        clock.advance(Duration.ofSeconds(1));

        assertAll(
                () -> assertTrue(throttle.isAuthenticationAllowed("piotr", TEST_SOURCE)),
                () -> assertEquals(0, throttle.trackedUsernameAndSourceCount()));
    }

    @Test
    void failuresOutsideTheWindowStartANewWindow() {
        MutableClock clock = new MutableClock(TEST_START);
        LoginAttemptThrottle throttle = throttle(clock, 2, 20, 10, 10);

        throttle.recordFailedAuthentication("piotr", TEST_SOURCE);
        clock.advance(Duration.ofMinutes(10));
        throttle.recordFailedAuthentication("piotr", TEST_SOURCE);

        assertTrue(throttle.isAuthenticationAllowed("piotr", TEST_SOURCE));
    }

    @Test
    void hostileSourceDoesNotBlockTheSameUsernameForAnotherSource() {
        LoginAttemptThrottle throttle = throttle(Clock.fixed(TEST_START, ZoneOffset.UTC), 2, 20, 10, 10);

        throttle.recordFailedAuthentication("piotr", "198.51.100.10");
        throttle.recordFailedAuthentication("piotr", "198.51.100.10");

        assertAll(
                () -> assertFalse(throttle.isAuthenticationAllowed("piotr", "198.51.100.10")),
                () -> assertTrue(throttle.isAuthenticationAllowed("piotr", "203.0.113.20")));
    }

    @Test
    void sourceFailureLimitStopsUsernameSpraying() {
        LoginAttemptThrottle throttle = throttle(Clock.fixed(TEST_START, ZoneOffset.UTC), 5, 3, 20, 10);

        throttle.recordFailedAuthentication("person-one", TEST_SOURCE);
        throttle.recordFailedAuthentication("person-two", TEST_SOURCE);
        throttle.recordFailedAuthentication("person-three", TEST_SOURCE);

        assertAll(
                () -> assertFalse(throttle.isAuthenticationAllowed("untried-person", TEST_SOURCE)),
                () -> assertTrue(throttle.isAuthenticationAllowed("untried-person", "203.0.113.20")));
    }

    @Test
    void successfulUsernameDoesNotEraseCrossUsernameSourceFailures() {
        LoginAttemptThrottle throttle = throttle(Clock.fixed(TEST_START, ZoneOffset.UTC), 5, 3, 20, 10);

        throttle.recordFailedAuthentication("person-one", TEST_SOURCE);
        throttle.recordFailedAuthentication("person-two", TEST_SOURCE);
        throttle.clearUsernameAndSourceFailures("person-one", TEST_SOURCE);
        throttle.recordFailedAuthentication("person-three", TEST_SOURCE);

        assertAll(
                () -> assertTrue(throttle.isAuthenticationAllowed("person-one", "203.0.113.20")),
                () -> assertFalse(throttle.isAuthenticationAllowed("untried-person", TEST_SOURCE)));
    }

    @Test
    void usernameAndSourceStateCannotExceedItsConfiguredLimit() {
        LoginAttemptThrottle throttle = throttle(Clock.fixed(TEST_START, ZoneOffset.UTC), 5, 200, 3, 10);

        for (int usernameIndex = 0; usernameIndex < 100; usernameIndex++) {
            throttle.recordFailedAuthentication("person-" + usernameIndex, TEST_SOURCE);
        }

        assertEquals(3, throttle.trackedUsernameAndSourceCount());
    }

    @Test
    void sourceStateCannotExceedItsConfiguredLimit() {
        LoginAttemptThrottle throttle = throttle(Clock.fixed(TEST_START, ZoneOffset.UTC), 5, 5, 200, 3);

        for (int sourceIndex = 0; sourceIndex < 100; sourceIndex++) {
            throttle.recordFailedAuthentication("person-" + sourceIndex, "192.0.2." + sourceIndex);
        }

        assertEquals(3, throttle.trackedSourceCount());
    }

    @Test
    void usernameAndSourceSprayingDoesNotEvictAnActiveBlock() {
        LoginAttemptThrottle throttle = throttle(Clock.fixed(TEST_START, ZoneOffset.UTC), 2, 1_000, 3, 10);

        throttle.recordFailedAuthentication("target", TEST_SOURCE);
        throttle.recordFailedAuthentication("target", TEST_SOURCE);
        for (int usernameIndex = 0; usernameIndex < 100; usernameIndex++) {
            throttle.recordFailedAuthentication("sprayed-person-" + usernameIndex, TEST_SOURCE);
        }

        assertAll(
                () -> assertFalse(throttle.isAuthenticationAllowed("target", TEST_SOURCE)),
                () -> assertEquals(3, throttle.trackedUsernameAndSourceCount()));
    }

    @Test
    void sourceSprayingDoesNotEvictAnActiveSourceBlock() {
        LoginAttemptThrottle throttle = throttle(Clock.fixed(TEST_START, ZoneOffset.UTC), 5, 2, 200, 3);
        String blockedSource = "198.51.100.10";

        throttle.recordFailedAuthentication("person-one", blockedSource);
        throttle.recordFailedAuthentication("person-two", blockedSource);
        for (int sourceIndex = 0; sourceIndex < 100; sourceIndex++) {
            throttle.recordFailedAuthentication("sprayed-person-" + sourceIndex, "203.0.113." + sourceIndex);
        }

        assertAll(
                () -> assertFalse(throttle.isAuthenticationAllowed("another-person", blockedSource)),
                () -> assertEquals(3, throttle.trackedSourceCount()));
    }

    @Test
    void oversizedIdentifiersUseBoundedTrackingKeys() {
        LoginAttemptThrottle throttle = throttle(Clock.fixed(TEST_START, ZoneOffset.UTC), 5, 20, 10, 10);

        throttle.recordFailedAuthentication("a".repeat(81), "x".repeat(129));
        throttle.recordFailedAuthentication("b".repeat(160), "y".repeat(256));

        assertAll(
                () -> assertEquals(1, throttle.trackedUsernameAndSourceCount()),
                () -> assertEquals(1, throttle.trackedSourceCount()));
    }

    private static LoginAttemptThrottle throttle(
            Clock clock,
            int maximumFailedAttemptsPerUsernameAndSource,
            int maximumFailedAttemptsPerSource,
            int maximumTrackedUsernameAndSourceCombinations,
            int maximumTrackedSources) {
        return new LoginAttemptThrottle(
                clock,
                maximumFailedAttemptsPerUsernameAndSource,
                maximumFailedAttemptsPerSource,
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                maximumTrackedUsernameAndSourceCombinations,
                maximumTrackedSources);
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
