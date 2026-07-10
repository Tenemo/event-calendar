package app.calendar;

import app.audit.AuditService;
import app.membership.CalendarAccessService;
import app.membership.CalendarMember;
import app.membership.CalendarRole;
import app.security.TokenService;
import app.user.AppUser;
import app.util.NotFoundException;
import app.util.ConflictException;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Stateless
public class CalendarService {
    private static final int MAXIMUM_CALENDAR_NAME_LENGTH = 160;
    private static final int MAXIMUM_TIMEZONE_LENGTH = 80;
    private static final int MAXIMUM_TOKEN_GENERATION_ATTEMPTS = 10;
    private static final String DEFAULT_TIMEZONE_ENVIRONMENT_VARIABLE = "APP_TIMEZONE";

    @PersistenceContext(unitName = "calendarPU")
    private EntityManager entityManager;

    @Inject
    private TokenService tokenService;

    @Inject
    private AuditService auditService;

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private CalendarTimeService calendarTimeService;

    private String defaultTimeZone = System.getenv().getOrDefault(DEFAULT_TIMEZONE_ENVIRONMENT_VARIABLE, "Europe/Warsaw");

    public Calendar createCalendar(AppUser creator, String name) {
        return createCalendar(creator, name, null);
    }

    public Calendar createCalendar(AppUser creator, String name, String description) {
        if (creator == null || creator.getId() == null || !creator.isActive()) {
            throw new ValidationException("An active user is required to create a calendar.");
        }

        String normalizedName = normalizeRequiredText(
                name,
                "Calendar name is required.",
                MAXIMUM_CALENDAR_NAME_LENGTH,
                "Calendar name must be 160 characters or fewer.");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AppUser managedCreator = entityManager.find(AppUser.class, creator.getId());
        if (managedCreator == null || !managedCreator.isActive()) {
            throw new ValidationException("An active user is required to create a calendar.");
        }

        Calendar calendar = new Calendar();
        calendar.setName(normalizedName);
        calendar.setDescription(normalizeOptionalText(description));
        calendar.setPublicToken(generateUniquePublicToken());
        calendar.setTimezone(calendarTimeService.normalizeTimeZone(defaultTimeZone));
        calendar.setPublicAccessEnabled(true);
        calendar.setActive(true);
        calendar.setCreatedByUser(managedCreator);
        calendar.setCreatedAt(now);
        calendar.setUpdatedAt(now);
        entityManager.persist(calendar);

        CalendarMember creatorMembership = new CalendarMember();
        creatorMembership.setCalendar(calendar);
        creatorMembership.setUser(managedCreator);
        creatorMembership.setRole(CalendarRole.ADMIN);
        creatorMembership.setActive(true);
        creatorMembership.setCreatedAt(now);
        creatorMembership.setUpdatedAt(now);
        entityManager.persist(creatorMembership);

        entityManager.flush();
        auditService.record(managedCreator, calendar, "calendar", calendar.getId(), "created", "Calendar created.");
        return calendar;
    }

