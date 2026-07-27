package app.user;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.invitation.InvitationService;
import app.util.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class RegistrationServiceTest {
    @Test
    void passwordConfirmationMismatchPreventsEveryRegistrationSideEffect() {
        AtomicInteger sideEffectCount = new AtomicInteger();
        RegistrationService registrationService = new RegistrationService();
        setField(registrationService, "invitationService", new InvitationService() {
            @Override
            public RegistrationAdmission claimRegistrationAdmission(String invitationToken) {
                sideEffectCount.incrementAndGet();
                throw new AssertionError("The invitation must not be claimed after a confirmation mismatch.");
            }
        });
        setField(registrationService, "userService", new UserService() {
            @Override
            public ApplicationUser createUser(
                    String username,
                    String displayName,
                    String password) {
                sideEffectCount.incrementAndGet();
                throw new AssertionError("The user must not be created after a confirmation mismatch.");
            }
        });
        setField(registrationService, "calendarService", new CalendarService() {
            @Override
            public Calendar createCalendar(ApplicationUser creator, String name) {
                sideEffectCount.incrementAndGet();
                throw new AssertionError("The calendar must not be created after a confirmation mismatch.");
            }
        });
        setField(registrationService, "auditService", new AuditService() {
            @Override
            public void record(
                    ApplicationUser actingUser,
                    Calendar calendar,
                    String entityType,
                    Long entityId,
                    String action,
                    String details) {
                sideEffectCount.incrementAndGet();
                throw new AssertionError("Registration must not be audited after a confirmation mismatch.");
            }
        });

        ValidationException mismatchException = assertThrows(
                ValidationException.class,
                () -> registrationService.register(
                        "unused-token",
                        "new-user",
                        "New user",
                        "a valid lowercase passphrase",
                        "a different lowercase passphrase",
                        "New calendar"));
        ValidationException missingConfirmationException = assertThrows(
                ValidationException.class,
                () -> registrationService.register(
                        "unused-token",
                        "new-user",
                        "New user",
                        "a valid lowercase passphrase",
                        null,
                        "New calendar"));

        assertAll(
                () -> assertEquals(
                        "Password confirmation does not match.",
                        mismatchException.getMessage()),
                () -> assertEquals(
                        "Password confirmation does not match.",
                        missingConfirmationException.getMessage()),
                () -> assertEquals(0, sideEffectCount.get()));
    }

    @Test
    void invalidCalendarNamesAreRejectedBeforeInvitationOrPasswordSideEffects() {
        AtomicInteger sideEffectCount = new AtomicInteger();
        RegistrationService registrationService = new RegistrationService();
        setField(registrationService, "calendarService", new CalendarService());
        setField(registrationService, "invitationService", new InvitationService() {
            @Override
            public RegistrationAdmission claimRegistrationAdmission(String invitationToken) {
                sideEffectCount.incrementAndGet();
                throw new AssertionError("An invalid calendar name must not claim an invitation.");
            }
        });
        setField(registrationService, "userService", new UserService() {
            @Override
            public ApplicationUser createUser(
                    String username,
                    String displayName,
                    String password) {
                sideEffectCount.incrementAndGet();
                throw new AssertionError(
                        "An invalid calendar name must not create a user or hash a password.");
            }
        });
        setField(registrationService, "auditService", new AuditService() {
            @Override
            public void record(
                    ApplicationUser actingUser,
                    Calendar calendar,
                    String entityType,
                    Long entityId,
                    String action,
                    String details) {
                sideEffectCount.incrementAndGet();
                throw new AssertionError("An invalid registration must not be audited.");
            }
        });

        ValidationException blankNameException = assertThrows(
                ValidationException.class,
                () -> registrationService.register(
                        "unused-token",
                        "new-user",
                        "New user",
                        "a valid lowercase passphrase",
                        "a valid lowercase passphrase",
                        " \t \n "));
        ValidationException longNameException = assertThrows(
                ValidationException.class,
                () -> registrationService.register(
                        "unused-token",
                        "new-user",
                        "New user",
                        "a valid lowercase passphrase",
                        "a valid lowercase passphrase",
                        "C".repeat(161)));

        assertAll(
                () -> assertEquals("Calendar name is required.", blankNameException.getMessage()),
                () -> assertEquals(
                        "Calendar name must be 160 characters or fewer.",
                        longNameException.getMessage()),
                () -> assertEquals(0, sideEffectCount.get()));
    }

    @Test
    void validRegistrationInputsAreValidatedBeforeOrderedSideEffects() {
        List<String> operations = new ArrayList<>();
        RegistrationAdmission admission = new RegistrationAdmission(null, true);
        ApplicationUser createdUser = new ApplicationUser();
        setField(createdUser, "id", 25L);
        createdUser.setActive(true);
        Calendar createdCalendar = new Calendar();
        createdCalendar.setName("Weekend plans");
        RegistrationService registrationService = new RegistrationService();
        setField(registrationService, "calendarService", new CalendarService() {
            @Override
            public String normalizeAndValidateCalendarName(String calendarName) {
                operations.add("calendar name validated");
                return super.normalizeAndValidateCalendarName(calendarName);
            }

            @Override
            public Calendar createCalendar(ApplicationUser creator, String name) {
                operations.add("calendar created with " + name);
                return createdCalendar;
            }
        });
        setField(registrationService, "invitationService", new InvitationService() {
            @Override
            public RegistrationAdmission claimRegistrationAdmission(String invitationToken) {
                operations.add("invitation claimed");
                return admission;
            }

            @Override
            public void acceptAdmission(
                    RegistrationAdmission acceptedAdmission,
                    ApplicationUser acceptingUser) {
                assertSame(admission, acceptedAdmission);
                assertSame(createdUser, acceptingUser);
                operations.add("invitation accepted");
            }
        });
        setField(registrationService, "userService", new UserService() {
            @Override
            public ApplicationUser createUser(
                    String username,
                    String displayName,
                    String password) {
                operations.add("user created and password hashed");
                return createdUser;
            }
        });
        setField(registrationService, "auditService", new AuditService() {
            @Override
            public void record(
                    ApplicationUser actingUser,
                    Calendar calendar,
                    String entityType,
                    Long entityId,
                    String action,
                    String details) {
                operations.add("registration audited");
            }
        });

        ApplicationUser registeredUser = registrationService.register(
                "registration-token",
                "new-user",
                "New user",
                "a valid lowercase passphrase",
                "a valid lowercase passphrase",
                "  Weekend plans  ");

        assertAll(
                () -> assertSame(createdUser, registeredUser),
                () -> assertEquals(
                        List.of(
                                "calendar name validated",
                                "invitation claimed",
                                "user created and password hashed",
                                "calendar created with Weekend plans",
                                "invitation accepted",
                                "registration audited"),
                        operations));
    }
}
