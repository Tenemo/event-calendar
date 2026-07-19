package db.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class V12__validate_calendar_time_zonesTest {
    @Test
    void acceptsSupportedIdentifiersButRejectsOffsetsWhitespaceAndUnknownStoredValues() {
        assertEquals(
                Set.of("+01:00", "Europe/Warsaw ", "Mars/Olympus", "UTC+01:00", "Z", "null"),
                V12__validate_calendar_time_zones.unsupportedTimeZones(Arrays.asList(
                        "Europe/Warsaw",
                        "America/New_York",
                        "Pacific/Apia",
                        "UTC",
                        "GMT",
                        "Etc/UTC",
                        "+01:00",
                        "UTC+01:00",
                        "Z",
                        "Europe/Warsaw ",
                        "Mars/Olympus",
                        null)));
    }
}
