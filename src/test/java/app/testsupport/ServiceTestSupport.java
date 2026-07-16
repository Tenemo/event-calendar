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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

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
        private final List<String> lockedQueryTexts = new ArrayList<>();
        private final List<String> maximumResultLimitedQueryTexts = new ArrayList<>();
        private final List<QueryPagination> queryPaginations = new ArrayList<>();
        private final List<Object> persistedObjects = new ArrayList<>();
        private final List<Object> removedObjects = new ArrayList<>();
        private final Map<String, QueryBehavior> queryBehaviors = new LinkedHashMap<>();
        private RuntimeException flushException;
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

        public List<String> lockedQueryTexts() {
            return lockedQueryTexts;
        }

        public List<String> maximumResultLimitedQueryTexts() {
            return maximumResultLimitedQueryTexts;
        }

        public List<QueryPagination> queryPaginations() {
            return queryPaginations;
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

        public EntityManagerStub singleResult(String queryTextFragment, Object result) {
            queryBehaviors.put(queryTextFragment, QueryBehavior.singleResult(result));
            return this;
        }

        public EntityManagerStub singleResultNotFound(String queryTextFragment) {
            queryBehaviors.put(queryTextFragment, QueryBehavior.singleResultException(new NoResultException()));
            return this;
        }

        public EntityManagerStub resultList(String queryTextFragment, List<?> result) {
            queryBehaviors.put(queryTextFragment, QueryBehavior.resultList(result));
            return this;
        }

        public EntityManagerStub failOnFlush(RuntimeException exception) {
            flushException = Objects.requireNonNull(exception);
            return this;
        }

        private Object invokeEntityManager(Object proxy, Method method, Object[] arguments) {
            String methodName = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return invokeObjectMethod(proxy, methodName, arguments, "EntityManager");
            }
            if (methodName.equals("find")) {
                return findResults.get(new FindKey((Class<?>) arguments[0], arguments[1]));
            }
            if (methodName.equals("persist")) {
                persistedObjects.add(arguments[0]);
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
            InvocationHandler queryHandler = (proxy, method, arguments) -> {
                String methodName = method.getName();
                if (method.getDeclaringClass() == Object.class) {
                    return invokeObjectMethod(proxy, methodName, arguments, "TypedQuery");
                }
                if (methodName.equals("setParameter")) {
                    return proxy;
                }
                if (methodName.equals("setLockMode")) {
                    if (arguments != null
                            && arguments.length == 1
                            && arguments[0] == LockModeType.PESSIMISTIC_WRITE) {
                        lockedQueryTexts.add(queryText);
                    }
                    return proxy;
                }
                if (methodName.equals("setMaxResults")) {
                    maximumResultLimitedQueryTexts.add(queryText);
                    maximumResults.set((Integer) arguments[0]);
                    return proxy;
                }
                if (methodName.equals("setFirstResult")) {
                    firstResult.set((Integer) arguments[0]);
                    return proxy;
                }
                if (methodName.equals("getSingleResult")) {
                    return matchingBehavior(queryText).singleResult();
                }
                if (methodName.equals("getResultList")) {
                    queryPaginations.add(new QueryPagination(
                            queryText,
                            firstResult.get(),
                            maximumResults.get()));
                    return matchingBehavior(queryText).resultList();
                }
                throw new AssertionError("Unsupported TypedQuery method: " + methodName);
            };

            return (TypedQuery<?>) Proxy.newProxyInstance(
                    TypedQuery.class.getClassLoader(),
                    new Class<?>[] { TypedQuery.class },
                    queryHandler);
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

    public record QueryPagination(String queryText, int firstResult, int maximumResults) {
    }

    private static final class QueryBehavior {
        private final Object singleResult;
        private final RuntimeException singleResultException;
        private final List<?> resultList;

        private QueryBehavior(Object singleResult, RuntimeException singleResultException, List<?> resultList) {
            this.singleResult = singleResult;
            this.singleResultException = singleResultException;
            this.resultList = resultList;
        }

        static QueryBehavior singleResult(Object result) {
            return new QueryBehavior(result, null, List.of());
        }

        static QueryBehavior singleResultException(RuntimeException exception) {
            return new QueryBehavior(null, Objects.requireNonNull(exception), List.of());
        }

        static QueryBehavior resultList(List<?> result) {
            return new QueryBehavior(null, null, result);
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
