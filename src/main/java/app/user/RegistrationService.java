package app.user;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.invitation.AppInvitationService;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

@Stateless
public class RegistrationService {
    @Inject
    private UserService userService;

    @Inject
    private CalendarService calendarService;

    @Inject
    private AuditService auditService;

    @Inject
    private AppInvitationService appInvitationService;

    public RegistrationResult register(
            String inviteToken,
            String username,
            String displayName,
            String password,
            String initialCalendarName) {
        RegistrationAdmission admission = appInvitationService.requireAdmission(inviteToken);
        AppUser user = userService.createUser(username, displayName, password);
        Calendar calendar = calendarService.createCalendar(user, initialCalendarName);
        appInvitationService.acceptAdmission(admission, user);
        auditService.record(user, calendar, "app_user", user.getId(), "registered", "User registered.");
        return new RegistrationResult(user, calendar);
    }
}
