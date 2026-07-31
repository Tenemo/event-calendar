package app.security;

import app.user.ApplicationUser;
import app.user.UserService;
import app.web.RelativeRedirect;
import jakarta.inject.Inject;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.SecurityContext;
import jakarta.security.enterprise.authentication.mechanism.http.AuthenticationParameters;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;

public final class LocalDevelopmentAutoSignInFilter implements Filter {
    @Inject
    private LocalDevelopmentAutoSignIn localDevelopmentAutoSignIn;

    @Inject
    private SecurityContext securityContext;

    @Inject
    private UserService userService;

    public LocalDevelopmentAutoSignInFilter() {
    }

    LocalDevelopmentAutoSignInFilter(
            LocalDevelopmentAutoSignIn localDevelopmentAutoSignIn,
            SecurityContext securityContext,
            UserService userService) {
        this.localDevelopmentAutoSignIn = localDevelopmentAutoSignIn;
        this.securityContext = securityContext;
        this.userService = userService;
    }

    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain filterChain)
            throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)
                || !localDevelopmentAutoSignIn.shouldAttempt(request)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        ApplicationUser existingUser = findAuthenticatedUser();
        if (existingUser != null
                && AuthenticatedSessionSecurity.hasCurrentPasswordVersion(request, existingUser)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        if (securityContext.getCallerPrincipal() != null) {
            AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);
        }

        AuthenticationStatus status = securityContext.authenticate(
                request,
                response,
                AuthenticationParameters.withParams()
                        .credential(localDevelopmentAutoSignIn.createCredential())
                        .newAuthentication(true));
        if (status == AuthenticationStatus.SUCCESS) {
            continueAfterSuccessfulAuthentication(request, response, filterChain);
            return;
        }
        if (status == AuthenticationStatus.NOT_DONE) {
            filterChain.doFilter(servletRequest, servletResponse);
        }
    }

    private void continueAfterSuccessfulAuthentication(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws IOException, ServletException {
        ApplicationUser authenticatedUser = findAuthenticatedUser();
        if (authenticatedUser == null
                || !localDevelopmentAutoSignIn
                        .getAdminUsername()
                        .equals(authenticatedUser.getUsername())) {
            AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);
            filterChain.doFilter(request, response);
            return;
        }
        AuthenticatedSessionSecurity.establishAuthenticatedSession(
                request, authenticatedUser.getPasswordVersion());
        if (localDevelopmentAutoSignIn.isSignInPage(request)) {
            RelativeRedirect.send(
                    request,
                    response,
                    AuthenticatedApplicationFilter.DEFAULT_AUTHENTICATED_ROUTE);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private ApplicationUser findAuthenticatedUser() {
        Principal callerPrincipal = securityContext.getCallerPrincipal();
        if (callerPrincipal == null) {
            return null;
        }
        return userService.findByUsername(callerPrincipal.getName()).orElse(null);
    }
}
