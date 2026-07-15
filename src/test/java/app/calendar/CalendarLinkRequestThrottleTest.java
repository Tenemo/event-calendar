package app.calendar;

import static org.junit.jupiter.api.Assertions.assertAll;
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
import org.junit.jupiter.api.Test;

final class CalendarLinkRequestThrottleTest {
    private static final Instant TEST_START = Instant.parse("2026-07-16T10:00:00Z");

    @Test
    void productionDefaultsEnforceTheDocumentedSourceAndConcurrencyLimits() {
        CalendarLinkRequestThrottle throttle = new CalendarLinkRequestThrottle();

        for (int requestNumber = 0;
                requestNumber < CalendarLinkRequestThrottle.MAXIMUM_REQUESTS_PER_SOURCE;
                requestNumber++) {
            try (CalendarLinkRequestThrottle.Admission admission = throttle.tryAcquire("192.0.2.10")) {
                assertTrue(admission.isAccepted());
            }
        }
        assertFalse(throttle.tryAcquire("192.0.2.10").isAccepted());

        List<CalendarLinkRequestThrottle.Admission> admissions = new ArrayList<>();
        try {
            for (int sourceNumber = 0;
                    sourceNumber < CalendarLinkRequestThrottle.MAXIMUM_CONCURRENT_REQUESTS;
                    sourceNumber++) {
                CalendarLinkRequestThrottle.Admission admission =
                        throttle.tryAcquire("198.51.100." + sourceNumber);
                admissions.add(admission);
                assertTrue(admission.isAccepted());
            }
            assertFalse(throttle.tryAcquire("203.0.113.20").isAccepted());
        } finally {
            admissions.forEach(CalendarLinkRequestThrottle.Admission::close);
        }
    }

    @Test
    void limitsEachSourceWithinAFixedWindowWithoutBlockingAnotherSource() {
        MutableClock clock = new MutableClock(TEST_START);
        CalendarLinkRequestThrottle throttle = throttle(clock, 2, 10, 2);

        try (CalendarLinkRequestThrottle.Admission first = throttle.tryAcquire("192.0.2.10");
                CalendarLinkRequestThrottle.Admission second = throttle.tryAcquire("192.0.2.10")) {
            assertTrue(first.isAccepted());
            assertTrue(second.isAccepted());
        }
        CalendarLinkRequestThrottle.Admission rejected = throttle.tryAcquire("192.0.2.10");
        CalendarLinkRequestThrottle.Admission differentSource = throttle.tryAcquire("198.51.100.20");

        assertAll(
                () -> assertFalse(rejected.isAccepted()),
                () -> assertEquals(60, rejected.getRetryAfterSeconds()),
                () -> assertTrue(differentSource.isAccepted()));
        differentSource.close();

        clock.advance(Duration.ofMinutes(1));
        try (CalendarLinkRequestThrottle.Admission afterWindow = throttle.tryAcquire("192.0.2.10")) {
            assertTrue(afterWindow.isAccepted());
        }
    }

    @Test
    void rejectsExcessConcurrentWorkAndReleasesCapacityExactlyOnce() {
        CalendarLinkRequestThrottle throttle = throttle(
                Clock.fixed(TEST_START, ZoneOffset.UTC), 100, 10, 2);

        CalendarLinkRequestThrottle.Admission first = throttle.tryAcquire("192.0.2.10");
        CalendarLinkRequestThrottle.Admission second = throttle.tryAcquire("192.0.2.11");
        CalendarLinkRequestThrottle.Admission rejected = throttle.tryAcquire("192.0.2.12");

        assertAll(
                () -> assertTrue(first.isAccepted()),
                () -> assertTrue(second.isAccepted()),
                () -> assertFalse(rejected.isAccepted()),
                () -> assertEquals(1, rejected.getRetryAfterSeconds()),
                () -> assertEquals(0, throttle.availableConcurrentRequestPermits()));

        first.close();
        first.close();
        assertEquals(1, throttle.availableConcurrentRequestPermits());
        try (CalendarLinkRequestThrottle.Admission replacement = throttle.tryAcquire("192.0.2.13")) {
            assertTrue(replacement.isAccepted());
        }
        second.close();
        assertEquals(2, throttle.availableConcurrentRequestPermits());
    }

    @Test
    void sourceTrackingRemainsBoundedUnderRotatingAndOversizedIdentifiers() {
        CalendarLinkRequestThrottle throttle = throttle(
                Clock.fixed(TEST_START, ZoneOffset.UTC), 100, 3, 1);

        for (int sourceIndex = 0; sourceIndex < 100; sourceIndex++) {
            try (CalendarLinkRequestThrottle.Admission ignored =
                    throttle.tryAcquire("203.0.113." + sourceIndex)) {
                assertTrue(ignored.isAccepted());
            }
        }
        try (CalendarLinkRequestThrottle.Admission firstOversized = throttle.tryAcquire("a".repeat(129))) {
            assertTrue(firstOversized.isAccepted());
        }
        try (CalendarLinkRequestThrottle.Admission secondOversized = throttle.tryAcquire("b".repeat(256))) {
            assertTrue(secondOversized.isAccepted());
        }

        assertEquals(3, throttle.trackedSourceCount());
    }

    private static CalendarLinkRequestThrottle throttle(
            Clock clock,
            int maximumRequestsPerSource,
            int maximumTrackedSources,
            int maximumConcurrentRequests) {
        return new CalendarLinkRequestThrottle(
                clock,
                maximumRequestsPerSource,
                Duration.ofMinutes(1),
                maximumTrackedSources,
                maximumConcurrentRequests);
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
