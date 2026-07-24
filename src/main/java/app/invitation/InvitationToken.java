package app.invitation;

/** Canonical validation shared by invitation persistence, lookup, and redirects. */
public final class InvitationToken {
    public static final int MINIMUM_BOOTSTRAP_SECRET_LENGTH = 43;
    public static final int MAXIMUM_LENGTH = 80;

    private InvitationToken() {
    }

    public static String normalize(String invitationToken) {
        return invitationToken == null ? "" : invitationToken.trim();
    }

    public static boolean isValidCandidate(String normalizedInvitationToken) {
        return normalizedInvitationToken != null
                && !normalizedInvitationToken.isBlank()
                && normalizedInvitationToken.length() <= MAXIMUM_LENGTH
                && normalizedInvitationToken.indexOf('\\') < 0
                && normalizedInvitationToken.codePoints().noneMatch(Character::isISOControl);
    }

    public static boolean isValidBootstrapSecret(String normalizedInvitationToken) {
        return normalizedInvitationToken != null
                && normalizedInvitationToken.length() >= MINIMUM_BOOTSTRAP_SECRET_LENGTH
                && normalizedInvitationToken.length() <= MAXIMUM_LENGTH
                && normalizedInvitationToken.chars()
                        .allMatch(character -> Character.isLetterOrDigit(character)
                                || character == '-'
                                || character == '_');
    }
}
