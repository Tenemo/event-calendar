package app.event;

import java.io.Serializable;

/**
 * Constant-size identity for the event ordering visible to one calendar page.
 *
 * <p>Application inserts increase the count and maximum identity, deletes decrease the count
 * unless paired with an insert whose newer identity changes the maximum, and updates increment an
 * event's optimistic-lock version. Time-zone rebasing increments every affected all-day event
 * version as well. A change committed after a page's final revision read belongs to the next
 * logical snapshot and is detected before the next page is loaded.
 */
public record CalendarEventRevision(
        long numberOfEvents,
        long maximumEventId,
        long accumulatedEventVersions) implements Serializable {
    public CalendarEventRevision {
        if (numberOfEvents < 0
                || maximumEventId < 0
                || accumulatedEventVersions < 0) {
            throw new IllegalArgumentException("Event revision values cannot be negative.");
        }
        if ((numberOfEvents == 0) != (maximumEventId == 0)) {
            throw new IllegalArgumentException(
                    "An empty event revision must have a zero maximum event identifier.");
        }
    }
}
