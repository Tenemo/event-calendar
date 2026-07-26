package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.invitation.InvitationToken;
import app.web.RelativeRedirect;
import org.junit.jupiter.api.Test;

final class SignInViewTest {
    @Test
    void successfulSignInUsesTheFixedDefaultForMissingOrUnsafeInvitationTokens() {
        String defaultRoute = AuthenticatedApplicationFilter.DEFAULT_AUTHENTICATED_ROUTE;

        assertAll(
                () -> assertEquals(defaultRoute, SignInView.successfulSignInRoute(null)),
                () -> assertEquals(defaultRoute, SignInView.successfulSignInRoute("   ")),
                () -> assertEquals(
                        defaultRoute,
                        SignInView.successfulSignInRoute(
                                "a".repeat(InvitationToken.MAXIMUM_LENGTH + 1))),
                () -> assertEquals(
                        defaultRoute,
                        SignInView.successfulSignInRoute("token\\suffix")),
                () -> assertEquals(
                        defaultRoute,
                        SignInView.successfulSignInRoute("token\r\nsuffix")));
    }

    @Test
    void successfulSignInNormalizesAndEncodesAValidBoundedInvitationToken() {
        String maximumLengthToken = "a".repeat(InvitationToken.MAXIMUM_LENGTH);

        assertAll(
                () -> assertEquals(
                        "/register?token=alpha+beta%26gamma",
                        SignInView.successfulSignInRoute("  alpha beta&gamma  ")),
                () -> assertEquals(
                        "/register?token=" + maximumLengthToken,
                        SignInView.successfulSignInRoute(maximumLengthToken)),
                () -> assertTrue(RelativeRedirect.isSafeApplicationPath(
                        SignInView.successfulSignInRoute("alpha beta&gamma"))));
    }

    @Test
    void statusViewParametersAcceptOnlyTheLiteralTrueValue() {
        SignInView signInView = new SignInView();

        signInView.setPasswordChangedParameter("TRUE");
        signInView.setReauthenticationRequiredParameter(" true ");
        assertAll(
                () -> assertFalse(signInView.isPasswordChanged()),
                () -> assertFalse(signInView.isReauthenticationRequired()));

        signInView.setPasswordChangedParameter("true");
        signInView.setReauthenticationRequiredParameter("true");
        assertAll(
                () -> assertTrue(signInView.isPasswordChanged()),
                () -> assertTrue(signInView.isReauthenticationRequired()));

        signInView.setPasswordChangedParameter(null);
        signInView.setReauthenticationRequiredParameter("not-a-boolean");
        assertAll(
                () -> assertFalse(signInView.isPasswordChanged()),
                () -> assertFalse(signInView.isReauthenticationRequired()));
    }
}
