package app.user;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
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

    public RegistrationResult register(String username, String displayName, String password, String initialCalendarName) {
        AppUser user = userService.createUser(username, displayName, password);
        Calendar calendar = calendarService.createCalendar(user, initialCalendarName);
        auditService.record(user, calendar, "app_user", user.getId(), "registered", "User registered.");
        return new RegistrationResult(user, calendar);
    }
}
