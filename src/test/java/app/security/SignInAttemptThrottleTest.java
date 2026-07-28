package app.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class SignInAttemptThrottleTest {
    private static final String USERNAME = "friend";
    private static final String SOURCE = "192.0.2.10";

    @Test
    void blocksAtTheLimitUntilTheFailureWindowExpires() {
        AdjustableClock clock = new AdjustableClock();
        SignInAttemptThrottle throttle = new SignInAttemptThrottle(clock);

        reserveAllowedAttempts(throttle, USERNAME, SOURCE);
        assertFalse(throttle.reserveAuthenticationAttempt(USERNAME, SOURCE));

        clock.advance(SignInAttemptThrottle.FAILURE_WINDOW.minusNanos(1));
        assertFalse(throttle.reserveAuthenticationAttempt(USERNAME, SOURCE));

        clock.advance(Duration.ofNanos(1));
        assertTrue(throttle.reserveAuthenticationAttempt(USERNAME, SOURCE));
    }

    @Test
    void tracksUsernamesAndRequestSourcesIndependently() {
        SignInAttemptThrottle throttle = new SignInAttemptThrottle(new AdjustableClock());

        reserveAllowedAttempts(throttle, USERNAME, SOURCE);

        assertFalse(throttle.reserveAuthenticationAttempt(USERNAME, SOURCE));
        assertTrue(throttle.reserveAuthenticationAttempt(USERNAME, "198.51.100.20"));
        assertTrue(throttle.reserveAuthenticationAttempt("another-friend", SOURCE));
    }

    @Test
    void successfulAuthenticationClearsTheEntireFailureWindow() {
        SignInAttemptThrottle throttle = new SignInAttemptThrottle(new AdjustableClock());

        reserveAllowedAttempts(throttle, USERNAME, SOURCE);
        throttle.releaseSuccessfulAttempt(USERNAME, SOURCE);

        reserveAllowedAttempts(throttle, USERNAME, SOURCE);
        assertFalse(throttle.reserveAuthenticationAttempt(USERNAME, SOURCE));
    }

    @Test
    void withdrawnAuthenticationOnlyRemovesItsReservedAttempt() {
        SignInAttemptThrottle throttle = new SignInAttemptThrottle(new AdjustableClock());

        reserveAllowedAttempts(throttle, USERNAME, SOURCE);
        throttle.withdrawAuthenticationAttempt(USERNAME, SOURCE);

        assertTrue(throttle.reserveAuthenticationAttempt(USERNAME, SOURCE));
        assertFalse(throttle.reserveAuthenticationAttempt(USERNAME, SOURCE));

        String separateUsername = "single-withdrawal";
        assertTrue(throttle.reserveAuthenticationAttempt(separateUsername, SOURCE));
        throttle.withdrawAuthenticationAttempt(separateUsername, SOURCE);
        reserveAllowedAttempts(throttle, separateUsername, SOURCE);
        assertFalse(throttle.reserveAuthenticationAttempt(separateUsername, SOURCE));
    }

    @Test
    void evictsTheLeastRecentlyUsedKeyAtTheTrackingLimit() {
        SignInAttemptThrottle throttle = new SignInAttemptThrottle(new AdjustableClock());

        for (int keyNumber = 0;
                keyNumber < SignInAttemptThrottle.MAXIMUM_TRACKED_KEYS;
                keyNumber++) {
            assertTrue(throttle.reserveAuthenticationAttempt("friend-" + keyNumber, SOURCE));
        }

        assertTrue(throttle.reserveAuthenticationAttempt("friend-0", SOURCE));
        assertTrue(throttle.reserveAuthenticationAttempt(
                "friend-" + SignInAttemptThrottle.MAXIMUM_TRACKED_KEYS,
                SOURCE));

        reserveAllowedAttempts(throttle, "friend-1", SOURCE);
        assertFalse(throttle.reserveAuthenticationAttempt("friend-1", SOURCE));
    }

    private static void reserveAllowedAttempts(
            SignInAttemptThrottle throttle,
            String username,
            String source) {
        for (int attemptNumber = 0;
                attemptNumber < SignInAttemptThrottle.MAXIMUM_FAILED_ATTEMPTS;
                attemptNumber++) {
            assertTrue(throttle.reserveAuthenticationAttempt(username, source));
        }
    }

    private static final class AdjustableClock extends Clock {
        private Instant currentTime = Instant.parse("2026-07-28T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(currentTime, zone);
        }

        @Override
        public Instant instant() {
            return currentTime;
        }

        void advance(Duration duration) {
            currentTime = currentTime.plus(duration);
        }
    }
}
