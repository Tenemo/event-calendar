package app.calendar;

import jakarta.faces.application.ViewHandler;
import jakarta.faces.application.ViewHandlerWrapper;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;

public final class CanonicalCalendarViewHandler extends ViewHandlerWrapper {
    private static final String CALENDAR_VIEW_ID = "/WEB-INF/views/calendar.xhtml";

    public CanonicalCalendarViewHandler(ViewHandler wrappedViewHandler) {
        super(wrappedViewHandler);
    }

    @Override
    public String getActionURL(FacesContext facesContext, String viewId) {
        ExternalContext externalContext = facesContext.getExternalContext();
        Object requestToken = externalContext
                .getRequestMap()
                .get(CalendarRouteFilter.CALENDAR_LINK_TOKEN_REQUEST_ATTRIBUTE);
        if (CALENDAR_VIEW_ID.equals(viewId)
                && requestToken instanceof String calendarLinkToken
                && CalendarLinkToken.isValid(calendarLinkToken)) {
            return externalContext.encodeActionURL(
                    externalContext.getApplicationContextPath() + "/" + calendarLinkToken);
        }
        return super.getActionURL(facesContext, viewId);
    }
}
