package app.security;

public record IssuedApiToken(ApiTokenSummary summary, String plaintextToken) {
}
