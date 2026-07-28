package app.testsupport;

import java.lang.reflect.Field;

public final class ServiceTestSupport {
    private ServiceTestSupport() {
    }

    public static void setField(Object target, String fieldName, Object value) {
        Class<?> currentType = target.getClass();
        while (currentType != null) {
            try {
                Field field = currentType.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException exception) {
                currentType = currentType.getSuperclass();
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Could not set field " + fieldName + ".", exception);
            }
        }

        throw new IllegalArgumentException("Field " + fieldName + " was not found.");
    }

    public static void setEntityId(Object entity, Long id) {
        setField(entity, "id", id);
    }
}
