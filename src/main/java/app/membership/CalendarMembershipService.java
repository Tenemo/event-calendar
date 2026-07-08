package app.membership;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.user.AppUser;
import app.user.UserService;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Stateless
public class CalendarMembershipService {
    @PersistenceContext(unitName = "calendarPU")
    private EntityManager entityManager;

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private CalendarService calendarService;

    @Inject
    private UserService userService;

    @Inject
    private AuditService auditService;

    @Inject
    private CalendarMembershipPolicy calendarMembershipPolicy;

    public List<CalendarMember> listMembers(AppUser actor, Long calendarId) {
        calendarAccessService.requireCanAdminister(actor, calendarId);
        return entityManager
                .createQuery(
                        "select calendarMember from CalendarMember calendarMember "
                                + "join fetch calendarMember.user "
                                + "where calendarMember.calendar.id = :calendarId "
                                + "order by calendarMember.user.username",
                        CalendarMember.class)
                .setParameter("calendarId", calendarId)
                .getResultList();
    }

    public CalendarMember grantOrUpdateMembership(Calendar calendar, AppUser user, CalendarRole role) {
        CalendarMember member = findMembership(calendar.getId(), user.getId()).orElseGet(() -> {
            CalendarMember newMember = new CalendarMember();
            newMember.setCalendar(calendar);
            newMember.setUser(user);
            newMember.setCreatedAt(OffsetDateTime.now());
            entityManager.persist(newMember);
            return newMember;
        });

        member.setRole(role);
        member.setActive(true);
        member.setUpdatedAt(OffsetDateTime.now());
        return member;
    }

    public CalendarMember changeMemberRole(AppUser actor, Long calendarId, Long targetUserId, CalendarRole newRole) {
        calendarAccessService.requireCanAdminister(actor, calendarId);
        if (newRole == null) {
            throw new ValidationException("Role is required.");
        }

        CalendarMember member = requireMembership(calendarId, targetUserId);
        if (member.getRole() == CalendarRole.ADMIN && newRole != CalendarRole.ADMIN) {
            calendarMembershipPolicy.requireSafeRoleChange(
                    member.getRole(), newRole, anotherActiveAdminExists(calendarId, targetUserId));
        }

        member.setRole(newRole);
        member.setActive(true);
        member.setUpdatedAt(OffsetDateTime.now());
        auditService.record(actor, member.getCalendar(), "calendar_member", targetUserId, "role_changed", "Member role changed.");
        return member;
    }

    public void disableMember(AppUser actor, Long calendarId, Long targetUserId) {
        calendarAccessService.requireCanAdminister(actor, calendarId);
        CalendarMember member = requireMembership(calendarId, targetUserId);
        if (member.getRole() == CalendarRole.ADMIN) {
            calendarMembershipPolicy.requireSafeDisable(member.getRole(), anotherActiveAdminExists(calendarId, targetUserId));
        }

        member.setActive(false);
        member.setUpdatedAt(OffsetDateTime.now());
        auditService.record(actor, member.getCalendar(), "calendar_member", targetUserId, "disabled", "Member access disabled.");
    }

    public CalendarMember addMemberByRole(Long calendarId, Long userId, CalendarRole role) {
        Calendar calendar = calendarService.requireActiveCalendar(calendarId);
        AppUser user = userService.requireActiveUser(userId);
        return grantOrUpdateMembership(calendar, user, role);
    }

    public Optional<CalendarMember> findMembership(Long calendarId, Long userId) {
        try {
            CalendarMember member = entityManager
                    .createQuery(
                            "select calendarMember from CalendarMember calendarMember "
                                    + "where calendarMember.calendar.id = :calendarId and calendarMember.user.id = :userId",
                            CalendarMember.class)
                    .setParameter("calendarId", calendarId)
                    .setParameter("userId", userId)
                    .getSingleResult();
            return Optional.of(member);
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    private CalendarMember requireMembership(Long calendarId, Long userId) {
        return findMembership(calendarId, userId)
                .orElseThrow(() -> new NotFoundException("Calendar membership was not found."));
    }

    private boolean anotherActiveAdminExists(Long calendarId, Long targetUserId) {
        Long otherAdminCount = entityManager
                .createQuery(
                        "select count(calendarMember) from CalendarMember calendarMember "
                                + "where calendarMember.calendar.id = :calendarId "
                                + "and calendarMember.user.id <> :targetUserId "
                                + "and calendarMember.role = :adminRole "
                                + "and calendarMember.active = true",
                        Long.class)
                .setParameter("calendarId", calendarId)
                .setParameter("targetUserId", targetUserId)
                .setParameter("adminRole", CalendarRole.ADMIN)
                .getSingleResult();
        return otherAdminCount > 0;
    }
}
