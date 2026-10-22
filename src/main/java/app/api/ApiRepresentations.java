package app.api;

import app.calendar.Calendar;
import app.calendar.CalendarMembershipSummary;
import app.calendar.CalendarTimeService;
import app.config.ApplicationUrlService;
import app.event.CalendarEvent;
import app.invitation.Invitation;
import app.invitation.InvitationSummary;
import app.membership.CalendarMembership;
import app.membership.CalendarRole;
import app.user.ApplicationUser;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.time.LocalDate;
import java.time.LocalDateTime;

final class ApiRepresentations {
    private ApiRepresentations() {
    }

    static JsonObject user(ApplicationUser user) {
        return Json.createObjectBuilder()
                .add("id", user.getId())
                .add("username", user.getUsername())
                .add("displayName", user.getDisplayName())
                .build();
    }

    static JsonObject calendarSummary(
            CalendarMembershipSummary calendar,
            ApplicationUrlService applicationUrlService) {
        return Json.createObjectBuilder()
                .add("id", calendar.getCalendarId())
                .add("name", calendar.getCalendarName())
                .add("role", calendar.getRole().name())
                .add("publicAccessEnabled", calendar.isPublicAccessEnabled())
                .add("publicUrl", publicCalendarUrl(calendar.getCalendarLinkToken(), applicationUrlService))
                .build();
    }

    static JsonObject calendar(
            Calendar calendar,
            CalendarRole role,
            ApplicationUrlService applicationUrlService) {
        JsonObjectBuilder body = Json.createObjectBuilder()
                .add("id", calendar.getId())
                .add("name", calendar.getName())
                .add("timeZone", calendar.getTimeZone())
                .add("publicAccessEnabled", calendar.isPublicAccessEnabled())
                .add("publicUrl", publicCalendarUrl(calendar.getCalendarLinkToken(), applicationUrlService))
                .add("role", role.name())
                .add("version", calendar.getVersion());
        addNullable(body, "description", calendar.getDescription());
        return body.build();
    }

    static JsonObject event(
            CalendarEvent event,
            Calendar calendar,
            CalendarTimeService calendarTimeService) {
        JsonObjectBuilder body = Json.createObjectBuilder()
                .add("id", event.getId())
                .add("title", event.getTitle())
                .add("time", eventTime(event, calendar.getTimeZone(), calendarTimeService))
                .add("version", event.getVersion())
                .add("calendarVersion", calendar.getVersion());
        addNullable(body, "description", event.getDescription());
        addNullable(body, "location", event.getLocation());
        return body.build();
    }

    static JsonObject membership(CalendarMembership membership) {
        ApplicationUser user = membership.getUser();
        return Json.createObjectBuilder()
                .add("user", ApiRepresentations.user(user))
                .add("role", membership.getRole().name())
                .build();
    }

    static JsonObject invitation(
            Invitation invitation,
            ApplicationUrlService applicationUrlService) {
        JsonObjectBuilder body = Json.createObjectBuilder()
                .add("id", invitation.getId())
                .add("kind", invitation.getCalendar() == null ? "registration" : "calendar-editor")
                .add("url", invitationUrl(invitation.getInvitationToken(), applicationUrlService))
                .add("createdAt", invitation.getCreatedAt().toString())
                .add("expiresAt", invitation.getExpiresAt().toString());
        if (invitation.getCalendar() == null) {
            body.addNull("calendarId");
        } else {
            body.add("calendarId", invitation.getCalendar().getId());
        }
        return body.build();
    }

    static JsonObject invitation(
            InvitationSummary invitation,
            ApplicationUrlService applicationUrlService) {
        JsonObjectBuilder body = Json.createObjectBuilder()
                .add("id", invitation.id())
                .add("kind", invitation.calendarId() == null ? "registration" : "calendar-editor")
                .add("url", invitationUrl(invitation.invitationToken(), applicationUrlService))
                .add("createdAt", invitation.createdAt().toString())
                .add("expiresAt", invitation.expiresAt().toString());
        if (invitation.calendarId() == null) {
            body.addNull("calendarId");
            body.addNull("calendarName");
        } else {
            body.add("calendarId", invitation.calendarId());
            body.add("calendarName", invitation.calendarName());
        }
        return body.build();
    }

    private static JsonObject eventTime(
            CalendarEvent event,
            String timeZone,
            CalendarTimeService calendarTimeService) {
        LocalDateTime calendarStartTime = calendarTimeService.toCalendarTime(event.getStartTime(), timeZone);
        if (event.isAllDay()) {
            LocalDate lastDay = calendarTimeService.toCalendarDateImmediatelyBefore(event.getEndTime(), timeZone);
            return Json.createObjectBuilder()
                    .add("kind", "all-day")
                    .add("firstDay", calendarStartTime.toLocalDate().toString())
                    .add("lastDay", lastDay.toString())
                    .build();
        }
        return Json.createObjectBuilder()
                .add("kind", "timed")
                .add("start", calendarStartTime.toString())
                .add("end", calendarTimeService.toCalendarTime(event.getEndTime(), timeZone).toString())
                .build();
    }

    private static String publicCalendarUrl(
            String calendarLinkToken,
            ApplicationUrlService applicationUrlService) {
        return applicationUrlService.linkTo("/" + calendarLinkToken);
    }

    private static String invitationUrl(
            String invitationToken,
            ApplicationUrlService applicationUrlService) {
        return applicationUrlService.linkTo("/register?token=" + invitationToken);
    }

    private static void addNullable(JsonObjectBuilder body, String propertyName, String value) {
        if (value == null) {
            body.addNull(propertyName);
        } else {
            body.add(propertyName, value);
        }
    }
}
