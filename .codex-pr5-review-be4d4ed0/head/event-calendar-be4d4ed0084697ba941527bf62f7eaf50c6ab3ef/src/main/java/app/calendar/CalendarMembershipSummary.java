package app.calendar;

import app.membership.CalendarRole;

public class CalendarMembershipSummary {
    private final Long calendarId;
    private final String calendarName;
    private final String calendarLinkToken;
    private final CalendarRole role;
    private final boolean publicAccessEnabled;

    public CalendarMembershipSummary(
            Long calendarId,
            String calendarName,
            String calendarLinkToken,
            CalendarRole role,
            boolean publicAccessEnabled) {
        this.calendarId = calendarId;
        this.calendarName = calendarName;
        this.calendarLinkToken = calendarLinkToken;
        this.role = role;
        this.publicAccessEnabled = publicAccessEnabled;
    }

    public Long getCalendarId() {
        return calendarId;
    }

    public String getCalendarName() {
        return calendarName;
    }

    public String getCalendarLinkToken() {
        return calendarLinkToken;
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
