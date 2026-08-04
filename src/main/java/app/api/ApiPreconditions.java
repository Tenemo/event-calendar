package app.api;

import jakarta.ws.rs.core.EntityTag;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ApiPreconditions {
    private static final Pattern CALENDAR_ENTITY_TAG = Pattern.compile("\"([0-9]+)\"");
    private static final Pattern EVENT_ENTITY_TAG = Pattern.compile(
            "\"event-([0-9]+)-calendar-([0-9]+)\"");

    private ApiPreconditions() {
    }

    static int requireCalendarVersion(String ifMatch) {
        return requireVersion(ifMatch, CALENDAR_ENTITY_TAG, 1, "calendar");
    }

    static EventVersions requireEventVersions(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw missingPrecondition();
        }
        Matcher matcher = EVENT_ENTITY_TAG.matcher(ifMatch.trim());
        if (!matcher.matches()) {
            throw malformedPrecondition("event");
        }
        try {
            return new EventVersions(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)));
        } catch (NumberFormatException exception) {
            throw malformedPrecondition("event");
        }
    }

    static EntityTag calendarEntityTag(int calendarVersion) {
        return new EntityTag(Integer.toString(calendarVersion));
    }

    static EntityTag eventEntityTag(int eventVersion, int calendarVersion) {
        return new EntityTag("event-" + eventVersion + "-calendar-" + calendarVersion);
    }

    private static int requireVersion(
            String ifMatch,
            Pattern pattern,
            int versionGroup,
            String resourceName) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw missingPrecondition();
        }
        Matcher matcher = pattern.matcher(ifMatch.trim());
        if (!matcher.matches()) {
            throw malformedPrecondition(resourceName);
        }
        try {
            return Integer.parseInt(matcher.group(versionGroup));
        } catch (NumberFormatException exception) {
            throw malformedPrecondition(resourceName);
        }
    }

    private static ApiRequestException missingPrecondition() {
        return new ApiRequestException(
                428,
                "Precondition required",
                "An If-Match header from the latest representation is required.");
    }

    private static ApiRequestException malformedPrecondition(String resourceName) {
        return new ApiRequestException(
                400,
                "Bad request",
                "If-Match must contain the strong entity tag from the latest " + resourceName + " representation.");
    }

    record EventVersions(int eventVersion, int calendarVersion) {
    }
}
