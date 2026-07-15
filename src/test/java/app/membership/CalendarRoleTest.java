package app.membership;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CalendarRoleTest {
    @Test
    void onlyAdminsCanAdministerCalendars() {
        assertAll(
                () -> assertFalse(CalendarRole.EDITOR.canAdminister()),
                () -> assertTrue(CalendarRole.ADMIN.canAdminister()));
    }

    @Test
    void keepsTheStrongerRoleWhenAnInviteWouldOtherwiseDowngradeAccess() {
        assertAll(
                () -> assertEquals(CalendarRole.EDITOR, CalendarRole.strongerRole(CalendarRole.EDITOR, CalendarRole.EDITOR)),
                () -> assertEquals(CalendarRole.ADMIN, CalendarRole.strongerRole(CalendarRole.ADMIN, CalendarRole.EDITOR)),
                () -> assertEquals(CalendarRole.ADMIN, CalendarRole.strongerRole(CalendarRole.EDITOR, CalendarRole.ADMIN)));
    }
}
