package app.web;

import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

public final class RelativeRedirect {
    static final int MAXIMUM_REDIRECT_TARGET_LENGTH = 2_048;

    private RelativeRedirect() {
    }

    public static void send(FacesContext facesContext, String applicationPath) {
        Objects.requireNonNull(facesContext, "Faces context is required.");
        HttpServletResponse response =
                (HttpServletResponse) facesContext.getExternalContext().getResponse();
        send(
                response,
                facesContext.getExternalContext().getRequestContextPath(),
                applicationPath);
        facesContext.responseComplete();
    }

    public static void sendKeepingMessages(
            FacesContext facesContext,
            String applicationPath) {
        Objects.requireNonNull(facesContext, "Faces context is required.");
        facesContext.getExternalContext().getFlash().setKeepMessages(true);
        facesContext.getExternalContext().getFlash().setRedirect(true);
        send(facesContext, applicationPath);
    }

    public static void send(
            HttpServletResponse response,
            String contextPath,
            String applicationPath) {
        Objects.requireNonNull(response, "HTTP response is required.");
        if (!isSafeApplicationPath(applicationPath)) {
            throw new IllegalArgumentException(
                    "Redirect target must be an origin-relative application path.");
        }
        String normalizedContextPath = validateContextPath(contextPath);
        if (normalizedContextPath.length() + applicationPath.length()
                > MAXIMUM_REDIRECT_TARGET_LENGTH) {
            throw new IllegalArgumentException("Redirect target is too long.");
        }
        response.resetBuffer();
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", normalizedContextPath + applicationPath);
    }

    public static boolean isSafeApplicationPath(String applicationPath) {
        if (applicationPath == null
                || applicationPath.isBlank()
                || applicationPath.length() > MAXIMUM_REDIRECT_TARGET_LENGTH
                || !applicationPath.startsWith("/")
                || applicationPath.startsWith("//")
                || applicationPath.indexOf('\\') >= 0
                || containsControlCharacter(applicationPath)) {
            return false;
        }
        try {
            URI redirectUri = new URI(applicationPath);
            String decodedPath = redirectUri.getPath();
            String decodedQuery = redirectUri.getQuery();
            return !redirectUri.isAbsolute()
                    && redirectUri.getRawAuthority() == null
                    && redirectUri.getRawFragment() == null
                    && redirectUri.getRawPath() != null
                    && redirectUri.getRawPath().startsWith("/")
                    && !containsEncodedPathAmbiguity(redirectUri.getRawPath())
                    && redirectUri.normalize().getRawPath().equals(redirectUri.getRawPath())
                    && decodedPath != null
                    && decodedPath.startsWith("/")
                    && !decodedPath.startsWith("//")
                    && !decodedPath.contains("//")
                    && decodedPath.indexOf('\\') < 0
                    && !containsControlCharacter(decodedPath)
                    && !containsDotPathSegment(decodedPath)
                    && (decodedQuery == null
                            || (decodedQuery.indexOf('\\') < 0
                                    && !containsControlCharacter(decodedQuery)));
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static String validateContextPath(String contextPath) {
        if (contextPath == null || contextPath.isEmpty()) {
            return "";
        }
        if (contextPath.equals("/")
                || contextPath.endsWith("/")
                || !isSafeApplicationPath(contextPath)) {
            throw new IllegalArgumentException("Servlet context path is invalid.");
        }
        try {
            URI contextPathUri = new URI(contextPath);
            if (contextPathUri.getRawQuery() != null
                    || !contextPath.equals(contextPathUri.getRawPath())) {
                throw new IllegalArgumentException("Servlet context path is invalid.");
            }
            return contextPath;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Servlet context path is invalid.", exception);
        }
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static boolean containsEncodedPathAmbiguity(String rawPath) {
        for (int characterIndex = 0; characterIndex + 2 < rawPath.length(); characterIndex++) {
            if (rawPath.charAt(characterIndex) != '%') {
                continue;
            }
            int firstHexDigit = Character.digit(rawPath.charAt(characterIndex + 1), 16);
            int secondHexDigit = Character.digit(rawPath.charAt(characterIndex + 2), 16);
            if (firstHexDigit < 0 || secondHexDigit < 0) {
                continue;
            }
            int decodedCharacter = firstHexDigit * 16 + secondHexDigit;
            if (decodedCharacter == '/'
                    || decodedCharacter == '\\'
                    || decodedCharacter == '.'
                    || decodedCharacter == '%'
                    || Character.isISOControl(decodedCharacter)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDotPathSegment(String path) {
        for (String pathSegment : path.split("/", -1)) {
            if (pathSegment.equals(".") || pathSegment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
