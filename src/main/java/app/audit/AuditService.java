package app.audit;

import app.calendar.Calendar;
import app.user.ApplicationUser;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.OffsetDateTime;

@Stateless
public class AuditService {
    @PersistenceContext(unitName = "calendarPersistenceUnit")
    private EntityManager entityManager;

    private Clock clock = Clock.systemUTC();

    public void record(
            ApplicationUser actingUser,
            Calendar calendar,
            String entityType,
            Long entityId,
            String action,
            String details) {
        OffsetDateTime currentTime = OffsetDateTime.now(clock);
        AuditLog auditLog = new AuditLog(
                actingUser,
                calendar,
                entityType,
                entityId,
                action,
                details,
                currentTime);
        entityManager.persist(auditLog);
    }
}
