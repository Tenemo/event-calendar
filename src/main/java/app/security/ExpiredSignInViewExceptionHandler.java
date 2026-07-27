package app.security;

import app.calendar.CalendarLinkToken;
import app.calendar.CalendarRouteFilter;
import app.web.RelativeRedirect;
import app.web.ViewParameterParser;
import jakarta.faces.FacesException;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.application.ViewExpiredException;
import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExceptionHandlerWrapper;
import jakarta.faces.context.FacesContext;
import jakarta.faces.event.ExceptionQueuedEvent;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.OptionalLong;
import java.util.Set;

final class ExpiredSignInViewExceptionHandler extends ExceptionHandlerWrapper {
    static final String RECOVERY_ROUTE = "/login?reauthenticationRequired=true";

    private static final String LOGIN_VIEW = "/login";
    private static final String REGISTRATION_VIEW = "/register";
    private static final String CALENDAR_VIEW = "/calendar";
    private static final String HOME_VIEW = "/index";
    private static final String CALENDARS_VIEW = "/app/calendars";
    private static final String INVITATIONS_VIEW = "/app/invitations";
    private static final String CALENDAR_SETTINGS_VIEW = "/app/calendar-settings";
    private static final String CALENDAR_MEMBERS_VIEW = "/app/calendar-members";
    private static final String ACCOUNT_SETTINGS_VIEW = "/app/account-settings";
    private static final Set<String> AUTHENTICATED_VIEWS = Set.of(
            CALENDARS_VIEW,
            INVITATIONS_VIEW,
            CALENDAR_SETTINGS_VIEW,
            CALENDAR_MEMBERS_VIEW,
            ACCOUNT_SETTINGS_VIEW);
    private static final String VIEW_FILE_SUFFIX = ".xhtml";
    private static final String EXPIRED_VIEW_MESSAGE_SUMMARY = "Page refreshed.";
    private static final String EXPIRED_VIEW_MESSAGE_DETAIL =
            "The page expired, so a fresh copy was loaded. Review it before trying again.";

    ExpiredSignInViewExceptionHandler(ExceptionHandler wrappedHandler) {
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
                    || !(responseObject instanceof HttpServletResponse response)) {
                continue;
            }
            String recoveryRoute = recoveryRouteForExpiredPostback(request, response, exception);
            if (recoveryRoute == null) {
                continue;
            }

