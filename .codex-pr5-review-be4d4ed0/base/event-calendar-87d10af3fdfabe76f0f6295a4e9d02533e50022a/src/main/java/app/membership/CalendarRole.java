package app.membership;

public enum CalendarRole {
    VIEWER,
    EDITOR,
    ADMIN;

    public boolean canView() {
        return true;
    }

    public boolean canEdit() {
        return this == EDITOR || this == ADMIN;
    }

    public boolean canAdminister() {
        return this == ADMIN;
    }

    public static CalendarRole strongerRole(CalendarRole firstRole, CalendarRole secondRole) {
        return rank(firstRole) >= rank(secondRole) ? firstRole : secondRole;
    }

    private static int rank(CalendarRole role) {
        return switch (role) {
            case VIEWER -> 1;
            case EDITOR -> 2;
            case ADMIN -> 3;
        };
    }
}
