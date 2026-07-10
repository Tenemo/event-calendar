package app.membership;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CalendarRolePolicyTest {
    private final CalendarRolePolicy calendarRolePolicy = new CalendarRolePolicy();

    @Test
    void mapsCalendarScopedRolesToTheirAllowedActions() {
        assertAll(
                () -> assertTrue(calendarRolePolicy.canView(CalendarRole.VIEWER)),
                () -> assertFalse(calendarRolePolicy.canEdit(CalendarRole.VIEWER)),
                () -> assertFalse(calendarRolePolicy.canAdminister(CalendarRole.VIEWER)),
                () -> assertTrue(calendarRolePolicy.canView(CalendarRole.EDITOR)),
                () -> assertTrue(calendarRolePolicy.canEdit(CalendarRole.EDITOR)),
                () -> assertFalse(calendarRolePolicy.canAdminister(CalendarRole.EDITOR)),
                () -> assertTrue(calendarRolePolicy.canView(CalendarRole.ADMIN)),
                () -> assertTrue(calendarRolePolicy.canEdit(CalendarRole.ADMIN)),
                () -> assertTrue(calendarRolePolicy.canAdminister(CalendarRole.ADMIN)));
    }

    @Test
    void keepsTheStrongerRoleWhenAnInviteWouldOtherwiseDowngradeAccess() {
        assertAll(
                () -> assertEquals(CalendarRole.EDITOR, calendarRolePolicy.strongerRole(CalendarRole.VIEWER, CalendarRole.EDITOR)),
                () -> assertEquals(CalendarRole.ADMIN, calendarRolePolicy.strongerRole(CalendarRole.ADMIN, CalendarRole.VIEWER)),
                () -> assertEquals(CalendarRole.ADMIN, calendarRolePolicy.strongerRole(CalendarRole.EDITOR, CalendarRole.ADMIN)));
    }
}
