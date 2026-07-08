package app.membership;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.util.ValidationException;
import org.junit.jupiter.api.Test;

final class CalendarMembershipPolicyTest {
    private final CalendarMembershipPolicy calendarMembershipPolicy = new CalendarMembershipPolicy();

    @Test
    void blocksDemotingOrDisablingTheLastActiveAdmin() {
        assertAll(
                () -> assertThrows(
                        ValidationException.class,
                        () -> calendarMembershipPolicy.requireSafeRoleChange(CalendarRole.ADMIN, CalendarRole.EDITOR, false)),
                () -> assertThrows(
                        ValidationException.class,
                        () -> calendarMembershipPolicy.requireSafeRoleChange(CalendarRole.ADMIN, CalendarRole.VIEWER, false)),
                () -> assertThrows(
                        ValidationException.class,
                        () -> calendarMembershipPolicy.requireSafeDisable(CalendarRole.ADMIN, false)));
    }

    @Test
    void allowsAdminChangesWhenAnotherActiveAdminRemains() {
        assertAll(
                () -> assertDoesNotThrow(
                        () -> calendarMembershipPolicy.requireSafeRoleChange(CalendarRole.ADMIN, CalendarRole.EDITOR, true)),
                () -> assertDoesNotThrow(() -> calendarMembershipPolicy.requireSafeDisable(CalendarRole.ADMIN, true)));
    }

    @Test
    void allowsNonAdminChangesWithoutRequiringAnotherAdminCount() {
        assertAll(
                () -> assertDoesNotThrow(
                        () -> calendarMembershipPolicy.requireSafeRoleChange(CalendarRole.EDITOR, CalendarRole.VIEWER, false)),
                () -> assertDoesNotThrow(() -> calendarMembershipPolicy.requireSafeDisable(CalendarRole.VIEWER, false)));
    }
}
