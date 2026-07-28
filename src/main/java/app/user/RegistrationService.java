package app.user;

import app.calendar.CalendarService;
import app.invitation.InvitationClaim;
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
        InvitationClaim invitationClaim = invitationService.claimInvitationForRegistration(invitationToken);
        ApplicationUser user = userService.createUser(username, displayName, password);
        calendarService.createCalendar(user, normalizedInitialCalendarName);
        invitationService.completeInvitationClaim(invitationClaim, user);
        return user;
    }
}
