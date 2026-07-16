package app.calendar;

import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.membership.CalendarAccessService;
import app.security.ClientRequestSourceResolver;
import app.security.CurrentUser;
import app.user.ApplicationUser;
import app.util.NotFoundException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class CalendarRouteFilterTest {
    private static final String CALENDAR_TOKEN = "Abc_123-xY0";

    @Test
    void bypassesNonCalendarPathsAndNonGetRequestsWithoutAccessingTheDatabase() throws Exception {
        AtomicInteger filterChainCalls = new AtomicInteger();
        CalendarRouteFilter filter = new CalendarRouteFilter();

        for (RequestCase requestCase : new RequestCase[] {
            new RequestCase("GET", "/calendar/" + CALENDAR_TOKEN),
            new RequestCase("GET", "/" + CALENDAR_TOKEN + "/events"),
            new RequestCase("GET", "/Abc_123-xYz"),
            new RequestCase("GET", "/invalid.path"),
            new RequestCase("POST", "/" + CALENDAR_TOKEN)
        }) {
            filter.doFilter(
                    request(requestCase.method(), requestCase.path(), null, new HashMap<>(), "192.0.2.10"),
                    response(new ResponseState()),
                    filterChain(filterChainCalls));
        }

        assertEquals(5, filterChainCalls.get());
    }

    @Test
    void passesTheCurrentUserToCanonicalCalendarAccessAndReleasesCapacity() throws Exception {
        ApplicationUser user = activeUser();
        Calendar calendar = new Calendar();
        AtomicReference<ApplicationUser> receivedUser = new AtomicReference<>();
        AtomicReference<String> receivedToken = new AtomicReference<>();
        AtomicInteger forwardCount = new AtomicInteger();
        CalendarLinkRequestThrottle throttle = throttle(1, 10);
        CalendarRouteFilter filter = filter(new CalendarAccessService() {
            @Override
            public Calendar requireCalendarReadableByToken(ApplicationUser candidate, String calendarToken) {
                receivedUser.set(candidate);
                receivedToken.set(calendarToken);
                return calendar;
            }
        }, user, throttle);
        Map<String, Object> requestAttributes = new HashMap<>();

        filter.doFilter(
                request("GET", "/" + CALENDAR_TOKEN, forwardingDispatcher(forwardCount), requestAttributes, "192.0.2.10"),
                response(new ResponseState()),
                filterChain(new AtomicInteger()));

        assertAll(
                () -> assertSame(user, receivedUser.get()),
                () -> assertEquals(CALENDAR_TOKEN, receivedToken.get()),
                () -> assertSame(calendar, requestAttributes.get(CalendarRouteFilter.CALENDAR_REQUEST_ATTRIBUTE)),
                () -> assertEquals(1, forwardCount.get()),
                () -> assertEquals(1, throttle.availableConcurrentRequestPermits()));
    }

    @Test
    void doesNotTreatAnExceptionFromTheForwardedViewAsAFailedCalendarLookupAndReleasesCapacity() {
        AtomicInteger forwardCount = new AtomicInteger();
        RequestDispatcher requestDispatcher = new RequestDispatcher() {
            @Override
            public void forward(ServletRequest request, ServletResponse response) {
                forwardCount.incrementAndGet();
                throw new NotFoundException("Forwarded view failed.");
            }

            @Override
            public void include(ServletRequest request, ServletResponse response) {
            }
        };
        CalendarLinkRequestThrottle throttle = throttle(1, 10);
        CalendarRouteFilter filter = filter(new CalendarAccessService() {
            @Override
            public Calendar requireCalendarReadableByToken(ApplicationUser user, String calendarToken) {
                return new Calendar();
            }
        }, null, throttle);

        assertThrows(
                NotFoundException.class,
                () -> filter.doFilter(
                        request("GET", "/" + CALENDAR_TOKEN, requestDispatcher, new HashMap<>(), "192.0.2.10"),
                        response(new ResponseState()),
                        filterChain(new AtomicInteger())));
        assertAll(
                () -> assertEquals(1, forwardCount.get()),
                () -> assertEquals(1, throttle.availableConcurrentRequestPermits()));
    }

    @Test
    void forwardsARejectedCalendarTokenExactlyOnceWithAFixedNotFoundStatus() throws Exception {
        AtomicInteger forwardCount = new AtomicInteger();
        ResponseState responseState = new ResponseState();
        Map<String, Object> requestAttributes = new HashMap<>();
        RequestDispatcher requestDispatcher = new RequestDispatcher() {
            @Override
            public void forward(ServletRequest request, ServletResponse response) {
                forwardCount.incrementAndGet();
                HttpServletResponse forwardedResponse = (HttpServletResponse) response;
                forwardedResponse.reset();
                forwardedResponse.setStatus(HttpServletResponse.SC_OK);
                assertEquals(HttpServletResponse.SC_NOT_FOUND, responseState.status.get());
            }

            @Override
            public void include(ServletRequest request, ServletResponse response) {
            }
        };
        CalendarRouteFilter filter = filter(new CalendarAccessService() {
            @Override
            public Calendar requireCalendarReadableByToken(ApplicationUser user, String calendarToken) {
                throw new NotFoundException("Calendar was not found.");
            }
        }, null, throttle(1, 10));

        filter.doFilter(
                request("GET", "/" + CALENDAR_TOKEN, requestDispatcher, requestAttributes, "192.0.2.10"),
                response(responseState),
                filterChain(new AtomicInteger()));

        assertAll(
                () -> assertEquals(1, forwardCount.get()),
                () -> assertEquals(
                        CALENDAR_TOKEN,
                        requestAttributes.get(CalendarRouteFilter.CALENDAR_TOKEN_REQUEST_ATTRIBUTE)),
                () -> assertTrue(Boolean.TRUE.equals(
                        requestAttributes.get(CalendarRouteFilter.NOT_FOUND_REQUEST_ATTRIBUTE))),
                () -> assertNull(requestAttributes.get(CalendarRouteFilter.CALENDAR_REQUEST_ATTRIBUTE)),
                () -> assertEquals(HttpServletResponse.SC_NOT_FOUND, responseState.status.get()));
    }

    @Test
    void returnsAGenericRetryableResponseBeforeCalendarLookupWhenTheSourceLimitIsReached() throws Exception {
        AtomicInteger accessCalls = new AtomicInteger();
        CalendarLinkRequestThrottle throttle = throttle(2, 1);
        CalendarRouteFilter filter = filter(new CalendarAccessService() {
            @Override
            public Calendar requireCalendarReadableByToken(ApplicationUser user, String calendarToken) {
                accessCalls.incrementAndGet();
                return new Calendar();
            }
        }, null, throttle);
        RequestDispatcher requestDispatcher = forwardingDispatcher(new AtomicInteger());

        filter.doFilter(
                request("GET", "/" + CALENDAR_TOKEN, requestDispatcher, new HashMap<>(), "192.0.2.10"),
                response(new ResponseState()),
                filterChain(new AtomicInteger()));
        ResponseState rejectedResponse = new ResponseState();
        filter.doFilter(
                request("GET", "/" + CALENDAR_TOKEN, requestDispatcher, new HashMap<>(), "192.0.2.10"),
                response(rejectedResponse),
                filterChain(new AtomicInteger()));

        assertAll(
                () -> assertEquals(1, accessCalls.get()),
                () -> assertEquals(429, rejectedResponse.status.get()),
                () -> assertEquals("60", rejectedResponse.headers.get("Retry-After")),
                () -> assertEquals("no-store", rejectedResponse.headers.get("Cache-Control")),
                () -> assertEquals("text/plain;charset=UTF-8", rejectedResponse.contentType),
                () -> assertEquals("Too many calendar link requests. Try again later.", rejectedResponse.body.toString()),
                () -> assertTrue(!rejectedResponse.body.toString().contains(CALENDAR_TOKEN)));
    }

    @Test
    void railwayClientsBehindTheSameProxyReceiveIndependentCalendarLimits() throws Exception {
        AtomicInteger accessCalls = new AtomicInteger();
        CalendarRouteFilter filter = filter(new CalendarAccessService() {
            @Override
            public Calendar requireCalendarReadableByToken(ApplicationUser user, String calendarToken) {
                accessCalls.incrementAndGet();
                return new Calendar();
            }
        }, null, throttle(1, 1), new ClientRequestSourceResolver("production-environment-id"));
        RequestDispatcher requestDispatcher = forwardingDispatcher(new AtomicInteger());

        filter.doFilter(
                request(
                        "GET",
                        "/" + CALENDAR_TOKEN,
                        requestDispatcher,
                        new HashMap<>(),
                        "100.64.8.9",
                        "198.51.100.10"),
                response(new ResponseState()),
                filterChain(new AtomicInteger()));
        filter.doFilter(
                request(
                        "GET",
                        "/" + CALENDAR_TOKEN,
                        requestDispatcher,
                        new HashMap<>(),
                        "100.64.8.9",
                        "198.51.100.11"),
                response(new ResponseState()),
                filterChain(new AtomicInteger()));

        assertEquals(2, accessCalls.get());
    }

    private static CalendarRouteFilter filter(
            CalendarAccessService calendarAccessService,
            ApplicationUser user,
            CalendarLinkRequestThrottle throttle) {
        return filter(
                calendarAccessService,
                user,
                throttle,
                new ClientRequestSourceResolver(null));
    }

    private static CalendarRouteFilter filter(
            CalendarAccessService calendarAccessService,
            ApplicationUser user,
            CalendarLinkRequestThrottle throttle,
            ClientRequestSourceResolver clientRequestSourceResolver) {
        CalendarRouteFilter filter = new CalendarRouteFilter();
        setField(filter, "calendarAccessService", calendarAccessService);
        setField(filter, "currentUser", new FixedCurrentUser(user));
        setField(filter, "requestThrottle", throttle);
        setField(filter, "clientRequestSourceResolver", clientRequestSourceResolver);
        return filter;
    }

    private static CalendarLinkRequestThrottle throttle(int maximumConcurrentRequests, int maximumRequestsPerSource) {
        return new CalendarLinkRequestThrottle(
                Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), ZoneOffset.UTC),
                maximumRequestsPerSource,
                Duration.ofMinutes(1),
                100,
                maximumConcurrentRequests);
    }

    private static RequestDispatcher forwardingDispatcher(AtomicInteger forwardCount) {
        return new RequestDispatcher() {
            @Override
            public void forward(ServletRequest request, ServletResponse response) {
                forwardCount.incrementAndGet();
            }

            @Override
            public void include(ServletRequest request, ServletResponse response) {
            }
        };
    }

    private static HttpServletRequest request(
            String method,
            String requestUri,
            RequestDispatcher requestDispatcher,
            Map<String, Object> requestAttributes,
            String remoteAddress) {
        return request(
                method,
                requestUri,
                requestDispatcher,
                requestAttributes,
                remoteAddress,
                new String[0]);
    }

    private static HttpServletRequest request(
            String method,
            String requestUri,
            RequestDispatcher requestDispatcher,
            Map<String, Object> requestAttributes,
            String remoteAddress,
            String... realIpHeaders) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                CalendarRouteFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, calledMethod, arguments) -> switch (calledMethod.getName()) {
                    case "getMethod" -> method;
                    case "getContextPath" -> "";
                    case "getRequestURI" -> requestUri;
                    case "getRemoteAddr" -> remoteAddress;
                    case "getHeaders" -> "X-Real-IP".equals(arguments[0])
                            ? Collections.enumeration(java.util.List.of(realIpHeaders))
                            : Collections.emptyEnumeration();
                    case "getRequestDispatcher" -> requestDispatcher;
                    case "getAttribute" -> requestAttributes.get(arguments[0]);
                    case "setAttribute" -> {
                        requestAttributes.put((String) arguments[0], arguments[1]);
                        yield null;
                    }
                    default -> defaultValue(calledMethod.getReturnType());
                });
    }

    private static HttpServletResponse response(ResponseState responseState) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                CalendarRouteFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getStatus" -> responseState.status.get();
                    case "setStatus" -> {
                        responseState.status.set((Integer) arguments[0]);
                        yield null;
                    }
                    case "setHeader" -> {
                        responseState.headers.put((String) arguments[0], (String) arguments[1]);
                        yield null;
                    }
                    case "setContentType" -> {
                        responseState.contentType = (String) arguments[0];
                        yield null;
                    }
                    case "getWriter" -> new PrintWriter(responseState.body, true);
                    case "reset" -> {
                        responseState.status.set(HttpServletResponse.SC_OK);
                        responseState.headers.clear();
                        responseState.body.getBuffer().setLength(0);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static FilterChain filterChain(AtomicInteger calls) {
        return (request, response) -> calls.incrementAndGet();
    }

    private static ApplicationUser activeUser() {
        ApplicationUser user = new ApplicationUser();
        setEntityId(user, 20L);
        user.setUsername("editor");
        user.setDisplayName("Editor");
        user.setActive(true);
        return user;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class FixedCurrentUser extends CurrentUser {
        private final ApplicationUser user;

        private FixedCurrentUser(ApplicationUser user) {
            this.user = user;
        }

        @Override
        public Optional<ApplicationUser> find() {
            return Optional.ofNullable(user);
        }
    }

    private static final class ResponseState {
        private final AtomicInteger status = new AtomicInteger(HttpServletResponse.SC_OK);
        private final Map<String, String> headers = new HashMap<>();
        private final StringWriter body = new StringWriter();
        private String contentType;
    }

    private record RequestCase(String method, String path) {
    }
}
