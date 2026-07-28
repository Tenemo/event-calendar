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
                () -> assertTrue(CalendarRole.ADMIN.canAdminister()),
                () -> assertEquals("Editor", CalendarRole.EDITOR.getDisplayName()),
                () -> assertEquals("Admin", CalendarRole.ADMIN.getDisplayName()));
    }
}
