package app.health;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

final class HealthServletTest {
    @Test
    void healthyDatabaseReturnsOk() throws Exception {
        HealthServlet healthServlet = healthServlet(databaseHealthMonitorReturning(true));
        ResponseCapture responseCapture = new ResponseCapture();

        healthServlet.doGet(null, responseCapture.response());

        assertAll(
                () -> assertEquals(HttpServletResponse.SC_OK, responseCapture.status),
                () -> assertEquals("text/plain; charset=UTF-8", responseCapture.contentType),
                () -> assertEquals("no-store", responseCapture.cacheControl),
                () -> assertEquals("ok", responseCapture.body()));
    }

    @Test
    void unusableDatabaseReturnsTheExactGenericServiceUnavailableResponse() throws Exception {
        HealthServlet healthServlet = healthServlet(databaseHealthMonitorReturning(false));
        ResponseCapture responseCapture = new ResponseCapture();

        healthServlet.doGet(null, responseCapture.response());

        assertAll(
                () -> assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, responseCapture.status),
                () -> assertEquals("unavailable", responseCapture.body()));
    }

    @Test
    void unexpectedProgrammingFailuresAreNotMisreportedAsDatabaseOutages() {
        IllegalStateException unexpectedFailure = new IllegalStateException("Unexpected monitor failure.");
        HealthServlet healthServlet = healthServlet(databaseHealthMonitorThrowing(unexpectedFailure));

        IllegalStateException thrownFailure = assertThrows(
                IllegalStateException.class,
                () -> healthServlet.doGet(null, new ResponseCapture().response()));

        assertSame(unexpectedFailure, thrownFailure);
    }

    private static HealthServlet healthServlet(DatabaseHealthMonitor databaseHealthMonitor) {
        HealthServlet healthServlet = new HealthServlet();
        setField(healthServlet, "databaseHealthMonitor", databaseHealthMonitor);
        return healthServlet;
    }

    private static DatabaseHealthMonitor databaseHealthMonitorReturning(boolean result) {
        return new StubDatabaseHealthMonitor(result, null);
    }

    private static DatabaseHealthMonitor databaseHealthMonitorThrowing(RuntimeException exception) {
        return new StubDatabaseHealthMonitor(false, exception);
    }

    private static Object invokeObjectMethod(Object proxy, Method method, Object[] arguments) {
        if (method.getDeclaringClass() != Object.class) {
            throw new AssertionError("Unsupported HttpServletResponse method: " + method.getName());
        }
        return switch (method.getName()) {
            case "toString" -> "HttpServletResponse test proxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> throw new AssertionError("Unsupported Object method: " + method.getName());
        };
    }

    private static final class StubDatabaseHealthMonitor extends DatabaseHealthMonitor {
        private final boolean result;
        private final RuntimeException exception;

        private StubDatabaseHealthMonitor(boolean result, RuntimeException exception) {
            this.result = result;
            this.exception = exception;
        }

        @Override
        public boolean isDatabaseUsable() {
            if (exception != null) {
                throw exception;
            }
            return result;
        }
    }

    private static final class ResponseCapture implements java.lang.reflect.InvocationHandler {
        private final StringWriter responseBody = new StringWriter();
        private final PrintWriter responseWriter = new PrintWriter(responseBody);
        private int status;
        private String contentType;
        private String cacheControl;

        private HttpServletResponse response() {
            return (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[] { HttpServletResponse.class },
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "setStatus" -> setStatus((Integer) arguments[0]);
                case "setContentType" -> setContentType((String) arguments[0]);
                case "setHeader" -> setHeader((String) arguments[0], (String) arguments[1]);
                case "getWriter" -> responseWriter;
                default -> invokeObjectMethod(proxy, method, arguments);
            };
        }

        private Object setStatus(int responseStatus) {
            status = responseStatus;
            return null;
        }

        private Object setContentType(String responseContentType) {
            contentType = responseContentType;
            return null;
        }

        private Object setHeader(String name, String value) {
            if (name.equals("Cache-Control")) {
                cacheControl = value;
            }
            return null;
        }

        private String body() {
            responseWriter.flush();
            return responseBody.toString();
        }
    }
}
