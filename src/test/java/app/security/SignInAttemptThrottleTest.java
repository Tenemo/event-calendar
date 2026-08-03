package app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void unblockedAttemptsYieldSegmentCapacityToNewUsernames() {
        SignInAttemptThrottle throttle = new SignInAttemptThrottle(new AdjustableClock());

        for (int usernameNumber = 0;
                usernameNumber < SignInAttemptThrottle.MAXIMUM_TRACKED_KEYS_PER_SEGMENT * 3;
                usernameNumber++) {
            assertTrue(throttle.reserveAuthenticationAttempt(
                    "new-username-" + usernameNumber,
                    SOURCE));
        }
    }

    @Test
    void blockedAttemptsSurviveSegmentSaturationWithoutBlockingOtherSegments() {
        SignInAttemptThrottle throttle = new SignInAttemptThrottle(new AdjustableClock());
        reserveAllowedAttempts(throttle, USERNAME, SOURCE);
        int targetSegment = SignInAttemptThrottle.attemptSegmentIndex(SOURCE);
        List<String> sameSourceUsernames = usernamesSharingSourceSegment(
                SignInAttemptThrottle.MAXIMUM_TRACKED_KEYS_PER_SEGMENT);

        for (int usernameIndex = 0;
                usernameIndex < SignInAttemptThrottle.MAXIMUM_TRACKED_KEYS_PER_SEGMENT - 1;
                usernameIndex++) {
            reserveAllowedAttempts(throttle, sameSourceUsernames.get(usernameIndex), SOURCE);
        }

        assertFalse(throttle.reserveAuthenticationAttempt(
                sameSourceUsernames.get(SignInAttemptThrottle.MAXIMUM_TRACKED_KEYS_PER_SEGMENT - 1),
                SOURCE));
        assertFalse(throttle.reserveAuthenticationAttempt(USERNAME, SOURCE));
        assertTrue(throttle.reserveAuthenticationAttempt(
                USERNAME,
                sourceOutsideSegment(targetSegment)));
    }

    @Test
    void concurrentReservationsNeverExceedTheFailureLimit() throws Exception {
        SignInAttemptThrottle throttle = new SignInAttemptThrottle(new AdjustableClock());
        CountDownLatch startReservations = new CountDownLatch(1);
        List<Future<Boolean>> reservations = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            for (int attemptNumber = 0; attemptNumber < 24; attemptNumber++) {
                reservations.add(executor.submit(() -> {
                    startReservations.await();
                    return throttle.reserveAuthenticationAttempt(USERNAME, SOURCE);
                }));
            }
            startReservations.countDown();

            long allowedReservationCount = 0;
            for (Future<Boolean> reservation : reservations) {
                if (reservation.get(10, TimeUnit.SECONDS)) {
                    allowedReservationCount++;
                }
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(
                    SignInAttemptThrottle.MAXIMUM_FAILED_ATTEMPTS,
                    allowedReservationCount);
            assertFalse(throttle.reserveAuthenticationAttempt(USERNAME, SOURCE));
        }
    }

    private static List<String> usernamesSharingSourceSegment(int usernameCount) {
        List<String> usernames = new ArrayList<>(usernameCount);
        for (int usernameNumber = 0; usernameNumber < usernameCount; usernameNumber++) {
            usernames.add("same-source-" + usernameNumber);
        }
        return usernames;
    }

    private static String sourceOutsideSegment(int segment) {
        for (int candidateNumber = 0; ; candidateNumber++) {
            String candidateSource = "198.51.100." + candidateNumber;
            if (SignInAttemptThrottle.attemptSegmentIndex(candidateSource) != segment) {
                return candidateSource;
            }
        }
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