    public Optional<Calendar> findByPublicToken(String publicToken) {
        if (publicToken == null || publicToken.isBlank()) {
            return Optional.empty();
        }

        try {
            Calendar calendar = entityManager
                    .createQuery(
                            "select calendarEntity from Calendar calendarEntity "
                                    + "where calendarEntity.publicToken = :publicToken "
                                    + "and calendarEntity.publicAccessEnabled = true "
                                    + "and calendarEntity.active = true",
                            Calendar.class)
                    .setParameter("publicToken", publicToken.trim())
                    .getSingleResult();
            return Optional.of(calendar);
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    public Calendar requireActiveCalendar(Long calendarId) {
        Calendar calendar = entityManager.find(Calendar.class, calendarId);
        if (calendar == null || !calendar.isActive()) {
            throw new NotFoundException("Calendar was not found.");
        }
        return calendar;
    }

    public Calendar requireAdminCalendar(AppUser actor, Long calendarId) {
        calendarAccessService.requireCanAdminister(actor, calendarId);
        return requireActiveCalendar(calendarId);
    }

    public List<CalendarMembershipSummary> findCalendarsForUser(AppUser user) {
        if (user == null || user.getId() == null) {
            return List.of();
        }

        return entityManager
                .createQuery(
                        "select new app.calendar.CalendarMembershipSummary("
                                + "calendarMember.calendar.id, calendarMember.calendar.name, "
                                + "calendarMember.role, calendarMember.calendar.publicAccessEnabled) "
                                + "from CalendarMember calendarMember "
                                + "where calendarMember.user.id = :userId "
                                + "and calendarMember.active = true "
                                + "and calendarMember.calendar.active = true "
                                + "order by calendarMember.calendar.name",
                        CalendarMembershipSummary.class)
                .setParameter("userId", user.getId())
                .getResultList();
    }

    public String rotatePublicToken(AppUser actor, Long calendarId) {
        return rotatePublicToken(actor, calendarId, null);
    }

    public String rotatePublicToken(AppUser actor, Long calendarId, Integer expectedVersion) {
        calendarAccessService.requireCanAdminister(actor, calendarId);
        Calendar calendar = requireActiveCalendar(calendarId);
        requireExpectedVersion(calendar, expectedVersion);
        calendar.setPublicToken(generateUniquePublicToken());
        calendar.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(actor, calendar, "calendar", calendar.getId(), "public_token_rotated", "Public token rotated.");
        flushWithConflictMessage();
        return calendar.getPublicToken();
    }

    public Calendar updateCalendarSettings(
            AppUser actor,
            Long calendarId,
            String name,
            String description,
            String timezone,
            boolean publicAccessEnabled) {
        return updateCalendarSettings(actor, calendarId, name, description, timezone, publicAccessEnabled, null);
    }

    public Calendar updateCalendarSettings(
            AppUser actor,
            Long calendarId,
            String name,
            String description,
            String timezone,
            boolean publicAccessEnabled,
            Integer expectedVersion) {
        calendarAccessService.requireCanAdminister(actor, calendarId);
        Calendar calendar = requireActiveCalendar(calendarId);
        requireExpectedVersion(calendar, expectedVersion);
        calendar.setName(normalizeRequiredText(
                name,
                "Calendar name is required.",
                MAXIMUM_CALENDAR_NAME_LENGTH,
                "Calendar name must be 160 characters or fewer."));
        calendar.setDescription(normalizeOptionalText(description));
        String normalizedTimeZone = normalizeRequiredText(
                timezone,
                "Timezone is required.",
                MAXIMUM_TIMEZONE_LENGTH,
                "Timezone must be 80 characters or fewer.");
        calendar.setTimezone(calendarTimeService.normalizeTimeZone(normalizedTimeZone));
        calendar.setPublicAccessEnabled(publicAccessEnabled);
        calendar.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(actor, calendar, "calendar", calendar.getId(), "settings_updated", "Calendar settings updated.");
        flushWithConflictMessage();
        return calendar;
    }

    private void requireExpectedVersion(Calendar calendar, Integer expectedVersion) {
        if (expectedVersion != null && calendar.getVersion() != expectedVersion) {
            throw calendarConflictException();
        }
    }

    private void flushWithConflictMessage() {
        try {
            entityManager.flush();
        } catch (OptimisticLockException exception) {
            throw calendarConflictException();
        }
    }

    private ConflictException calendarConflictException() {
        return new ConflictException("This calendar changed after you opened it. Reload the page and try again.");
    }

    private String generateUniquePublicToken() {
        for (int attempt = 0; attempt < MAXIMUM_TOKEN_GENERATION_ATTEMPTS; attempt++) {
            String token = tokenService.generateToken();
            Long existingCount = entityManager
                    .createQuery(
                            "select count(calendarEntity) from Calendar calendarEntity where calendarEntity.publicToken = :token",
                            Long.class)
                    .setParameter("token", token)
                    .getSingleResult();
            if (existingCount == 0) {
                return token;
            }
        }
        throw new IllegalStateException("Could not generate a unique public token.");
    }

    private String normalizeRequiredText(String value, String blankMessage, int maximumLength, String lengthMessage) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(blankMessage);
        }
        String normalizedValue = value.trim();
        if (normalizedValue.length() > maximumLength) {
            throw new ValidationException(lengthMessage);
        }
        return normalizedValue;
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
