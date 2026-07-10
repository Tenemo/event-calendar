package app.membership;

import java.io.Serializable;
import java.util.Objects;

public class CalendarMemberId implements Serializable {
    private Long calendar;
    private Long user;

    public CalendarMemberId() {
    }

    public CalendarMemberId(Long calendar, Long user) {
        this.calendar = calendar;
        this.user = user;
    }

    public Long getCalendar() {
        return calendar;
    }

    public void setCalendar(Long calendar) {
        this.calendar = calendar;
    }

    public Long getUser() {
        return user;
    }

    public void setUser(Long user) {
        this.user = user;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CalendarMemberId that)) {
            return false;
        }
        return Objects.equals(calendar, that.calendar) && Objects.equals(user, that.user);
    }

    @Override
    public int hashCode() {
        return Objects.hash(calendar, user);
    }
}
