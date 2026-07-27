package app.audit;

import static app.testsupport.ProxyReturnValues.defaultValue;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import jakarta.persistence.EntityManager;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AuditServiceTest {
    @Test
    void persistsOneAuditRecordForEachMutation() {
        List<Object> persistedEntities = new ArrayList<>();
        AuditService auditService = new AuditService();
        setField(auditService, "entityManager", entityManager(persistedEntities));
        setField(
                auditService,
                "clock",
                Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC));

        auditService.record(null, null, "calendar", 1L, "created", "Calendar created.");
        auditService.record(null, null, "event", 2L, "updated", "Event updated.");

        assertEquals(2, persistedEntities.size());
        assertInstanceOf(AuditLog.class, persistedEntities.get(0));
        assertInstanceOf(AuditLog.class, persistedEntities.get(1));
    }

    private static EntityManager entityManager(List<Object> persistedEntities) {
        return (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[] {EntityManager.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("persist")) {
                        persistedEntities.add(arguments[0]);
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }
}
