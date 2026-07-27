package app.calendar;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.membership.CalendarRole;
import app.util.TextNormalizer;
import java.beans.Introspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class CalendarTemplateContractTest {
    private static final Path WEB_APPLICATION_ROOT = Path.of("src", "main", "webapp");

    @Test
    void rolePropertiesUsedByCalendarTemplatesRemainReadable() {
        assertAll(
                () -> assertEquals(CalendarRole.class, propertyType(CalendarView.class, "role")),
                () -> assertEquals(CalendarRole.class, propertyType(CalendarMembershipSummary.class, "role")));
    }

    @Test
    void descriptionInputsAndRenderedTextShareTheApplicationDescriptionPolicy() throws IOException {
        String calendarTemplate = Files.readString(WEB_APPLICATION_ROOT.resolve("calendar.xhtml"));
        String calendarSettingsTemplate = Files.readString(
                WEB_APPLICATION_ROOT.resolve(Path.of("app", "calendar-settings.xhtml")));
        String styleSheet = Files.readString(
                WEB_APPLICATION_ROOT.resolve(Path.of("resources", "css", "app.css")));
        String maximumLengthAttribute = "pt:maxlength=\""
                + TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH
                + "\"";

        assertAll(
                () -> assertTrue(elementHasAttributes(
                        calendarSettingsTemplate,
                        "p:inputTextarea",
                        "id=\"calendarDescription\"",
                        maximumLengthAttribute)),
                () -> assertTrue(elementHasAttributes(
                        calendarTemplate,
                        "p:inputTextarea",
                        "id=\"eventDescription\"",
                        maximumLengthAttribute)),
                () -> assertTrue(elementHasAttributes(
                        calendarTemplate,
                        "h:outputText",
                        "value=\"#{calendarView.calendarDescription}\"",
                        "styleClass=\"lead-text description-text\"")),
                () -> assertTrue(elementHasAttributes(
                        calendarTemplate,
                        "h:panelGroup",
                        "rendered=\"#{not empty event.description}\"",
                        "styleClass=\"event-description description-text\"")),
                () -> assertTrue(cssRuleDeclares(
                        styleSheet,
                        ".description-text",
                        "white-space",
                        "pre-wrap")));
    }

    @Test
    void datePickerOverlaysRenderOutsideTheScrollingEventEditor() throws IOException {
        String calendarTemplate = Files.readString(WEB_APPLICATION_ROOT.resolve("calendar.xhtml"));

        assertAll(
                () -> assertTrue(elementHasAttributes(
                        calendarTemplate,
                        "p:datePicker",
                        "id=\"eventStart\"",
                        "appendTo=\"@(body)\"")),
                () -> assertTrue(elementHasAttributes(
                        calendarTemplate,
                        "p:datePicker",
                        "id=\"eventEnd\"",
                        "appendTo=\"@(body)\"")),
                () -> assertTrue(elementHasAttributes(
                        calendarTemplate,
                        "p:datePicker",
                        "id=\"eventFirstDay\"",
                        "appendTo=\"@(body)\"")),
                () -> assertTrue(elementHasAttributes(
                        calendarTemplate,
                        "p:datePicker",
                        "id=\"eventLastDay\"",
                        "appendTo=\"@(body)\"")));
    }

    @Test
    void eventPaginationAnnouncesAndFocusesNewlyLoadedContent() throws IOException {
        String calendarTemplate = Files.readString(WEB_APPLICATION_ROOT.resolve("calendar.xhtml"));
        String mainTemplate = Files.readString(
                WEB_APPLICATION_ROOT.resolve(Path.of("WEB-INF", "templates", "main.xhtml")));
        String styleSheet = Files.readString(
                WEB_APPLICATION_ROOT.resolve(Path.of("resources", "css", "app.css")));

        assertAll(
                () -> assertTrue(elementHasAttributes(
                        calendarTemplate,
                        "h:panelGroup",
                        "id=\"eventPaginationLiveRegion\"",
                        "pt:role=\"status\"",
                        "pt:aria-live=\"polite\"",
                        "pt:aria-atomic=\"true\"")),
                () -> assertTrue(elementHasAttributes(
                        calendarTemplate,
                        "h:outputText",
                        "id=\"eventPaginationAnnouncement\"",
                        "value=\"#{calendarView.eventPaginationAnnouncement}\"",
                        "pt:data-first-new-event-id=\"#{calendarView.firstNewlyLoadedEventId}\"")),
                () -> assertTrue(elementHasAttributes(
                        calendarTemplate,
                        "article",
                        "id=\"event-#{event.id}\"",
                        "tabindex=\"-1\"")),
                () -> assertTrue(calendarTemplate.contains(
                        "update=\":messages :calendarContent :eventPaginationAnnouncement\"")),
                () -> assertTrue(calendarTemplate.contains(
                        "oncomplete=\"focusAfterLoadingEvents();\"")),
                () -> assertTrue(mainTemplate.contains("function focusAfterLoadingEvents()")),
                () -> assertTrue(cssRuleDeclares(
                        styleSheet,
                        ".visually-hidden",
                        "position",
                        "absolute")));
    }

    private static Class<?> propertyType(Class<?> beanType, String propertyName) throws Exception {
        return Arrays.stream(Introspector.getBeanInfo(beanType).getPropertyDescriptors())
                .filter(property -> property.getName().equals(propertyName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        beanType.getSimpleName() + " must expose the " + propertyName + " property used by Facelets."))
                .getPropertyType();
    }

    private static boolean elementHasAttributes(
            String template,
            String elementName,
            String... expectedAttributes) {
        Matcher elementMatcher = Pattern.compile(
                        "<" + Pattern.quote(elementName) + "\\b[^>]*>")
                .matcher(template);
        while (elementMatcher.find()) {
            String element = elementMatcher.group();
            if (Arrays.stream(expectedAttributes).allMatch(element::contains)) {
                return true;
            }
        }
        return false;
    }

    private static boolean cssRuleDeclares(
            String styleSheet,
            String selector,
            String property,
            String value) {
        return Pattern.compile(
                        Pattern.quote(selector)
                                + "\\s*\\{[^}]*\\b"
                                + Pattern.quote(property)
                                + "\\s*:\\s*"
                                + Pattern.quote(value)
                                + "\\s*;",
                        Pattern.DOTALL)
                .matcher(styleSheet)
                .find();
    }
}
