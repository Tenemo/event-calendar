package app.membership;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CalendarRolePolicy {
    public boolean canView(CalendarRole role) {
        return role == CalendarRole.VIEWER || role == CalendarRole.EDITOR || role == CalendarRole.ADMIN;
    }

    public boolean canEdit(CalendarRole role) {
        return role == CalendarRole.EDITOR || role == CalendarRole.ADMIN;
    }

    public boolean canAdminister(CalendarRole role) {
        return role == CalendarRole.ADMIN;
    }

    public CalendarRole strongerRole(CalendarRole firstRole, CalendarRole secondRole) {
        if (rank(firstRole) >= rank(secondRole)) {
            return firstRole;
        }
        return secondRole;
    }

    private int rank(CalendarRole role) {
        return switch (role) {
            case VIEWER -> 1;
            case EDITOR -> 2;
            case ADMIN -> 3;
        };
    }
}
