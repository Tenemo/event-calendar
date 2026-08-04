package app.security;

import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.NotFoundException;
import app.util.TextNormalizer;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Stateless
public class ApiTokenService {
    public static final int MAXIMUM_TOKEN_NAME_LENGTH = 80;
    static final int TOKEN_HINT_LENGTH = 8;
    private static final int ENCODED_RANDOM_TOKEN_LENGTH = 43;
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            Pattern.quote(TokenService.API_TOKEN_PREFIX)
                    + "[A-Za-z0-9_-]{"
                    + ENCODED_RANDOM_TOKEN_LENGTH
                    + "}");

    @PersistenceContext(unitName = "calendarPersistenceUnit")
    private EntityManager entityManager;

    @Inject
    private TokenService tokenService;

    private Clock clock = Clock.systemUTC();

    public IssuedApiToken issueToken(ApplicationUser actingUser, String name) {
        ApplicationUser user = requireUser(actingUser);
        String normalizedName = TextNormalizer.normalizeRequiredText(
                name,
                "Token name is required.",
                MAXIMUM_TOKEN_NAME_LENGTH,
                "Token name must be 80 characters or fewer.");
        String plaintextToken = tokenService.generateApiToken();
        OffsetDateTime createdAt = OffsetDateTime.now(clock);

        ApiToken apiToken = new ApiToken();
        apiToken.setUser(user);
        apiToken.setName(normalizedName);
        apiToken.setTokenDigest(digestToken(plaintextToken));
        apiToken.setTokenHint(plaintextToken.substring(plaintextToken.length() - TOKEN_HINT_LENGTH));
        apiToken.setCreatedAt(createdAt);
        entityManager.persist(apiToken);
        entityManager.flush();

        return new IssuedApiToken(toSummary(apiToken), plaintextToken);
    }

    public List<ApiTokenSummary> listTokens(ApplicationUser actingUser) {
        ApplicationUser user = requireUser(actingUser);
        return entityManager
                .createQuery(
                        "select new app.security.ApiTokenSummary("
                                + "apiToken.id, apiToken.name, apiToken.tokenHint, "
                                + "apiToken.createdAt, apiToken.lastUsedAt) "
                                + "from ApiToken apiToken "
                                + "where apiToken.user.id = :userId "
                                + "order by apiToken.createdAt desc, apiToken.id desc",
                        ApiTokenSummary.class)
                .setParameter("userId", user.getId())
                .getResultList();
    }

    public void revokeToken(ApplicationUser actingUser, Long tokenId) {
        ApplicationUser user = requireUser(actingUser);
        if (tokenId == null) {
            throw tokenNotFound();
        }
        try {
            ApiToken apiToken = entityManager
                    .createQuery(
                            "select apiToken from ApiToken apiToken "
                                    + "where apiToken.id = :tokenId and apiToken.user.id = :userId",
                            ApiToken.class)
                    .setParameter("tokenId", tokenId)
                    .setParameter("userId", user.getId())
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getSingleResult();
            entityManager.remove(apiToken);
        } catch (NoResultException exception) {
            throw tokenNotFound();
        }
    }

    public Optional<ApplicationUser> authenticate(String plaintextToken) {
        if (!isValidCandidate(plaintextToken)) {
            return Optional.empty();
        }
        try {
            ApiToken apiToken = entityManager
                    .createQuery(
                            "select apiToken from ApiToken apiToken "
                                    + "join fetch apiToken.user "
                                    + "where apiToken.tokenDigest = :tokenDigest",
                            ApiToken.class)
                    .setParameter("tokenDigest", digestToken(plaintextToken))
                    .getSingleResult();
            apiToken.setLastUsedAt(OffsetDateTime.now(clock));
            return Optional.of(apiToken.getUser());
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    static boolean isValidCandidate(String plaintextToken) {
        return plaintextToken != null && TOKEN_PATTERN.matcher(plaintextToken).matches();
    }

    static String digestToken(String plaintextToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plaintextToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private ApplicationUser requireUser(ApplicationUser actingUser) {
        if (actingUser == null || actingUser.getId() == null) {
            throw new AuthorizationException("Sign-in is required.");
        }
        ApplicationUser user = entityManager.find(ApplicationUser.class, actingUser.getId());
        if (user == null) {
            throw new AuthorizationException("Sign-in is required.");
        }
        return user;
    }

    private static ApiTokenSummary toSummary(ApiToken apiToken) {
        return new ApiTokenSummary(
                apiToken.getId(),
                apiToken.getName(),
                apiToken.getTokenHint(),
                apiToken.getCreatedAt(),
                apiToken.getLastUsedAt());
    }

    private static NotFoundException tokenNotFound() {
        return new NotFoundException("API token was not found.");
    }
}
