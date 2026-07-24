package app.calendar;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.membership.CalendarRole;
import java.beans.Introspector;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class CalendarTemplateContractTest {
    @Test
    void rolePropertiesUsedByCalendarTemplatesRemainReadable() {
        assertAll(
                () -> assertEquals(CalendarRole.class, propertyType(CalendarView.class, "role")),
                () -> assertEquals(CalendarRole.class, propertyType(CalendarMembershipSummary.class, "role")));
    }

    private static Class<?> propertyType(Class<?> beanType, String propertyName) throws Exception {
        return Arrays.stream(Introspector.getBeanInfo(beanType).getPropertyDescriptors())
                .filter(property -> property.getName().equals(propertyName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        beanType.getSimpleName() + " must expose the " + propertyName + " property used by Facelets."))
                .getPropertyType();
    }
}
