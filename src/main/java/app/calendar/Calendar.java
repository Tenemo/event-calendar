package app.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "calendar")
public class Calendar {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String name;

    private String description;

    @Column(name = "calendar_link_token", nullable = false, unique = true, length = CalendarLinkToken.ENCODED_LENGTH)
    private String calendarLinkToken;

    @Column(name = "time_zone", nullable = false, length = 80)
    private String timeZone = "Europe/Warsaw";

    @Column(name = "public_access_enabled", nullable = false)
    private boolean publicAccessEnabled = true;

    @Version
    @Column(nullable = false)
    private int version;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCalendarLinkToken() {
        return calendarLinkToken;
    }

    public void setCalendarLinkToken(String calendarLinkToken) {
        this.calendarLinkToken = calendarLinkToken;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public boolean isPublicAccessEnabled() {
        return publicAccessEnabled;
    }

    public void setPublicAccessEnabled(boolean publicAccessEnabled) {
        this.publicAccessEnabled = publicAccessEnabled;
    }

    public int getVersion() {
        return version;
    }

}
