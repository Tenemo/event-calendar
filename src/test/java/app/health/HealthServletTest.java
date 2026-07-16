package app.health;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

final class HealthServletTest {
    @Test
    void healthyDatabaseReturnsOkAndClosesTheValidationConnection() throws Exception {
        RecordingConnection recordingConnection = new RecordingConnection(true);
        HealthServlet healthServlet = healthServlet(dataSourceReturning(recordingConnection.connection()));
        ResponseCapture responseCapture = new ResponseCapture();

        healthServlet.doGet(null, responseCapture.response());

        assertAll(
                () -> assertEquals(HttpServletResponse.SC_OK, responseCapture.status),
                () -> assertEquals("text/plain; charset=UTF-8", responseCapture.contentType),
                () -> assertEquals("no-store", responseCapture.cacheControl),
                () -> assertEquals("ok", responseCapture.body()),
                () -> assertEquals(2, recordingConnection.validationTimeoutSeconds),
                () -> assertTrue(recordingConnection.closed));
    }

    @Test
    void unusableDatabaseReturnsServiceUnavailable() throws Exception {
        RecordingConnection recordingConnection = new RecordingConnection(false);
        HealthServlet healthServlet = healthServlet(dataSourceReturning(recordingConnection.connection()));
        ResponseCapture responseCapture = new ResponseCapture();

        healthServlet.doGet(null, responseCapture.response());

        assertAll(
                () -> assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, responseCapture.status),
                () -> assertEquals("unavailable", responseCapture.body()),
                () -> assertTrue(recordingConnection.closed));
    }

    @Test
    void databaseFailureDoesNotLeakItsDetails() throws Exception {
        String sensitiveFailureMessage = "Could not connect with password secret-password.";
        HealthServlet healthServlet = healthServlet(dataSourceThrowing(new SQLException(sensitiveFailureMessage)));
        ResponseCapture responseCapture = new ResponseCapture();

        healthServlet.doGet(null, responseCapture.response());

        assertAll(
                () -> assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, responseCapture.status),
                () -> assertEquals("unavailable", responseCapture.body()),
                () -> assertFalse(responseCapture.body().contains(sensitiveFailureMessage)));
    }

    @Test
    void unexpectedProgrammingFailuresAreNotMisreportedAsDatabaseOutages() {
        IllegalStateException unexpectedFailure = new IllegalStateException("Unexpected datasource implementation failure.");
        HealthServlet healthServlet = healthServlet(dataSourceThrowing(unexpectedFailure));

        IllegalStateException thrownFailure = assertThrows(
                IllegalStateException.class,
                () -> healthServlet.doGet(null, new ResponseCapture().response()));

        assertSame(unexpectedFailure, thrownFailure);
    }

    private static HealthServlet healthServlet(DataSource dataSource) {
        HealthServlet healthServlet = new HealthServlet();
        setField(healthServlet, "dataSource", dataSource);
        return healthServlet;
    }

    private static DataSource dataSourceReturning(Connection connection) {
        return dataSource((proxy, method, arguments) -> {
            if (method.getName().equals("getConnection")) {
                return connection;
            }
            return invokeObjectMethod(proxy, method, arguments, "DataSource");
        });
    }

    private static DataSource dataSourceThrowing(Throwable exception) {
        return dataSource((proxy, method, arguments) -> {
            if (method.getName().equals("getConnection")) {
                throw exception;
            }
            return invokeObjectMethod(proxy, method, arguments, "DataSource");
        });
    }

    private static DataSource dataSource(InvocationHandler invocationHandler) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] { DataSource.class },
                invocationHandler);
    }

    private static Object invokeObjectMethod(Object proxy, Method method, Object[] arguments, String objectName) {
        if (method.getDeclaringClass() != Object.class) {
            throw new AssertionError("Unsupported " + objectName + " method: " + method.getName());
        }
        return switch (method.getName()) {
            case "toString" -> objectName + " test proxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> throw new AssertionError("Unsupported Object method: " + method.getName());
        };
    }

    private static final class RecordingConnection implements InvocationHandler {
        private final boolean validationResult;
        private int validationTimeoutSeconds;
        private boolean closed;

        private RecordingConnection(boolean validationResult) {
            this.validationResult = validationResult;
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "isValid" -> validate((Integer) arguments[0]);
                case "close" -> close();
                default -> invokeObjectMethod(proxy, method, arguments, "Connection");
            };
        }

        private boolean validate(int timeoutSeconds) {
            validationTimeoutSeconds = timeoutSeconds;
            return validationResult;
        }

        private Object close() {
            closed = true;
            return null;
        }
    }

    private static final class ResponseCapture implements InvocationHandler {
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
                default -> invokeObjectMethod(proxy, method, arguments, "HttpServletResponse");
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
