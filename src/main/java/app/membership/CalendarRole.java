package app.membership;

public enum CalendarRole {
    EDITOR,
    ADMIN;

    public boolean canAdminister() {
        return this == ADMIN;
    }

    public static CalendarRole strongerRole(CalendarRole firstRole, CalendarRole secondRole) {
        return firstRole == ADMIN || secondRole == ADMIN ? ADMIN : EDITOR;
    }
}
