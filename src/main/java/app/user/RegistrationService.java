package app.user;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.invitation.InvitationService;
import app.util.ValidationException;
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
    private InvitationService invitationService;

    public ApplicationUser register(
            String invitationToken,
            String username,
            String displayName,
            String password,
            String passwordConfirmation,
            String initialCalendarName) {
        if (password == null || !password.equals(passwordConfirmation)) {
            throw new ValidationException("Password confirmation does not match.");
        }
        String normalizedInitialCalendarName =
                calendarService.normalizeAndValidateCalendarName(initialCalendarName);
        RegistrationAdmission admission = invitationService.claimRegistrationAdmission(invitationToken);
        ApplicationUser user = userService.createUser(username, displayName, password);
        Calendar calendar = calendarService.createCalendar(user, normalizedInitialCalendarName);
        invitationService.acceptAdmission(admission, user);
        auditService.record(user, calendar, "app_user", user.getId(), "registered", "User registered.");
        return user;
    }
}
