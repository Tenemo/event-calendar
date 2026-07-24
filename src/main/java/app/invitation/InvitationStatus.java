package app.invitation;

public enum InvitationStatus {
    AVAILABLE,
    ACCEPTED,
    REVOKED,
    EXPIRED;

    public boolean isRevocable() {
        return this == AVAILABLE;
    }
}
