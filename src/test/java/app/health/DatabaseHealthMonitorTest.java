package app.health;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

final class DatabaseHealthMonitorTest {
    private static final Instant TEST_START = Instant.parse("2026-07-27T10:00:00Z");

    @Test
    void healthyResultIsCachedAndTheValidationConnectionIsClosed() {
        AtomicInteger connectionRequestCount = new AtomicInteger();
        RecordingConnection recordingConnection = new RecordingConnection(true);
        DatabaseHealthMonitor databaseHealthMonitor = databaseHealthMonitor(
                dataSourceReturning(connectionRequestCount, recordingConnection.connection()),
                Clock.fixed(TEST_START, ZoneOffset.UTC));

        assertTrue(databaseHealthMonitor.isDatabaseUsable());
        assertTrue(databaseHealthMonitor.isDatabaseUsable());

        assertAll(
                () -> assertEquals(1, connectionRequestCount.get()),
                () -> assertEquals(1, recordingConnection.validationCount.get()),
                () -> assertEquals(
                        DatabaseHealthMonitor.DATABASE_VALIDATION_TIMEOUT_SECONDS,
                        recordingConnection.validationTimeoutSeconds),
                () -> assertTrue(recordingConnection.closed));
    }

    @Test
    void unavailableResultIsCachedUntilTheExactExpirationBoundary() {
        MutableClock clock = new MutableClock(TEST_START);
        AtomicInteger connectionRequestCount = new AtomicInteger();
        List<String> warningMessages = new ArrayList<>();
        RecordingConnection unavailableConnection = new RecordingConnection(false);
        RecordingConnection availableConnection = new RecordingConnection(true);
        DataSource dataSource = dataSource((proxy, method, arguments) -> {
            if (method.getName().equals("getConnection")) {
                int requestNumber = connectionRequestCount.getAndIncrement();
                return requestNumber == 0
                        ? unavailableConnection.connection()
                        : availableConnection.connection();
            }
            return invokeObjectMethod(proxy, method, arguments, "DataSource");
        });
        DatabaseHealthMonitor databaseHealthMonitor = databaseHealthMonitor(
                dataSource,
                clock,
                warningMessages);

        assertFalse(databaseHealthMonitor.isDatabaseUsable());
        clock.advance(DatabaseHealthMonitor.RESULT_CACHE_DURATION.minusMillis(1));
        assertFalse(databaseHealthMonitor.isDatabaseUsable());
        clock.advance(Duration.ofMillis(1));
        assertTrue(databaseHealthMonitor.isDatabaseUsable());

        assertAll(
                () -> assertEquals(2, connectionRequestCount.get()),
                () -> assertEquals(1, warningMessages.size()),
                () -> assertTrue(warningMessages.getFirst()
                        .contains("failure_type=invalid_connection")),
                () -> assertTrue(unavailableConnection.closed),
                () -> assertTrue(availableConnection.closed));
    }

    @Test
    void repeatedInvalidConnectionsUseTheExactFiveMinuteWarningBoundary() {
        MutableClock clock = new MutableClock(TEST_START);
        AtomicInteger connectionRequestCount = new AtomicInteger();
        RecordingConnection invalidConnection = new RecordingConnection(false);
        List<String> warningMessages = new ArrayList<>();
        Duration testCacheDuration = Duration.ofNanos(1);
        DatabaseHealthMonitor databaseHealthMonitor = new DatabaseHealthMonitor(
                dataSourceReturning(
                        connectionRequestCount,
                        invalidConnection.connection()),
                clock,
                testCacheDuration,
                warningMessages::add);

        assertFalse(databaseHealthMonitor.isDatabaseUsable());
        clock.advance(testCacheDuration);
        assertFalse(databaseHealthMonitor.isDatabaseUsable());
        clock.advance(DatabaseHealthMonitor.FAILURE_WARNING_INTERVAL
                .minus(testCacheDuration)
                .minusNanos(1));
        assertFalse(databaseHealthMonitor.isDatabaseUsable());

        assertAll(
                () -> assertEquals(1, warningMessages.size()),
                () -> assertTrue(warningMessages.getFirst()
                        .contains("failure_type=invalid_connection")));

        clock.advance(testCacheDuration);
        assertFalse(databaseHealthMonitor.isDatabaseUsable());

        assertAll(
                () -> assertEquals(2, warningMessages.size()),
                () -> assertTrue(warningMessages.getLast()
                        .endsWith("suppressed_failures=2")),
                () -> assertEquals(4, connectionRequestCount.get()));
    }

