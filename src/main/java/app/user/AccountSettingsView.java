package app.user;

import app.security.ApiTokenService;
import app.security.ApiTokenSummary;
import app.security.AuthenticatedSessionSecurity;
import app.security.CurrentUser;
import app.security.IssuedApiToken;
import app.security.LocalDevelopmentAutoSignIn;
import app.util.AuthorizationException;
import app.util.NotFoundException;
import app.util.ValidationException;
import app.web.FacesMessages;
import app.web.RelativeRedirect;
import app.web.ViewParameterParser;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Named
@RequestScoped
public class AccountSettingsView {
    private static final DateTimeFormatter TOKEN_TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("MMM d, yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    @Inject
    private CurrentUser currentUser;

    @Inject
    private UserService userService;

    @Inject
    private LocalDevelopmentAutoSignIn localDevelopmentAutoSignIn;

    @Inject
    private ApiTokenService apiTokenService;

    private String currentPassword;
    private String newPassword;
    private String newPasswordConfirmation;
    private String tokenName;
    private String generatedApiToken;
    private List<ApiTokenItem> apiTokens = List.of();

    @PostConstruct
    public void load() {
        refreshApiTokens(currentUser.require());
    }

    public void createApiToken() {
        try {
            ApplicationUser actingUser = currentUser.require();
            IssuedApiToken issuedApiToken = apiTokenService.issueToken(actingUser, tokenName);
            generatedApiToken = issuedApiToken.plaintextToken();
            tokenName = null;
            refreshApiTokens(actingUser);
            FacesMessages.add(
                    FacesMessage.SEVERITY_INFO,
                    "API token created.",
                    "Copy it now because it will not be shown again.");
        } catch (AuthorizationException | ValidationException exception) {
            FacesMessages.add(FacesMessage.SEVERITY_ERROR, "API token could not be created.", exception.getMessage());
        }
    }

    public void revokeApiToken() {
        try {
            ApplicationUser actingUser = currentUser.require();
            Long tokenId = parseTokenId(FacesContext.getCurrentInstance()
                    .getExternalContext()
                    .getRequestParameterMap()
                    .get("apiTokenId"));
            apiTokenService.revokeToken(actingUser, tokenId);
            refreshApiTokens(actingUser);
            FacesMessages.add(
                    FacesMessage.SEVERITY_INFO,
                    "API token revoked.",
                    "Requests using it are no longer authorized.");
        } catch (AuthorizationException | NotFoundException | ValidationException exception) {
            FacesMessages.add(FacesMessage.SEVERITY_ERROR, "Revoke failed.", exception.getMessage());
        }
    }

    public void changePassword() throws ServletException {
        try {
            userService.changePassword(
                    currentUser.require(),
                    currentPassword,
                    newPassword,
                    newPasswordConfirmation);
            signOutAndRedirect();
        } catch (AuthorizationException | ValidationException exception) {
            FacesMessages.add(
                    FacesMessage.SEVERITY_ERROR,
                    "Password could not be changed.",
                    exception.getMessage());
        }
    }

    public String getUsername() {
        return currentUser.require().getUsername();
    }

    private static Long parseTokenId(String submittedTokenId) {
        return ViewParameterParser.positiveLong(submittedTokenId)
                .orElseThrow(() -> new ValidationException("API token is invalid."));
    }

    private void refreshApiTokens(ApplicationUser actingUser) {
        apiTokens = apiTokenService.listTokens(actingUser).stream()
                .map(ApiTokenItem::new)
                .toList();
    }

    private void signOutAndRedirect() throws ServletException {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        HttpServletRequest request = (HttpServletRequest) facesContext.getExternalContext().getRequest();
        AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);
        localDevelopmentAutoSignIn.suppressAfterExplicitSignOut(request);
        RelativeRedirect.send(facesContext, "/sign-in?passwordChanged=true");
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getNewPasswordConfirmation() {
        return newPasswordConfirmation;
    }

    public void setNewPasswordConfirmation(String newPasswordConfirmation) {
        this.newPasswordConfirmation = newPasswordConfirmation;
    }

    public String getTokenName() {
        return tokenName;
    }

    public void setTokenName(String tokenName) {
        this.tokenName = tokenName;
    }

    public int getMaximumTokenNameLength() {
        return ApiTokenService.MAXIMUM_TOKEN_NAME_LENGTH;
    }

    public String getGeneratedApiToken() {
        return generatedApiToken;
    }

    public boolean hasGeneratedApiToken() {
        return generatedApiToken != null;
    }

    public List<ApiTokenItem> getApiTokens() {
        return apiTokens;
    }

    public static final class ApiTokenItem {
        private final Long id;
        private final String name;
        private final String tokenHint;
        private final String createdAtLabel;
        private final String lastUsedAtLabel;

        private ApiTokenItem(ApiTokenSummary apiToken) {
            id = apiToken.id();
            name = apiToken.name();
            tokenHint = apiToken.tokenHint();
            createdAtLabel = formatTimestamp(apiToken.createdAt());
            lastUsedAtLabel = apiToken.lastUsedAt() == null
                    ? "Never"
                    : formatTimestamp(apiToken.lastUsedAt());
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getTokenHint() {
            return tokenHint;
        }

        public String getCreatedAtLabel() {
            return createdAtLabel;
        }

        public String getLastUsedAtLabel() {
            return lastUsedAtLabel;
        }
    }

    static String formatTimestamp(OffsetDateTime timestamp) {
        return TOKEN_TIMESTAMP_FORMAT.format(timestamp);
    }
}
