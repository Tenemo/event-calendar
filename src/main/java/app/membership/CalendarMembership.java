package app.membership;

import app.calendar.Calendar;
import app.user.ApplicationUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "calendar_membership")
@IdClass(CalendarMembershipId.class)
public class CalendarMembership {
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "calendar_id", nullable = false)
    private Calendar calendar;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private ApplicationUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", nullable = false, length = 20)
    private CalendarRole role;

    public void setCalendar(Calendar calendar) {
        this.calendar = calendar;
    }

    public ApplicationUser getUser() {
        return user;
    }

    public void setUser(ApplicationUser user) {
        this.user = user;
    }

    public CalendarRole getRole() {
        return role;
    }

    public void setRole(CalendarRole role) {
        this.role = role;
    }

}