    @Test
    void sqlFailureIsCachedWithoutLeakingOrRepeatedConnectionPressure() {
        MutableClock clock = new MutableClock(TEST_START);
        AtomicInteger connectionRequestCount = new AtomicInteger();
        SQLException sensitiveFailure = new SQLException("password=secret-password");
        List<String> warningMessages = new ArrayList<>();
        DatabaseHealthMonitor databaseHealthMonitor = databaseHealthMonitor(
                dataSourceThrowing(connectionRequestCount, sensitiveFailure),
                clock,
                warningMessages);

        assertFalse(databaseHealthMonitor.isDatabaseUsable());
        assertFalse(databaseHealthMonitor.isDatabaseUsable());
        assertAll(
                () -> assertEquals(1, connectionRequestCount.get()),
                () -> assertEquals(1, warningMessages.size()),
                () -> assertFalse(warningMessages.getFirst().contains("secret-password")),
                () -> assertFalse(warningMessages.getFirst().contains(sensitiveFailure.getMessage())));

        clock.advance(DatabaseHealthMonitor.RESULT_CACHE_DURATION);
        assertFalse(databaseHealthMonitor.isDatabaseUsable());
        assertAll(
                () -> assertEquals(2, connectionRequestCount.get()),
                () -> assertEquals(1, warningMessages.size()));
    }

    @Test
    void uncheckedDataSourceFailureBecomesACachedUnhealthyResult() {
        MutableClock clock = new MutableClock(TEST_START);
        AtomicInteger connectionRequestCount = new AtomicInteger();
        IllegalStateException unexpectedFailure = new IllegalStateException(
                "jdbc:postgresql://database/private?password=secret-password");
        RecordingConnection recordingConnection = new RecordingConnection(true);
        List<String> warningMessages = new ArrayList<>();
        DataSource dataSource = dataSource((proxy, method, arguments) -> {
            if (method.getName().equals("getConnection")) {
                if (connectionRequestCount.getAndIncrement() == 0) {
                    throw unexpectedFailure;
                }
                return recordingConnection.connection();
            }
            return invokeObjectMethod(proxy, method, arguments, "DataSource");
        });
        DatabaseHealthMonitor databaseHealthMonitor = databaseHealthMonitor(
                dataSource,
                clock,
                warningMessages);

        assertFalse(databaseHealthMonitor.isDatabaseUsable());
        assertFalse(databaseHealthMonitor.isDatabaseUsable());

        assertAll(
                () -> assertEquals(1, connectionRequestCount.get()),
                () -> assertEquals(1, warningMessages.size()),
                () -> assertTrue(warningMessages.getFirst()
                        .contains("failure_type=java.lang.IllegalStateException")),
                () -> assertFalse(warningMessages.getFirst().contains("secret-password")),
                () -> assertFalse(warningMessages.getFirst().contains(unexpectedFailure.getMessage())));

        clock.advance(DatabaseHealthMonitor.RESULT_CACHE_DURATION);
        assertTrue(databaseHealthMonitor.isDatabaseUsable());

        assertAll(
                () -> assertEquals(2, connectionRequestCount.get()),
                () -> assertEquals(1, warningMessages.size()),
                () -> assertTrue(recordingConnection.closed));
    }

    @Test
    void repeatedFailuresProduceOneSecretFreeWarningPerBoundedWindow() {
        MutableClock clock = new MutableClock(TEST_START);
        AtomicInteger connectionRequestCount = new AtomicInteger();
        String sensitiveFailureText = "username=calendar password=secret-password";
        RuntimeException sensitiveFailure = new IllegalStateException(sensitiveFailureText);
        List<String> warningMessages = new ArrayList<>();
        DatabaseHealthMonitor databaseHealthMonitor = databaseHealthMonitor(
                dataSourceThrowing(connectionRequestCount, sensitiveFailure),
                clock,
                warningMessages);

        assertFalse(databaseHealthMonitor.isDatabaseUsable());
        int suppressedFailureCount = 5;
        for (int failureIndex = 0; failureIndex < suppressedFailureCount; failureIndex++) {
            clock.advance(DatabaseHealthMonitor.RESULT_CACHE_DURATION);
            assertFalse(databaseHealthMonitor.isDatabaseUsable());
        }

        assertAll(
                () -> assertEquals(suppressedFailureCount + 1, connectionRequestCount.get()),
                () -> assertEquals(1, warningMessages.size()),
                () -> assertTrue(warningMessages.getFirst().endsWith("suppressed_failures=0")));

        clock.advance(DatabaseHealthMonitor.FAILURE_WARNING_INTERVAL
                .minus(DatabaseHealthMonitor.RESULT_CACHE_DURATION.multipliedBy(suppressedFailureCount)));
        assertFalse(databaseHealthMonitor.isDatabaseUsable());

        assertAll(
                () -> assertEquals(suppressedFailureCount + 2, connectionRequestCount.get()),
                () -> assertEquals(2, warningMessages.size()),
                () -> assertTrue(warningMessages.getLast()
                        .endsWith("suppressed_failures=" + suppressedFailureCount)),
                () -> assertTrue(warningMessages.stream()
                        .noneMatch(message -> message.contains(sensitiveFailureText))),
                () -> assertTrue(warningMessages.stream()
                        .noneMatch(message -> message.contains("secret-password"))));
    }

