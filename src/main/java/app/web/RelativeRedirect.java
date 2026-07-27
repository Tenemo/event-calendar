package app.web;

import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

public final class RelativeRedirect {
    private RelativeRedirect() {
    }

    public static void send(FacesContext facesContext, String applicationPath) {
        Objects.requireNonNull(facesContext, "Faces context is required.");
        ExternalContext externalContext = facesContext.getExternalContext();
        String redirectTarget = redirectTarget(externalContext.getRequestContextPath(), applicationPath);
        try {
            externalContext.redirect(redirectTarget);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not redirect the response.", exception);
        }
        facesContext.responseComplete();
    }

    public static void sendKeepingMessages(FacesContext facesContext, String applicationPath) {
        Objects.requireNonNull(facesContext, "Faces context is required.");
        facesContext.getExternalContext().getFlash().setKeepMessages(true);
        facesContext.getExternalContext().getFlash().setRedirect(true);
        send(facesContext, applicationPath);
    }

    public static void send(
            HttpServletRequest request,
            HttpServletResponse response,
            String applicationPath) {
        Objects.requireNonNull(request, "HTTP request is required.");
        Objects.requireNonNull(response, "HTTP response is required.");
        String redirectTarget = redirectTarget(request.getContextPath(), applicationPath);

        response.resetBuffer();
        response.setHeader("Cache-Control", "no-store");
        if (!"partial/ajax".equalsIgnoreCase(request.getHeader("Faces-Request"))) {
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", redirectTarget);
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/xml;charset=UTF-8");
        try {
            response.getWriter().write(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<partial-response><redirect url=\""
                            + redirectTarget.replace("&", "&amp;")
                            + "\"/></partial-response>");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write the JSF AJAX redirect response.", exception);
        }
    }

    private static String redirectTarget(String contextPath, String applicationPath) {
        String normalizedContextPath = contextPath == null ? "" : contextPath;
        if ((!normalizedContextPath.isEmpty()
                        && (!normalizedContextPath.startsWith("/")
                                || normalizedContextPath.endsWith("/")
                                || containsUnsafeCharacter(normalizedContextPath)))
                || applicationPath == null
                || !applicationPath.startsWith("/")
                || applicationPath.startsWith("//")
                || containsUnsafeCharacter(applicationPath)) {
            throw new IllegalArgumentException("Redirect target must be an origin-relative application path.");
        }
        return normalizedContextPath + applicationPath;
    }

    private static boolean containsUnsafeCharacter(String value) {
        return value.indexOf('\\') >= 0
                || value.indexOf('#') >= 0
                || value.codePoints().anyMatch(Character::isISOControl);
    }
}
