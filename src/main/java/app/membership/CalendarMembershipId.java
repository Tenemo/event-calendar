package app.membership;

import java.io.Serializable;
import java.util.Objects;

public class CalendarMembershipId implements Serializable {
    private Long calendar;
    private Long user;

    public CalendarMembershipId() {
    }

    CalendarMembershipId(Long calendarIdentifier, Long userIdentifier) {
        this.calendar = calendarIdentifier;
        this.user = userIdentifier;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CalendarMembershipId that)) {
            return false;
        }
        return Objects.equals(calendar, that.calendar) && Objects.equals(user, that.user);
    }

    @Override
    public int hashCode() {
        return Objects.hash(calendar, user);
    }
}