            unhandledEvents.remove();
            if (!recoveryResponseWritten) {
                if (isSignInRecoveryRoute(recoveryRoute)) {
                    RelativeRedirect.send(facesContext, recoveryRoute);
                } else {
                    facesContext.addMessage(
                            null,
                            new FacesMessage(
                                    FacesMessage.SEVERITY_WARN,
                                    EXPIRED_VIEW_MESSAGE_SUMMARY,
                                    EXPIRED_VIEW_MESSAGE_DETAIL));
                    RelativeRedirect.sendKeepingMessages(facesContext, recoveryRoute);
                }
                recoveryResponseWritten = true;
            }
        }
        getWrapped().handle();
    }

    static boolean isRecoverableExpiredSignInPostback(
            HttpServletRequest request,
            HttpServletResponse response,
            Throwable exception) {
        String recoveryRoute = recoveryRouteForExpiredPostback(request, response, exception);
        return recoveryRoute != null && isSignInRecoveryRoute(recoveryRoute);
    }

    static String recoveryRouteForExpiredPostback(
            HttpServletRequest request,
            HttpServletResponse response,
            Throwable exception) {
        ViewExpiredException viewExpiredException = findViewExpiredException(exception);
        if (viewExpiredException == null
                || !"POST".equalsIgnoreCase(request.getMethod())
                || response.isCommitted()) {
            return null;
        }

        String viewId = viewExpiredException.getViewId();
        if (matchesView(viewId, LOGIN_VIEW) && matchesRequestRoute(request, LOGIN_VIEW)) {
            return signInRecoveryRoute(request);
        }
        if (matchesView(viewId, REGISTRATION_VIEW)
                && matchesRequestRoute(request, REGISTRATION_VIEW)) {
            return registrationRecoveryRoute(request);
        }
        if (matchesView(viewId, CALENDAR_VIEW) && isCalendarPostbackRequest(request)) {
            return calendarRecoveryRoute(request);
        }
        if (matchesView(viewId, HOME_VIEW)
                && matchesHomeRequestRoute(request)) {
            return "/";
        }
        return authenticatedRecoveryRoute(request, viewId);
    }

    private static boolean matchesView(String viewId, String expectedView) {
        return expectedView.equals(viewId) || (expectedView + VIEW_FILE_SUFFIX).equals(viewId);
    }

    private static boolean matchesRequestRoute(
            HttpServletRequest request,
            String expectedApplicationPath) {
        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        return contextPath != null
                && requestUri != null
                && (requestUri.equals(contextPath + expectedApplicationPath)
                        || requestUri.equals(
                                 contextPath + expectedApplicationPath + VIEW_FILE_SUFFIX));
    }

    private static boolean matchesHomeRequestRoute(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        return contextPath != null
                && requestUri != null
                && (requestUri.equals(contextPath + "/")
                        || requestUri.equals(contextPath + HOME_VIEW)
                        || requestUri.equals(contextPath + HOME_VIEW + VIEW_FILE_SUFFIX));
    }

    private static boolean isCalendarPostbackRequest(HttpServletRequest request) {
        return CalendarLinkToken.fromRequestPath(
                                request.getContextPath(), request.getRequestURI())
                        != null
                || matchesRequestRoute(request, CALENDAR_VIEW)
                || request.getAttribute(
                                CalendarRouteFilter.CALENDAR_LINK_TOKEN_REQUEST_ATTRIBUTE)
                        != null
                || request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI) != null;
    }

    private static String registrationRecoveryRoute(HttpServletRequest request) {
        String invitationToken = ViewParameterParser.invitationToken(
                        request.getParameterMap(), false, true)
                .orElse(null);
        if (invitationToken == null) {
            return safeRecoveryRoute(request);
        }
        return "/register?token="
                + URLEncoder.encode(invitationToken, StandardCharsets.UTF_8);
    }

    private static String signInRecoveryRoute(HttpServletRequest request) {
        String invitationToken = ViewParameterParser.invitationToken(
                        request.getParameterMap(), true, true)
                .orElse(null);
        return invitationToken == null
                ? RECOVERY_ROUTE
                : RECOVERY_ROUTE
                        + "&token="
                        + URLEncoder.encode(invitationToken, StandardCharsets.UTF_8);
    }

    private static boolean isSignInRecoveryRoute(String recoveryRoute) {
        return RECOVERY_ROUTE.equals(recoveryRoute)
                || recoveryRoute.startsWith(RECOVERY_ROUTE + "&token=");
    }

    private static String calendarRecoveryRoute(HttpServletRequest request) {
        String calendarLinkToken = validatedCalendarLinkToken(request);
        return calendarLinkToken == null
                ? safeRecoveryRoute(request)
                : "/" + calendarLinkToken;
    }

    private static String authenticatedRecoveryRoute(
            HttpServletRequest request,
            String viewId) {
        if (request.getUserPrincipal() == null) {
            return null;
        }
        for (String authenticatedView : AUTHENTICATED_VIEWS) {
            if (!matchesView(viewId, authenticatedView)
                    || !matchesRequestRoute(request, authenticatedView)) {
                continue;
            }
            if (CALENDAR_SETTINGS_VIEW.equals(authenticatedView)
                    || CALENDAR_MEMBERS_VIEW.equals(authenticatedView)) {
                return calendarAdministrationRecoveryRoute(request, authenticatedView);
            }
            return authenticatedView;
        }
        return null;
    }

    private static String calendarAdministrationRecoveryRoute(
            HttpServletRequest request,
            String authenticatedView) {
        String[] submittedCalendarIds = request.getParameterValues("id");
        if (submittedCalendarIds == null || submittedCalendarIds.length != 1) {
            return AuthenticatedApplicationFilter.DEFAULT_AUTHENTICATED_ROUTE;
        }
        OptionalLong calendarId = ViewParameterParser.positiveLong(submittedCalendarIds[0]);
        return calendarId.isPresent()
                ? authenticatedView + "?id=" + calendarId.getAsLong()
                : AuthenticatedApplicationFilter.DEFAULT_AUTHENTICATED_ROUTE;
    }

    private static String validatedCalendarLinkToken(HttpServletRequest request) {
        Set<String> calendarLinkTokens = new LinkedHashSet<>();

        Object forwardedCalendarLinkToken =
                request.getAttribute(CalendarRouteFilter.CALENDAR_LINK_TOKEN_REQUEST_ATTRIBUTE);
        if (forwardedCalendarLinkToken != null) {
            if (!(forwardedCalendarLinkToken instanceof String token)
                    || !CalendarLinkToken.isValid(token)) {
                return null;
            }
            calendarLinkTokens.add(token);
        }

        String requestPathCalendarLinkToken = CalendarLinkToken.fromRequestPath(
                request.getContextPath(), request.getRequestURI());
        if (requestPathCalendarLinkToken != null) {
            calendarLinkTokens.add(requestPathCalendarLinkToken);
        }

        Object originalRequestUri = request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        if (originalRequestUri != null) {
            if (!(originalRequestUri instanceof String forwardedRequestUri)) {
                return null;
            }
            String forwardedPathCalendarLinkToken = CalendarLinkToken.fromRequestPath(
                    request.getContextPath(), forwardedRequestUri);
            if (forwardedPathCalendarLinkToken == null) {
                return null;
            }
            calendarLinkTokens.add(forwardedPathCalendarLinkToken);
        }

        return calendarLinkTokens.size() == 1
                ? calendarLinkTokens.iterator().next()
                : null;
    }

    private static String safeRecoveryRoute(HttpServletRequest request) {
        return request.getUserPrincipal() == null
                ? "/"
                : AuthenticatedApplicationFilter.DEFAULT_AUTHENTICATED_ROUTE;
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
