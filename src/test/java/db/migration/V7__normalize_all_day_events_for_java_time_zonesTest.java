package db.migration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

final class V7__normalize_all_day_events_for_java_time_zonesTest {
    @Test
    void normalizesJavaFixedOffsetTimeZonesWithoutPostgreSQLSignReversal() {
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
    void normalizesJavaLegacyAliasesThatPostgreSQLDoesNotRecognize() {
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

    @Test
    void clampsAnEndDateBeforeTheStartDateToOneInclusiveCivilDay() {
        V7__normalize_all_day_events_for_java_time_zones.NormalizedBoundary normalizedBoundary =
                V7__normalize_all_day_events_for_java_time_zones.normalizeBoundary(
                        OffsetDateTime.parse("2026-07-22T12:00:00+02:00"),
                        OffsetDateTime.parse("2026-07-20T12:00:00+02:00"),
                        "+02:00");

        assertAll(
                () -> assertEquals(
                        OffsetDateTime.parse("2026-07-22T00:00:00+02:00"),
                        normalizedBoundary.startTime()),
                () -> assertEquals(
                        OffsetDateTime.parse("2026-07-23T00:00:00+02:00"),
                        normalizedBoundary.endTime()));
    }

    @Test
    void rejectsAnExclusiveBoundaryWhoseCivilDateWasSkipped() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> V7__normalize_all_day_events_for_java_time_zones.normalizeBoundary(
                        OffsetDateTime.parse("2011-12-29T12:00:00-10:00"),
                        OffsetDateTime.parse("2011-12-29T13:00:00-10:00"),
                        "Pacific/Apia"));

        assertEquals(
                "An all-day event uses a civil date that does not exist in its calendar time zone.",
                exception.getMessage());
    }
}
