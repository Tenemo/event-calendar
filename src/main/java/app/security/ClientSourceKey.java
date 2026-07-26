package app.security;

/**
 * Canonical bounded tracking key for one verified client source.
 *
 * <p>Both the sign-in throttle and the calendar-link request throttle bound their in-memory state
 * by client source, so they must agree on what counts as one source. Keeping that definition here
 * means a change to the bound, the trimming, or the fallback keys cannot land in one throttle and
 * miss the other, which would let a caller count as two separate sources to the two controls.
 *
 * <p>The identifier itself is produced by {@link ClientRequestSourceResolver}, which already
 * normalizes addresses and collapses IPv6 clients to their /64 prefix.
 */
public final class ClientSourceKey {
    public static final int MAXIMUM_LENGTH = 128;
    public static final String UNKNOWN = "<unknown-source>";
    public static final String OVERSIZED = "<oversized-source>";

    private ClientSourceKey() {
    }

    public static String of(String sourceIdentifier) {
        if (sourceIdentifier == null || sourceIdentifier.isBlank()) {
            return UNKNOWN;
        }
        String normalizedSourceIdentifier = sourceIdentifier.trim();
        if (normalizedSourceIdentifier.length() > MAXIMUM_LENGTH) {
            return OVERSIZED;
        }
        return normalizedSourceIdentifier;
    }
}
