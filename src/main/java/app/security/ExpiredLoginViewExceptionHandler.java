package app.security;

import app.web.RelativeRedirect;
import jakarta.faces.FacesException;
import jakarta.faces.application.ViewExpiredException;
import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExceptionHandlerWrapper;
import jakarta.faces.context.FacesContext;
import jakarta.faces.event.ExceptionQueuedEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Iterator;

final class ExpiredLoginViewExceptionHandler extends ExceptionHandlerWrapper {
    static final String RECOVERY_ROUTE = "/login?reauthenticationRequired=true";

    ExpiredLoginViewExceptionHandler(ExceptionHandler wrappedHandler) {
        super(wrappedHandler);
    }

    @Override
    public void handle() throws FacesException {
        Iterator<ExceptionQueuedEvent> unhandledEvents =
                getUnhandledExceptionQueuedEvents().iterator();
        boolean recoveryResponseWritten = false;
        while (unhandledEvents.hasNext()) {
            ExceptionQueuedEvent event = unhandledEvents.next();
            FacesContext facesContext = event.getContext().getContext();
            Object requestObject = facesContext.getExternalContext().getRequest();
            Object responseObject = facesContext.getExternalContext().getResponse();
            Throwable exception = event.getContext().getException();
            if (!(requestObject instanceof HttpServletRequest request)
                    || !(responseObject instanceof HttpServletResponse response)
                    || !isRecoverableExpiredLoginPostback(request, response, exception)) {
                continue;
            }

            unhandledEvents.remove();
            if (!recoveryResponseWritten) {
                RelativeRedirect.send(facesContext, RECOVERY_ROUTE);
                recoveryResponseWritten = true;
            }
        }
        getWrapped().handle();
    }

    static boolean isRecoverableExpiredLoginPostback(
            HttpServletRequest request,
            HttpServletResponse response,
            Throwable exception) {
        ViewExpiredException viewExpiredException = findViewExpiredException(exception);
        if (viewExpiredException == null
                || !"POST".equalsIgnoreCase(request.getMethod())
                || response.isCommitted()) {
            return false;
        }

        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        String viewId = viewExpiredException.getViewId();
        return contextPath != null
                && requestUri != null
                && (requestUri.equals(contextPath + "/login")
                        || requestUri.equals(contextPath + "/login.xhtml"))
                && ("/login".equals(viewId) || "/login.xhtml".equals(viewId));
    }

    private static ViewExpiredException findViewExpiredException(Throwable exception) {
        Throwable currentCause = exception;
        while (currentCause != null) {
            if (currentCause instanceof ViewExpiredException viewExpiredException) {
                return viewExpiredException;
            }
            if (currentCause == currentCause.getCause()) {
                return null;
            }
            currentCause = currentCause.getCause();
        }
        return null;
    }
}
