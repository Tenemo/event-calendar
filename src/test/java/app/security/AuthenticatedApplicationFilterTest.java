package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AuthenticatedApplicationFilterTest {
    @Test
    void authenticatedUsersContinueWithoutMutatingTheResponse() throws Exception {
        AtomicInteger filterChainCalls = new AtomicInteger();
        TestResponse response = new TestResponse();

        new AuthenticatedApplicationFilter(currentUser(true))
                .doFilter(
                        request("/shared"),
                        response.proxy(),
                        countingFilterChain(filterChainCalls));

        assertAll(
                () -> assertEquals(1, filterChainCalls.get()),
                () -> assertEquals(0, response.resetBufferCalls.get()),
                () -> assertEquals(0, response.status.get()),
                () -> assertNull(response.headers.get("Location")));
    }

    @Test
    void anonymousUsersReceiveOnlyTheFixedOriginRelativeLoginRedirect() throws Exception {
        AtomicInteger filterChainCalls = new AtomicInteger();
        TestResponse response = new TestResponse();

        new AuthenticatedApplicationFilter(currentUser(false))
                .doFilter(
                        request("/shared"),
                        response.proxy(),
                        countingFilterChain(filterChainCalls));

        assertAll(
                () -> assertEquals(0, filterChainCalls.get()),
                () -> assertEquals(1, response.resetBufferCalls.get()),
                () -> assertEquals(HttpServletResponse.SC_FOUND, response.status.get()),
                () -> assertEquals("/shared/login", response.headers.get("Location")),
                () -> assertEquals("no-store", response.headers.get("Cache-Control")));
    }

    @Test
    void anonymousRedirectDoesNotReadClientControlledAuthorityOrForwardingHeaders() throws Exception {
        AtomicInteger filterChainCalls = new AtomicInteger();
        TestResponse response = new TestResponse();
        HttpServletRequest hostileRequest = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (ignoredProxy, method, arguments) -> {
                    if (method.getName().equals("getContextPath")) {
                        return "";
                    }
                    throw new AssertionError(
                            "Admission filter unexpectedly consulted request data through "
                                    + method.getName()
                                    + ".");
                });

        new AuthenticatedApplicationFilter(currentUser(false))
                .doFilter(
                        hostileRequest,
                        response.proxy(),
                        countingFilterChain(filterChainCalls));

        assertAll(
                () -> assertEquals(0, filterChainCalls.get()),
                () -> assertEquals("/login", response.headers.get("Location")),
                () -> assertEquals("no-store", response.headers.get("Cache-Control")));
    }

    @Test
    void nonHttpTrafficContinuesWithoutConsultingAuthenticationState() throws Exception {
        AtomicInteger filterChainCalls = new AtomicInteger();
        ServletRequest request = proxy(ServletRequest.class);
        ServletResponse response = proxy(ServletResponse.class);
        CurrentUser unexpectedCurrentUser = new CurrentUser() {
            @Override
            public boolean isSignedIn() {
                throw new AssertionError("Non-HTTP traffic must not consult the HTTP user session.");
            }
        };

        new AuthenticatedApplicationFilter(unexpectedCurrentUser)
                .doFilter(request, response, countingFilterChain(filterChainCalls));

        assertEquals(1, filterChainCalls.get());
    }

    private static CurrentUser currentUser(boolean signedIn) {
        return new CurrentUser() {
            @Override
            public boolean isSignedIn() {
                return signedIn;
            }
        };
    }

    private static FilterChain countingFilterChain(AtomicInteger calls) {
        return (request, response) -> calls.incrementAndGet();
    }

    private static HttpServletRequest request(String contextPath) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (ignoredProxy, method, arguments) -> method.getName().equals("getContextPath")
                        ? contextPath
                        : defaultValue(method.getReturnType()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> interfaceType) {
        return (T) Proxy.newProxyInstance(
                interfaceType.getClassLoader(),
                new Class<?>[] {interfaceType},
                (ignoredProxy, method, arguments) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return 0;
    }

    private static final class TestResponse {
        private final AtomicInteger resetBufferCalls = new AtomicInteger();
        private final AtomicInteger status = new AtomicInteger();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final HttpServletResponse proxy = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (ignoredProxy, method, arguments) -> switch (method.getName()) {
                    case "resetBuffer" -> {
                        resetBufferCalls.incrementAndGet();
                        yield null;
                    }
                    case "setStatus" -> {
                        status.set((Integer) arguments[0]);
                        yield null;
                    }
                    case "setHeader" -> {
                        headers.put((String) arguments[0], (String) arguments[1]);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });

        private HttpServletResponse proxy() {
            return proxy;
        }
    }
}
