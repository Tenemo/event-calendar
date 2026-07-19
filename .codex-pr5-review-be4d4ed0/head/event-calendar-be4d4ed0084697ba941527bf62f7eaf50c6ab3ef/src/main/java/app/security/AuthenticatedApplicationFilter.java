package app.security;

import app.web.RelativeRedirect;
import jakarta.inject.Inject;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class AuthenticatedApplicationFilter implements Filter {
    static final String DEFAULT_AUTHENTICATED_ROUTE = "/app/calendars";
    @Inject
    private CurrentUser currentUser;

    public AuthenticatedApplicationFilter() {
    }

    AuthenticatedApplicationFilter(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain filterChain)
            throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)
                || currentUser.isSignedIn()) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        RelativeRedirect.send(response, request.getContextPath(), "/login");
    }
}
