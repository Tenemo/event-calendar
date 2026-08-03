package app.web;

import jakarta.faces.FacesException;
import jakarta.faces.application.ViewExpiredException;
import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExceptionHandlerWrapper;
import jakarta.faces.context.FacesContext;
import jakarta.faces.event.ExceptionQueuedEvent;
import jakarta.faces.event.ExceptionQueuedEventContext;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

public final class ExpiredViewExceptionHandler extends ExceptionHandlerWrapper {
    ExpiredViewExceptionHandler(ExceptionHandler wrappedHandler) {
        super(wrappedHandler);
    }

    @Override
    public void handle() throws FacesException {
        Iterator<ExceptionQueuedEvent> unhandledEvents =
                getUnhandledExceptionQueuedEvents().iterator();
        while (unhandledEvents.hasNext()) {
            ExceptionQueuedEvent queuedEvent = unhandledEvents.next();
            ExceptionQueuedEventContext eventContext = queuedEvent.getContext();
            if (findExpiredViewException(eventContext.getException()) == null) {
                continue;
            }

            unhandledEvents.remove();
            FacesContext facesContext = eventContext.getContext();
            HttpServletRequest request =
                    (HttpServletRequest) facesContext.getExternalContext().getRequest();
            String recoveryPath = recoveryApplicationPath(
                    request.getContextPath(),
                    request.getRequestURI(),
                    facesContext.getExternalContext().getRequestParameterValuesMap());
            RelativeRedirect.send(facesContext, recoveryPath);
            return;
        }
        getWrapped().handle();
    }

    static String recoveryApplicationPath(
            String contextPath, String requestUri, Map<String, String[]> requestParameters) {
        String applicationPath = safeApplicationPath(contextPath, requestUri);
        StringBuilder recoveryPath = new StringBuilder(applicationPath).append("?viewExpired=true");
        ViewParameterParser.invitationToken(requestParameters, true).ifPresent(invitationToken -> recoveryPath
                .append("&token=")
                .append(URLEncoder.encode(invitationToken, StandardCharsets.UTF_8)));
        return recoveryPath.toString();
    }

    private static ViewExpiredException findExpiredViewException(Throwable exception) {
        Throwable currentException = exception;
        while (currentException != null) {
            if (currentException instanceof ViewExpiredException expiredViewException) {
                return expiredViewException;
            }
            Throwable cause = currentException.getCause();
            currentException = cause == currentException ? null : cause;
        }
        return null;
    }

    private static String safeApplicationPath(String contextPath, String requestUri) {
        String normalizedContextPath = contextPath == null ? "" : contextPath;
        String requiredPrefix = normalizedContextPath + "/";
        if (requestUri == null || !requestUri.startsWith(requiredPrefix)) {
            return "/";
        }

        String applicationPath = requestUri.substring(normalizedContextPath.length());
        if (applicationPath.startsWith("//")
                || applicationPath.indexOf('\\') >= 0
                || applicationPath.indexOf('#') >= 0
                || applicationPath.codePoints().anyMatch(Character::isISOControl)) {
            return "/";
        }
        return applicationPath;
    }
}
