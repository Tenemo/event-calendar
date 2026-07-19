package app.invitation;

import app.calendar.Calendar;
import app.membership.CalendarRole;
import app.user.ApplicationUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "app_invitation")
public class Invitation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calendar_id")
    private Calendar calendar;

    @Column(name = "invite_token", nullable = false, unique = true, length = 80)
    private String invitationToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", length = 20)
    private CalendarRole role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private ApplicationUser createdByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by_user_id")
    private ApplicationUser acceptedByUser;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public Calendar getCalendar() {
        return calendar;
    }

    public void setCalendar(Calendar calendar) {
        this.calendar = calendar;
    }

    public String getInvitationToken() {
        return invitationToken;
    }

    public void setInvitationToken(String invitationToken) {
        this.invitationToken = invitationToken;
    }

    public CalendarRole getRole() {
        return role;
    }

    public void setRole(CalendarRole role) {
        this.role = role;
    }

    public ApplicationUser getCreatedByUser() {
        return createdByUser;
    }

    public void setCreatedByUser(ApplicationUser createdByUser) {
        this.createdByUser = createdByUser;
    }

    public ApplicationUser getAcceptedByUser() {
        return acceptedByUser;
    }

    public void setAcceptedByUser(ApplicationUser acceptedByUser) {
        this.acceptedByUser = acceptedByUser;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public OffsetDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(OffsetDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
