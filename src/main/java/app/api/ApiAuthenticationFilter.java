package app.api;

import app.security.ApiTokenService;
import app.user.ApplicationUser;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION)
public class ApiAuthenticationFilter implements ContainerRequestFilter {
    private static final Pattern BEARER_CREDENTIAL = Pattern.compile("(?i)^Bearer[ \\t]+([A-Za-z0-9_-]+)$");

    @Inject
    private ApiTokenService apiTokenService;

    @Inject
    private ApiCaller apiCaller;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        Optional<ApplicationUser> authenticatedUser = bearerToken(requestContext)
                .flatMap(apiTokenService::authenticate);
        if (authenticatedUser.isEmpty()) {
            requestContext.abortWith(ApiProblemResponses.unauthorized(
                    requestContext.getUriInfo().getRequestUri()));
            return;
        }
        apiCaller.authenticate(authenticatedUser.get());
    }

    static Optional<String> bearerToken(ContainerRequestContext requestContext) {
        List<String> authorizationHeaders = requestContext.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorizationHeaders == null || authorizationHeaders.size() != 1) {
            return Optional.empty();
        }
        String authorizationHeader = authorizationHeaders.getFirst();
        if (authorizationHeader == null) {
            return Optional.empty();
        }
        Matcher matcher = BEARER_CREDENTIAL.matcher(authorizationHeader);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
