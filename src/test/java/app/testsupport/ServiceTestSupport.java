package app.testsupport;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class ServiceTestSupport {
    private ServiceTestSupport() {
    }

    public static void setField(Object target, String fieldName, Object value) {
        Class<?> currentType = target.getClass();
        while (currentType != null) {
            try {
                java.lang.reflect.Field field = currentType.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException exception) {
                currentType = currentType.getSuperclass();
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Could not set field " + fieldName + ".", exception);
            }
        }

        throw new IllegalArgumentException("Field " + fieldName + " was not found.");
    }

    public static void setEntityId(Object entity, Long id) {
        setField(entity, "id", id);
    }

    public static EntityManagerStub entityManagerStub() {
        return new EntityManagerStub();
    }

    public static final class EntityManagerStub {
        private final EntityManager entityManager;
        private final Map<FindKey, Object> findResults = new HashMap<>();
        private final List<FindLock> findLocks = new ArrayList<>();
        private final List<Object> refreshedObjects = new ArrayList<>();
        private final List<String> lockedQueryTexts = new ArrayList<>();
        private final List<String> maximumResultLimitedQueryTexts = new ArrayList<>();
        private final List<QueryPagination> queryPaginations = new ArrayList<>();
        private final List<QueryExecution> queryExecutions = new ArrayList<>();
        private final List<Object> persistedObjects = new ArrayList<>();
        private final List<Object> removedObjects = new ArrayList<>();
        private final Map<String, QueryBehavior> queryBehaviors = new LinkedHashMap<>();
        private RuntimeException flushException;
        private Consumer<Object> refreshOperation = ignored -> { };
        private long nextGeneratedId = 1L;
        private int flushCount;

        private EntityManagerStub() {
            entityManager = (EntityManager) Proxy.newProxyInstance(
                    EntityManager.class.getClassLoader(),
                    new Class<?>[] { EntityManager.class },
                    this::invokeEntityManager);
        }

        public EntityManager entityManager() {
            return entityManager;
        }

        public List<Object> persistedObjects() {
            return persistedObjects;
        }

        public List<FindLock> findLocks() {
            return findLocks;
        }

        public List<Object> refreshedObjects() {
            return refreshedObjects;
        }

        public List<String> lockedQueryTexts() {
            return lockedQueryTexts;
        }

        public List<String> maximumResultLimitedQueryTexts() {
            return maximumResultLimitedQueryTexts;
        }

        public List<QueryPagination> queryPaginations() {
            return queryPaginations;
        }

        public List<QueryExecution> queryExecutions() {
            return queryExecutions;
        }

        public List<Object> removedObjects() {
            return removedObjects;
        }

        public int flushCount() {
            return flushCount;
        }

        public EntityManagerStub find(Class<?> entityType, Object id, Object result) {
            findResults.put(new FindKey(entityType, id), result);
            return this;
        }

        public EntityManagerStub singleResult(
                String queryTextFragment,
                Object result,
                QueryParameter... expectedParameters) {
            queryBehaviors.put(
                    queryTextFragment,
                    QueryBehavior.singleResult(result, expectedParameterMap(expectedParameters)));
            return this;
        }

        public EntityManagerStub singleResultNotFound(
                String queryTextFragment,
                QueryParameter... expectedParameters) {
            queryBehaviors.put(
                    queryTextFragment,
                    QueryBehavior.singleResultException(
                            new NoResultException(),
                            expectedParameterMap(expectedParameters)));
            return this;
        }

        public EntityManagerStub resultList(
                String queryTextFragment,
                List<?> result,
                QueryParameter... expectedParameters) {
            queryBehaviors.put(
                    queryTextFragment,
                    QueryBehavior.resultList(result, expectedParameterMap(expectedParameters)));
            return this;
        }

        private Map<String, Object> expectedParameterMap(QueryParameter[] expectedParameters) {
            Objects.requireNonNull(expectedParameters, "Expected query parameters are required.");
            Map<String, Object> parameterValues = new LinkedHashMap<>();
            for (QueryParameter expectedParameter : expectedParameters) {
                Objects.requireNonNull(expectedParameter, "Expected query parameter is required.");
                parameterValues.put(expectedParameter.name(), expectedParameter.value());
            }
            return immutableParameterMap(parameterValues);
        }

        public EntityManagerStub failOnFlush(RuntimeException exception) {
            flushException = Objects.requireNonNull(exception);
            return this;
        }

        public EntityManagerStub whenRefreshed(Consumer<Object> operation) {
            refreshOperation = Objects.requireNonNull(operation);
            return this;
        }

        private Object invokeEntityManager(Object proxy, Method method, Object[] arguments) {
            String methodName = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return invokeObjectMethod(proxy, methodName, arguments, "EntityManager");
            }
            if (methodName.equals("find")) {
                if (arguments.length >= 3 && arguments[2] instanceof LockModeType lockMode) {
                    findLocks.add(new FindLock((Class<?>) arguments[0], arguments[1], lockMode));
                }
                return findResults.get(new FindKey((Class<?>) arguments[0], arguments[1]));
            }
            if (methodName.equals("persist")) {
                persistedObjects.add(arguments[0]);
                return null;
            }
            if (methodName.equals("refresh")) {
                refreshedObjects.add(arguments[0]);
                refreshOperation.accept(arguments[0]);
                return null;
            }
            if (methodName.equals("remove")) {
                removedObjects.add(arguments[0]);
                return null;
            }
            if (methodName.equals("flush")) {
                flushCount++;
                if (flushException != null) {
                    throw flushException;
                }
                assignMissingGeneratedIds();
                return null;
            }
            if (methodName.equals("createQuery") && arguments != null && arguments.length >= 1 && arguments[0] instanceof String queryText) {
                return createTypedQuery(queryText);
            }
            throw new AssertionError("Unsupported EntityManager method: " + methodName);
        }

        private void assignMissingGeneratedIds() {
            for (Object persistedObject : persistedObjects) {
                Field idField = findField(persistedObject.getClass(), "id");
                if (idField == null || !idField.getType().equals(Long.class)) {
                    continue;
                }

                try {
                    idField.setAccessible(true);
                    if (idField.get(persistedObject) == null) {
                        idField.set(persistedObject, nextGeneratedId++);
                    }
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException("Could not assign generated id.", exception);
                }
            }
        }

        private Field findField(Class<?> type, String fieldName) {
            Class<?> currentType = type;
            while (currentType != null) {
                try {
                    return currentType.getDeclaredField(fieldName);
                } catch (NoSuchFieldException exception) {
                    currentType = currentType.getSuperclass();
                }
            }
            return null;
        }

        private TypedQuery<?> createTypedQuery(String queryText) {
            AtomicInteger firstResult = new AtomicInteger();
            AtomicInteger maximumResults = new AtomicInteger(Integer.MAX_VALUE);
            Map<String, Object> parameterValues = new LinkedHashMap<>();
            InvocationHandler queryHandler = (proxy, method, arguments) -> {
                String methodName = method.getName();
                if (method.getDeclaringClass() == Object.class) {
                    return invokeObjectMethod(proxy, methodName, arguments, "TypedQuery");
                }
                if (methodName.equals("setParameter")) {
                    recordParameterBinding(queryText, parameterValues, arguments);
                    return proxy;
                }
                if (methodName.equals("setLockMode")) {
                    if (arguments != null
                            && arguments.length == 1
                            && (arguments[0] == LockModeType.PESSIMISTIC_WRITE
                                    || arguments[0] == LockModeType.PESSIMISTIC_READ)) {
                        lockedQueryTexts.add(queryText);
                    }
                    return proxy;
                }
                if (methodName.equals("setMaxResults")) {
                    int maximumResultCount = requireNonNegativePaginationValue(
                            "Maximum query result count", arguments);
                    maximumResultLimitedQueryTexts.add(queryText);
                    maximumResults.set(maximumResultCount);
                    return proxy;
                }
                if (methodName.equals("setFirstResult")) {
                    firstResult.set(requireNonNegativePaginationValue(
                            "First query result", arguments));
                    return proxy;
                }
                if (methodName.equals("getSingleResult")) {
                    QueryBehavior queryBehavior = matchingBehavior(queryText);
                    recordAndValidateQueryExecution(
                            queryText,
                            parameterValues,
                            firstResult.get(),
                            maximumResults.get(),
                            queryBehavior);
                    return queryBehavior.singleResult();
                }
                if (methodName.equals("getResultList")) {
                    queryPaginations.add(new QueryPagination(
                            queryText,
                            firstResult.get(),
                            maximumResults.get()));
                    QueryBehavior queryBehavior = matchingBehavior(queryText);
                    recordAndValidateQueryExecution(
                            queryText,
                            parameterValues,
                            firstResult.get(),
                            maximumResults.get(),
                            queryBehavior);
                    return queryBehavior.resultList();
                }
                throw new AssertionError("Unsupported TypedQuery method: " + methodName);
            };

            return (TypedQuery<?>) Proxy.newProxyInstance(
                    TypedQuery.class.getClassLoader(),
                    new Class<?>[] { TypedQuery.class },
                    queryHandler);
        }

        private void recordParameterBinding(
                String queryText,
                Map<String, Object> parameterValues,
                Object[] arguments) {
            if (arguments == null
                    || arguments.length != 2
                    || !(arguments[0] instanceof String parameterName)) {
                throw new AssertionError(
                        "Only two-argument named query parameter bindings are supported for query: "
                                + queryText);
            }
            if (parameterName.isBlank()) {
                throw new AssertionError("Query parameter name must not be blank for query: " + queryText);
            }
            parameterValues.put(parameterName, arguments[1]);
        }

        private int requireNonNegativePaginationValue(String valueDescription, Object[] arguments) {
            if (arguments == null || arguments.length != 1 || !(arguments[0] instanceof Integer value)) {
                throw new AssertionError(valueDescription + " must be supplied as an integer.");
            }
            if (value < 0) {
                throw new IllegalArgumentException(valueDescription + " must not be negative.");
            }
            return value;
        }

        private void recordAndValidateQueryExecution(
                String queryText,
                Map<String, Object> parameterValues,
                int firstResult,
                int maximumResults,
                QueryBehavior queryBehavior) {
            QueryExecution queryExecution = new QueryExecution(
                    queryText,
                    parameterValues,
                    firstResult,
                    maximumResults);
            queryExecutions.add(queryExecution);
            queryBehavior.validateParameters(queryText, queryExecution.parameterValues());
        }

        private QueryBehavior matchingBehavior(String queryText) {
            for (Map.Entry<String, QueryBehavior> entry : queryBehaviors.entrySet()) {
                if (queryText.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            throw new AssertionError("No query behavior matched query: " + queryText);
        }

        private Object invokeObjectMethod(Object proxy, String methodName, Object[] arguments, String objectName) {
            return switch (methodName) {
                case "toString" -> objectName + " test proxy";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> throw new AssertionError("Unsupported Object method: " + methodName);
            };
        }
    }

    private record FindKey(Class<?> entityType, Object id) {
    }

    public record FindLock(Class<?> entityType, Object id, LockModeType lockMode) {
    }

    public record QueryPagination(String queryText, int firstResult, int maximumResults) {
    }

    public record QueryExecution(
            String queryText,
            Map<String, Object> parameterValues,
            int firstResult,
            int maximumResults) {
        public QueryExecution {
            parameterValues = immutableParameterMap(parameterValues);
        }
    }

    public record QueryParameter(String name, Object value) {
        public QueryParameter {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Expected query parameter name must not be blank.");
            }
        }
    }

    public static QueryParameter queryParameter(String name, Object value) {
        return new QueryParameter(name, value);
    }

    private static Map<String, Object> immutableParameterMap(Map<String, Object> parameterValues) {
        Objects.requireNonNull(parameterValues, "Query parameter values are required.");
        return Collections.unmodifiableMap(new LinkedHashMap<>(parameterValues));
    }

    private static final class QueryBehavior {
        private final Object singleResult;
        private final RuntimeException singleResultException;
        private final List<?> resultList;
        private final Map<String, Object> expectedParameters;

        private QueryBehavior(
                Object singleResult,
                RuntimeException singleResultException,
                List<?> resultList,
                Map<String, Object> expectedParameters) {
            this.singleResult = singleResult;
            this.singleResultException = singleResultException;
            this.resultList = resultList;
            this.expectedParameters = expectedParameters;
        }

        static QueryBehavior singleResult(Object result, Map<String, Object> expectedParameters) {
            return new QueryBehavior(result, null, List.of(), expectedParameters);
        }

        static QueryBehavior singleResultException(
                RuntimeException exception,
                Map<String, Object> expectedParameters) {
            return new QueryBehavior(
                    null,
                    Objects.requireNonNull(exception),
                    List.of(),
                    expectedParameters);
        }

        static QueryBehavior resultList(List<?> result, Map<String, Object> expectedParameters) {
            return new QueryBehavior(null, null, result, expectedParameters);
        }

        void validateParameters(String queryText, Map<String, Object> parameterValues) {
            if (!expectedParameters.equals(parameterValues)) {
                throw new AssertionError(
                        "Expected query parameters "
                                + expectedParameters
                                + " but received "
                                + parameterValues
                                + " for query: "
                                + queryText);
            }
        }

        Object singleResult() {
            if (singleResultException != null) {
                throw singleResultException;
            }
            return singleResult;
        }

        List<?> resultList() {
            return resultList;
        }
    }
}
