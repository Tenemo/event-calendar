package app.calendar;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class PublicCalendarRouteServletTest {
    @Test
    void extractsExactlyOneNonblankPathSegmentAsTheBearerToken() {
        assertAll(
                () -> assertEquals("token-123", PublicCalendarRouteServlet.tokenFromPath("/token-123")),
                () -> assertNull(PublicCalendarRouteServlet.tokenFromPath(null)),
                () -> assertNull(PublicCalendarRouteServlet.tokenFromPath("")),
                () -> assertNull(PublicCalendarRouteServlet.tokenFromPath("/")),
                () -> assertNull(PublicCalendarRouteServlet.tokenFromPath("/first/second")),
                () -> assertNull(PublicCalendarRouteServlet.tokenFromPath("/   ")));
    }
}
