package app.web;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class ExpiredViewExceptionHandlerTest {
    @Test
    void recoveryReturnsToTheSameApplicationPageWithoutSubmittedCredentials() {
        assertEquals(
                "/sign-in?viewExpired=true",
                ExpiredViewExceptionHandler.recoveryApplicationPath(
                        "",
                        "/sign-in",
                        Map.of(
                                "form:username", new String[] {"private-user"},
                                "form:password", new String[] {"private-password"})));
    }

    @Test
    void recoveryPreservesOnlyOneValidatedInvitationToken() {
        assertAll(
                () -> assertEquals(
                        "/register?viewExpired=true&token=alpha+beta%26gamma",
                        ExpiredViewExceptionHandler.recoveryApplicationPath(
                                "/calendar",
                                "/calendar/register",
                                Map.of(
                                        "form:invitationToken",
                                        new String[] {"  alpha beta&gamma  "},
                                        "form:password",
                                        new String[] {"private-password"}))),
                () -> assertEquals(
                        "/register?viewExpired=true",
                        ExpiredViewExceptionHandler.recoveryApplicationPath(
                                "",
                                "/register",
                                Map.of(
                                        "first:invitationToken",
                                        new String[] {"first-token"},
                                        "second:invitationToken",
                                        new String[] {"second-token"}))));
    }

    @Test
    void recoveryFallsBackToTheApplicationRootForUnsafeOrUnrelatedPaths() {
        assertAll(
                () -> assertEquals(
                        "/?viewExpired=true",
                        ExpiredViewExceptionHandler.recoveryApplicationPath(
                                "/calendar", "/other/sign-in", Map.of())),
                () -> assertEquals(
                        "/?viewExpired=true",
                        ExpiredViewExceptionHandler.recoveryApplicationPath(
                                "", "//attacker.example/sign-in", Map.of())),
                () -> assertEquals(
                        "/?viewExpired=true",
                        ExpiredViewExceptionHandler.recoveryApplicationPath(
                                "", "/sign-in\\attacker.example", Map.of())),
                () -> assertEquals(
                        "/?viewExpired=true",
                        ExpiredViewExceptionHandler.recoveryApplicationPath(
                                "", "/sign-in\r\nLocation: https://attacker.example", Map.of())));
    }
}
