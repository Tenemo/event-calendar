package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class AuthenticationAuditServiceTest {
    @Test
    void recordsEveryAuthenticationAndSessionOutcomeWithoutCallerControlledData() {
        List<String> auditEvents = new ArrayList<>();
        AuthenticationAuditService auditService = new AuthenticationAuditService(
                Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
                auditEvents::add);

        auditService.recordSignInSucceeded();
        auditService.recordSignInFailed();
        auditService.recordSignInThrottled();
        auditService.recordSignOut();
        auditService.recordForcedSessionInvalidation();

        assertEquals(
                List.of(
                        "authentication_audit event=sign_in_succeeded outcome=recorded",
                        "authentication_audit event=sign_in_failed outcome=recorded",
                        "authentication_audit event=sign_in_throttled outcome=recorded",
                        "authentication_audit event=sign_out outcome=recorded",
                        "authentication_audit event=forced_session_invalidation outcome=recorded"),
                auditEvents);
    }

    @Test
    void hostileFailureVolumeProducesBoundedOutputAndFixedMemoryState() throws Exception {
        int hostileRequestCount = 1_000;
        List<String> auditEvents = Collections.synchronizedList(new ArrayList<>());
        AuthenticationAuditService auditService = new AuthenticationAuditService(
                Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
                auditEvents::add);
        CountDownLatch startRequests = new CountDownLatch(1);
        ExecutorService requestExecutor = Executors.newFixedThreadPool(32);
        List<Future<?>> results = new ArrayList<>();

        try {
            for (int requestIndex = 0; requestIndex < hostileRequestCount; requestIndex++) {
                results.add(requestExecutor.submit(() -> {
                    assertTrue(startRequests.await(5, TimeUnit.SECONDS));
                    auditService.recordSignInFailed();
                    return null;
                }));
            }
            startRequests.countDown();
            for (Future<?> result : results) {
                result.get(5, TimeUnit.SECONDS);
            }
        } finally {
            requestExecutor.shutdownNow();
        }

        assertAll(
                () -> assertEquals(
                        AuthenticationAuditService.MAXIMUM_DIRECT_EVENTS_PER_TYPE_AND_WINDOW + 1,
                        auditEvents.size()),
                () -> assertEquals(
                        1,
                        auditEvents.stream()
                                .filter(event -> event.contains("further_events_suppressed"))
                                .count()),
                () -> assertTrue(auditEvents.stream().allMatch(
                        event -> event.startsWith(
                                "authentication_audit event=sign_in_failed "))));
    }

    @Test
    void nextWindowSummarizesSuppressionThenAllowsDirectEventsAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-27T12:00:00Z"));
        List<String> auditEvents = new ArrayList<>();
        AuthenticationAuditService auditService = new AuthenticationAuditService(
                clock,
                auditEvents::add);
        int eventCount = AuthenticationAuditService.MAXIMUM_DIRECT_EVENTS_PER_TYPE_AND_WINDOW + 7;
        for (int eventIndex = 0; eventIndex < eventCount; eventIndex++) {
            auditService.recordSignInThrottled();
        }

        clock.advance(AuthenticationAuditService.AUDIT_RATE_LIMIT_WINDOW);
        auditService.recordSignInThrottled();

        assertAll(
                () -> assertTrue(auditEvents.get(auditEvents.size() - 2)
                        .endsWith("outcome=suppressed_count=7")),
                () -> assertTrue(auditEvents.getLast().endsWith("outcome=recorded")));
    }

    private static final class MutableClock extends Clock {
        private Instant currentInstant;

        private MutableClock(Instant currentInstant) {
            this.currentInstant = currentInstant;
        }

        private void advance(Duration duration) {
            currentInstant = currentInstant.plus(duration);
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
    }
}
