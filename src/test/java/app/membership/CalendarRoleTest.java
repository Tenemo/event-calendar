package app.membership;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CalendarRoleTest {
    @Test
    void mapsCalendarScopedRolesToTheirAllowedActions() {
        assertAll(
                () -> assertTrue(CalendarRole.VIEWER.canView()),
                () -> assertFalse(CalendarRole.VIEWER.canEdit()),
                () -> assertFalse(CalendarRole.VIEWER.canAdminister()),
                () -> assertTrue(CalendarRole.EDITOR.canView()),
                () -> assertTrue(CalendarRole.EDITOR.canEdit()),
                () -> assertFalse(CalendarRole.EDITOR.canAdminister()),
                () -> assertTrue(CalendarRole.ADMIN.canView()),
                () -> assertTrue(CalendarRole.ADMIN.canEdit()),
                () -> assertTrue(CalendarRole.ADMIN.canAdminister()));
    }

    @Test
    void keepsTheStrongerRoleWhenAnInviteWouldOtherwiseDowngradeAccess() {
        assertAll(
                () -> assertEquals(CalendarRole.EDITOR, CalendarRole.strongerRole(CalendarRole.VIEWER, CalendarRole.EDITOR)),
                () -> assertEquals(CalendarRole.ADMIN, CalendarRole.strongerRole(CalendarRole.ADMIN, CalendarRole.VIEWER)),
                () -> assertEquals(CalendarRole.ADMIN, CalendarRole.strongerRole(CalendarRole.EDITOR, CalendarRole.ADMIN)));
    }
}