    @Test
    void diagnosticFailureCannotEscapeOrDisableUnhealthyResultCaching() {
        MutableClock clock = new MutableClock(TEST_START);
        AtomicInteger connectionRequestCount = new AtomicInteger();
        RuntimeException dataSourceFailure = new IllegalStateException(
                "password=secret-password");
        RuntimeException diagnosticFailure = new IllegalStateException(
                "Simulated diagnostic failure.");
        DatabaseHealthMonitor databaseHealthMonitor = new DatabaseHealthMonitor(
                dataSourceThrowing(connectionRequestCount, dataSourceFailure),
                clock,
                DatabaseHealthMonitor.RESULT_CACHE_DURATION,
                ignoredMessage -> {
                    throw diagnosticFailure;
                });

        assertFalse(databaseHealthMonitor.isDatabaseUsable());
        assertFalse(databaseHealthMonitor.isDatabaseUsable());

        assertAll(
                () -> assertEquals(1, connectionRequestCount.get()),
                () -> assertEquals(1, dataSourceFailure.getSuppressed().length),
                () -> assertEquals(diagnosticFailure, dataSourceFailure.getSuppressed()[0]));
    }

    @Test
    void hostileConcurrentRequestsShareOneInFlightValidation() throws Exception {
        int concurrentRequestCount = 64;
        AtomicInteger connectionRequestCount = new AtomicInteger();
        CountDownLatch validationStarted = new CountDownLatch(1);
        CountDownLatch continueValidation = new CountDownLatch(1);
        RecordingConnection recordingConnection = new RecordingConnection(
                true,
                validationStarted,
                continueValidation);
        DatabaseHealthMonitor databaseHealthMonitor = databaseHealthMonitor(
                dataSourceReturning(connectionRequestCount, recordingConnection.connection()),
                Clock.fixed(TEST_START, ZoneOffset.UTC));
        CountDownLatch requestsReady = new CountDownLatch(concurrentRequestCount);
        CountDownLatch startRequests = new CountDownLatch(1);

        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> requestResults = new ArrayList<>();
            for (int requestIndex = 0; requestIndex < concurrentRequestCount; requestIndex++) {
                requestResults.add(executorService.submit(() -> {
                    requestsReady.countDown();
                    startRequests.await();
                    return databaseHealthMonitor.isDatabaseUsable();
                }));
            }

            try {
                assertTrue(requestsReady.await(10, TimeUnit.SECONDS));
                startRequests.countDown();
                assertTrue(validationStarted.await(10, TimeUnit.SECONDS));
                assertEquals(1, connectionRequestCount.get());
            } finally {
                startRequests.countDown();
                continueValidation.countDown();
            }

            for (Future<Boolean> requestResult : requestResults) {
                assertTrue(requestResult.get(10, TimeUnit.SECONDS));
            }
        }

        assertAll(
                () -> assertEquals(1, connectionRequestCount.get()),
                () -> assertEquals(1, recordingConnection.validationCount.get()),
                () -> assertTrue(recordingConnection.closed));
    }

    private static DatabaseHealthMonitor databaseHealthMonitor(DataSource dataSource, Clock clock) {
        return new DatabaseHealthMonitor(
                dataSource,
                clock,
                DatabaseHealthMonitor.RESULT_CACHE_DURATION);
    }

    private static DatabaseHealthMonitor databaseHealthMonitor(
            DataSource dataSource,
            Clock clock,
            List<String> warningMessages) {
        return new DatabaseHealthMonitor(
                dataSource,
                clock,
                DatabaseHealthMonitor.RESULT_CACHE_DURATION,
                warningMessages::add);
    }

    private static DataSource dataSourceReturning(
            AtomicInteger connectionRequestCount,
            Connection connection) {
        return dataSource((proxy, method, arguments) -> {
            if (method.getName().equals("getConnection")) {
                connectionRequestCount.incrementAndGet();
                return connection;
            }
            return invokeObjectMethod(proxy, method, arguments, "DataSource");
        });
    }

    private static DataSource dataSourceThrowing(
            AtomicInteger connectionRequestCount,
            Throwable failure) {
        return dataSource((proxy, method, arguments) -> {
            if (method.getName().equals("getConnection")) {
                connectionRequestCount.incrementAndGet();
                throw failure;
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
        private final CountDownLatch validationStarted;
        private final CountDownLatch continueValidation;
        private final AtomicInteger validationCount = new AtomicInteger();
        private volatile int validationTimeoutSeconds;
        private volatile boolean closed;

        private RecordingConnection(boolean validationResult) {
            this(validationResult, null, null);
        }

        private RecordingConnection(
                boolean validationResult,
                CountDownLatch validationStarted,
                CountDownLatch continueValidation) {
            this.validationResult = validationResult;
            this.validationStarted = validationStarted;
            this.continueValidation = continueValidation;
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
            validationCount.incrementAndGet();
            validationTimeoutSeconds = timeoutSeconds;
            if (validationStarted != null) {
                validationStarted.countDown();
            }
            if (continueValidation != null) {
                awaitValidationRelease();
            }
            return validationResult;
        }

        private void awaitValidationRelease() {
            try {
                if (!continueValidation.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release the database validation.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Database validation was interrupted.", exception);
            }
        }

        private Object close() {
            closed = true;
            return null;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant currentInstant;

        private MutableClock(Instant currentInstant) {
            this.currentInstant = currentInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }

        private void advance(Duration duration) {
            currentInstant = currentInstant.plus(duration);
        }
    }
}
