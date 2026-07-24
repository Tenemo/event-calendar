package app.web;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class RelativeRedirectTest {
    @Test
    void writesAnExactOriginRelativeLocationAndPreservesExistingResponseHeaders() {
        TestResponse response = new TestResponse();
        response.headers.put("Content-Security-Policy", "frame-ancestors 'none'");
        response.headers.put("Set-Cookie", "JSESSIONID=session; Secure; HttpOnly");

        RelativeRedirect.send(
                response.proxy(),
                "",
                "/login?reauthenticationRequired=true");

        assertAll(
                () -> assertEquals(1, response.resetBufferCalls.get()),
                () -> assertEquals(HttpServletResponse.SC_FOUND, response.status.get()),
                () -> assertEquals(
                        "/login?reauthenticationRequired=true",
                        response.headers.get("Location")),
                () -> assertEquals("no-store", response.headers.get("Cache-Control")),
                () -> assertEquals(
                        "frame-ancestors 'none'",
                        response.headers.get("Content-Security-Policy")),
                () -> assertEquals(
                        "JSESSIONID=session; Secure; HttpOnly",
                        response.headers.get("Set-Cookie")));
    }

    @Test
    void preservesAValidatedNonRootServletContextWithoutCreatingAnAbsoluteUrl() {
        TestResponse response = new TestResponse();

        RelativeRedirect.send(
                response.proxy(),
                "/calendar",
                "/app/calendar-settings?id=42");

        assertEquals(
                "/calendar/app/calendar-settings?id=42",
                response.headers.get("Location"));
    }

    @Test
    void writesAProtocolCorrectPartialRedirectForFacesAjaxRequests() {
        TestResponse response = new TestResponse();

        RelativeRedirect.send(
                request("partial/ajax"),
                response.proxy(),
                "/shared",
                "/login?reauthenticationRequired=true&source=calendar");

        assertAll(
                () -> assertEquals(1, response.resetBufferCalls.get()),
                () -> assertEquals(HttpServletResponse.SC_OK, response.status.get()),
                () -> assertEquals("no-store", response.headers.get("Cache-Control")),
                () -> assertFalse(response.headers.containsKey("Location")),
                () -> assertEquals("text/xml;charset=UTF-8", response.contentType),
                () -> assertEquals(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<partial-response><redirect url=\"/shared/login?"
                                + "reauthenticationRequired=true&amp;source=calendar\"/>"
                                + "</partial-response>",
                        response.body.toString()));
    }

    @Test
    void acceptsOnlyUnambiguousOriginRelativeApplicationPaths() {
        for (String safeTarget : new String[] {
            "/",
            "/login?passwordChanged=true",
            "/register?token=abc_-",
            "/Abc_123-xY0",
            "/app/calendar-settings?id=42"
        }) {
            assertTrue(
                    RelativeRedirect.isSafeApplicationPath(safeTarget),
                    () -> "Expected the redirect target to be accepted: " + safeTarget);
        }

        for (String invalidTarget : new String[] {
            null,
            "",
            "   ",
            "login",
            "//attacker.invalid/login",
            "///attacker.invalid/login",
            "https://attacker.invalid/login",
            "/\\attacker.invalid/login",
            "/%5c%5cattacker.invalid/login",
            "/%2f%2fattacker.invalid/login",
            "/app%2f..%2flogin",
            "/app/../login",
            "/app/%2e%2e/login",
            "/app/%252e%252e/login",
            "/app//calendars",
            "/login#fragment",
            "/login\r\nX-Injected: true",
            "/login%0d%0aX-Injected:true",
            "/login\u007f",
            "/login\u0085",
            "/" + "x".repeat(RelativeRedirect.MAXIMUM_REDIRECT_TARGET_LENGTH)
        }) {
            assertFalse(
                    RelativeRedirect.isSafeApplicationPath(invalidTarget),
                    () -> "Expected the redirect target to be rejected: " + invalidTarget);
        }
    }

    @Test
    void rejectsInvalidContextPathsAndTargetsBeforeMutatingTheResponse() {
        for (String invalidContextPath : new String[] {
            "/",
            "calendar",
            "//attacker.invalid",
            "/calendar/",
            "/calendar\\admin",
            "/calendar\r\nX-Injected: true",
            "/../calendar",
            "/%2e%2e/calendar",
            "/%2f%2fattacker.invalid",
            "/calendar?path=/login"
        }) {
            TestResponse response = new TestResponse();

            assertThrows(
                    IllegalArgumentException.class,
                    () -> RelativeRedirect.send(
                            response.proxy(),
                            invalidContextPath,
                            "/login"));
            assertAll(
                    () -> assertEquals(0, response.resetBufferCalls.get()),
                    () -> assertEquals(0, response.status.get()),
                    () -> assertTrue(response.headers.isEmpty()));
        }

        TestResponse unsafeTargetResponse = new TestResponse();
        assertThrows(
                IllegalArgumentException.class,
                () -> RelativeRedirect.send(
                        unsafeTargetResponse.proxy(),
                        "",
                        "https://attacker.invalid"));
        assertEquals(0, unsafeTargetResponse.resetBufferCalls.get());
    }

    @Test
    void rejectsACombinedContextAndApplicationPathAboveTheBound() {
        TestResponse response = new TestResponse();
        String contextPath = "/" + "c".repeat(1_024);
        String applicationPath = "/" + "a".repeat(1_024);

        assertThrows(
                IllegalArgumentException.class,
                () -> RelativeRedirect.send(
                        response.proxy(),
                        contextPath,
                        applicationPath));
        assertEquals(0, response.resetBufferCalls.get());
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

    private static HttpServletRequest request(String facesRequestHeader) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (ignoredProxy, method, arguments) -> method.getName().equals("getHeader")
                                && "Faces-Request".equals(arguments[0])
                        ? facesRequestHeader
                        : defaultValue(method.getReturnType()));
    }

    private static final class TestResponse {
        private final AtomicInteger resetBufferCalls = new AtomicInteger();
        private final AtomicInteger status = new AtomicInteger();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final StringWriter body = new StringWriter();
        private String contentType;
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
                    case "setContentType" -> {
                        contentType = (String) arguments[0];
                        yield null;
                    }
                    case "getWriter" -> new PrintWriter(body, true);
                    default -> defaultValue(method.getReturnType());
                });

        private HttpServletResponse proxy() {
            return proxy;
        }
    }
}
