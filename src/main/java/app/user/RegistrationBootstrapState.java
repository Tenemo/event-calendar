package app.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "app_registration_bootstrap")
public class RegistrationBootstrapState {
    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "singleton_id", nullable = false)
    private Short singletonId;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    public Short getSingletonId() {
        return singletonId;
    }

    public OffsetDateTime getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(OffsetDateTime consumedAt) {
        this.consumedAt = consumedAt;
    }
}
