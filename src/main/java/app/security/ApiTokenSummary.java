package app.security;

import java.time.OffsetDateTime;

public record ApiTokenSummary(
        Long id,
        String name,
        String tokenHint,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt) {
}
