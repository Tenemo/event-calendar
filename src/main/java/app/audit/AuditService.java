package app.audit;

import app.calendar.Calendar;
import app.user.ApplicationUser;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Stateless
public class AuditService {
    @PersistenceContext(unitName = "calendarPU")
    private EntityManager entityManager;

    public void record(ApplicationUser actingUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
        AuditLog auditLog = new AuditLog();
        auditLog.setActingUser(actingUser);
        auditLog.setCalendar(calendar);
        auditLog.setEntityType(entityType);
        auditLog.setEntityId(entityId);
        auditLog.setAction(action);
        auditLog.setDetails(details);
        auditLog.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entityManager.persist(auditLog);
    }
}
