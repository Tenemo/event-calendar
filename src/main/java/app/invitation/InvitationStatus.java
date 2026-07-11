package app.invitation;

public enum InvitationStatus {
    AVAILABLE,
    USED,
    REVOKED,
    EXPIRED;

    public boolean isRevocable() {
        return this == AVAILABLE;
    }
}
