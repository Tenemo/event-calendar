package app.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.security.enterprise.authentication.mechanism.http.CustomFormAuthenticationMechanismDefinition;
import jakarta.security.enterprise.authentication.mechanism.http.LoginToContinue;

@ApplicationScoped
@CustomFormAuthenticationMechanismDefinition(
        loginToContinue = @LoginToContinue(
                loginPage = "/sign-in",
                errorPage = "/sign-in-error",
                useForwardToLogin = false))
public class FormAuthenticationConfiguration {
}
