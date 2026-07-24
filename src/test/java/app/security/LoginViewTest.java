package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.invitation.InvitationToken;
import app.web.RelativeRedirect;
import org.junit.jupiter.api.Test;

final class LoginViewTest {
    @Test
    void successfulLoginUsesTheFixedDefaultForMissingOrUnsafeInvitationTokens() {
        String defaultRoute = AuthenticatedApplicationFilter.DEFAULT_AUTHENTICATED_ROUTE;

        assertAll(
                () -> assertEquals(defaultRoute, LoginView.successfulLoginRoute(null)),
                () -> assertEquals(defaultRoute, LoginView.successfulLoginRoute("   ")),
                () -> assertEquals(
                        defaultRoute,
                        LoginView.successfulLoginRoute(
                                "a".repeat(InvitationToken.MAXIMUM_LENGTH + 1))),
                () -> assertEquals(
                        defaultRoute,
                        LoginView.successfulLoginRoute("token\\suffix")),
                () -> assertEquals(
                        defaultRoute,
                        LoginView.successfulLoginRoute("token\r\nsuffix")));
    }

    @Test
    void successfulLoginNormalizesAndEncodesAValidBoundedInvitationToken() {
        String maximumLengthToken = "a".repeat(InvitationToken.MAXIMUM_LENGTH);

        assertAll(
                () -> assertEquals(
                        "/register?token=alpha+beta%26gamma",
                        LoginView.successfulLoginRoute("  alpha beta&gamma  ")),
                () -> assertEquals(
                        "/register?token=" + maximumLengthToken,
                        LoginView.successfulLoginRoute(maximumLengthToken)),
                () -> assertTrue(RelativeRedirect.isSafeApplicationPath(
                        LoginView.successfulLoginRoute("alpha beta&gamma"))));
    }

    @Test
    void statusViewParametersAcceptOnlyTheLiteralTrueValue() {
        LoginView loginView = new LoginView();

        loginView.setPasswordChangedParameter("TRUE");
        loginView.setReauthenticationRequiredParameter(" true ");
        assertAll(
                () -> assertFalse(loginView.isPasswordChanged()),
                () -> assertFalse(loginView.isReauthenticationRequired()));

        loginView.setPasswordChangedParameter("true");
        loginView.setReauthenticationRequiredParameter("true");
        assertAll(
                () -> assertTrue(loginView.isPasswordChanged()),
                () -> assertTrue(loginView.isReauthenticationRequired()));

        loginView.setPasswordChangedParameter(null);
        loginView.setReauthenticationRequiredParameter("not-a-boolean");
        assertAll(
                () -> assertFalse(loginView.isPasswordChanged()),
                () -> assertFalse(loginView.isReauthenticationRequired()));
    }
}
