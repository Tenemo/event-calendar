package app.calendar;

import app.membership.CalendarRole;

public class CalendarMembershipSummary {
    private final Long calendarId;
    private final String calendarName;
    private final String publicToken;
    private final CalendarRole role;
    private final boolean publicAccessEnabled;

    public CalendarMembershipSummary(
            Long calendarId,
            String calendarName,
            String publicToken,
            CalendarRole role,
            boolean publicAccessEnabled) {
        this.calendarId = calendarId;
        this.calendarName = calendarName;
        this.publicToken = publicToken;
        this.role = role;
        this.publicAccessEnabled = publicAccessEnabled;
    }

    public Long getCalendarId() {
        return calendarId;
    }

    public String getCalendarName() {
        return calendarName;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public CalendarRole getRole() {
        return role;
    }

    public boolean isPublicAccessEnabled() {
        return publicAccessEnabled;
    }

    public boolean isAdmin() {
        return role == CalendarRole.ADMIN;
    }
}
