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
import app.security.CurrentUser;
import app.user.ApplicationUser;
import app.util.NotFoundException;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class CalendarRouteServletTest {
    @Test
    void extractsExactlyOneNonblankPathSegmentAsTheBearerToken() {
        assertAll(
                () -> assertEquals("token-123", CalendarRouteServlet.tokenFromPath("/token-123")),
                () -> assertNull(CalendarRouteServlet.tokenFromPath(null)),
                () -> assertNull(CalendarRouteServlet.tokenFromPath("")),
                () -> assertNull(CalendarRouteServlet.tokenFromPath("/")),
                () -> assertNull(CalendarRouteServlet.tokenFromPath("/first/second")),
                () -> assertNull(CalendarRouteServlet.tokenFromPath("/   ")));
    }

    @Test
    void passesTheCurrentUserToCanonicalCalendarAccess() throws Exception {
        ApplicationUser user = activeUser();
        Calendar calendar = new Calendar();
        AtomicReference<ApplicationUser> receivedUser = new AtomicReference<>();
        AtomicReference<String> receivedToken = new AtomicReference<>();
        CalendarRouteServlet servlet = servlet(new CalendarAccessService() {
            @Override
            public Calendar requireCalendarReadableByToken(ApplicationUser candidate, String calendarToken) {
                receivedUser.set(candidate);
                receivedToken.set(calendarToken);
                return calendar;
            }
        }, user);
        Map<String, Object> requestAttributes = new HashMap<>();

        servlet.doGet(
                request("/token-123", forwardingDispatcher(new AtomicInteger()), requestAttributes),
                response(new AtomicInteger()));

        assertAll(
                () -> assertSame(user, receivedUser.get()),
                () -> assertEquals("token-123", receivedToken.get()),
                () -> assertSame(calendar, requestAttributes.get(CalendarRouteServlet.CALENDAR_REQUEST_ATTRIBUTE)));
    }

    @Test
    void doesNotTreatAnExceptionFromTheForwardedViewAsAFailedCalendarLookup() {
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
        CalendarRouteServlet servlet = servlet(new CalendarAccessService() {
            @Override
            public Calendar requireCalendarReadableByToken(ApplicationUser user, String calendarToken) {
                return new Calendar();
            }
        }, null);

        assertThrows(
                NotFoundException.class,
                () -> servlet.doGet(
                        request("/token-123", requestDispatcher, new HashMap<>()),
                        response(new AtomicInteger())));
        assertEquals(1, forwardCount.get(), "A forwarded-view exception must not trigger a second forward.");
    }

    @Test
    void forwardsARejectedCalendarTokenExactlyOnceWithAFixedNotFoundStatus() throws Exception {
        AtomicInteger forwardCount = new AtomicInteger();
        AtomicInteger responseStatus = new AtomicInteger(HttpServletResponse.SC_OK);
        Map<String, Object> requestAttributes = new HashMap<>();
        RequestDispatcher requestDispatcher = new RequestDispatcher() {
            @Override
            public void forward(ServletRequest request, ServletResponse response) {
                forwardCount.incrementAndGet();
                HttpServletResponse forwardedResponse = (HttpServletResponse) response;
                forwardedResponse.setStatus(HttpServletResponse.SC_OK);
                assertEquals(HttpServletResponse.SC_NOT_FOUND, responseStatus.get());
            }

            @Override
            public void include(ServletRequest request, ServletResponse response) {
            }
        };
        CalendarRouteServlet servlet = servlet(new CalendarAccessService() {
            @Override
            public Calendar requireCalendarReadableByToken(ApplicationUser user, String calendarToken) {
                throw new NotFoundException("Calendar was not found.");
            }
        }, null);

        servlet.doGet(request("/missing-token", requestDispatcher, requestAttributes), response(responseStatus));

        assertAll(
                () -> assertEquals(1, forwardCount.get()),
                () -> assertEquals(
                        "missing-token",
                        requestAttributes.get(CalendarRouteServlet.CALENDAR_TOKEN_REQUEST_ATTRIBUTE)),
                () -> assertTrue(Boolean.TRUE.equals(
                        requestAttributes.get(CalendarRouteServlet.NOT_FOUND_REQUEST_ATTRIBUTE))),
                () -> assertNull(requestAttributes.get(CalendarRouteServlet.CALENDAR_REQUEST_ATTRIBUTE)),
                () -> assertEquals(HttpServletResponse.SC_NOT_FOUND, responseStatus.get()));
    }

    private static CalendarRouteServlet servlet(CalendarAccessService calendarAccessService, ApplicationUser user) {
        CalendarRouteServlet servlet = new CalendarRouteServlet();
        setField(servlet, "calendarAccessService", calendarAccessService);
        setField(servlet, "currentUser", new FixedCurrentUser(user));
        return servlet;
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
            String pathInfo, RequestDispatcher requestDispatcher, Map<String, Object> requestAttributes) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                CalendarRouteServletTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getPathInfo" -> pathInfo;
                    case "getRequestDispatcher" -> requestDispatcher;
                    case "getAttribute" -> requestAttributes.get(arguments[0]);
                    case "setAttribute" -> {
                        requestAttributes.put((String) arguments[0], arguments[1]);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static HttpServletResponse response(AtomicInteger responseStatus) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                CalendarRouteServletTest.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getStatus" -> responseStatus.get();
                    case "setStatus" -> {
                        responseStatus.set((Integer) arguments[0]);
                        yield null;
                    }
                    case "reset" -> {
                        responseStatus.set(HttpServletResponse.SC_OK);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
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
            return (char) 0;
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
}
