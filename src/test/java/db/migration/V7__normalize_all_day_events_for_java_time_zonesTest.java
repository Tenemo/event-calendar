package db.migration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

final class V7__normalize_all_day_events_for_java_time_zonesTest {
    @Test
    void normalizesJavaFixedOffsetTimeZonesWithoutPostgresqlSignReversal() {
        V7__normalize_all_day_events_for_java_time_zones.NormalizedBoundary normalizedBoundary =
                V7__normalize_all_day_events_for_java_time_zones.normalizeBoundary(
                        OffsetDateTime.parse("2026-07-22T12:00:00+02:00"),
                        OffsetDateTime.parse("2026-07-24T12:00:00+02:00"),
                        "+02:00");

        assertAll(
                () -> assertEquals(
                        OffsetDateTime.parse("2026-07-22T00:00:00+02:00"),
                        normalizedBoundary.startTime()),
                () -> assertEquals(
                        OffsetDateTime.parse("2026-07-25T00:00:00+02:00"),
                        normalizedBoundary.endTime()));
    }

    @Test
    void normalizesJavaLegacyAliasesThatPostgresqlDoesNotRecognize() {
        V7__normalize_all_day_events_for_java_time_zones.NormalizedBoundary normalizedBoundary =
                V7__normalize_all_day_events_for_java_time_zones.normalizeBoundary(
                        OffsetDateTime.parse("2026-07-22T12:00:00-04:00"),
                        OffsetDateTime.parse("2026-07-22T13:00:00-04:00"),
                        "US/Eastern");

        assertAll(
                () -> assertEquals(
                        OffsetDateTime.parse("2026-07-22T00:00:00-04:00"),
                        normalizedBoundary.startTime()),
                () -> assertEquals(
                        OffsetDateTime.parse("2026-07-23T00:00:00-04:00"),
                        normalizedBoundary.endTime()));
    }
}
