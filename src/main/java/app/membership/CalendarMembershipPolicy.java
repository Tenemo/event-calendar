package app.membership;

import app.util.ValidationException;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CalendarMembershipPolicy {
    public void requireSafeRoleChange(CalendarRole currentRole, CalendarRole newRole, boolean anotherActiveAdminExists) {
        if (currentRole == CalendarRole.ADMIN && newRole != CalendarRole.ADMIN && !anotherActiveAdminExists) {
            throw new ValidationException("A calendar must keep at least one active admin.");
        }
    }

    public void requireSafeDisable(CalendarRole currentRole, boolean anotherActiveAdminExists) {
        if (currentRole == CalendarRole.ADMIN && !anotherActiveAdminExists) {
            throw new ValidationException("A calendar must keep at least one active admin.");
        }
    }
}
