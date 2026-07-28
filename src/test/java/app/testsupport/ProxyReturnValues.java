package app.testsupport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Objects;

public final class ProxyReturnValues {
    private ProxyReturnValues() {
    }

    public static <InterfaceType> InterfaceType createInterfaceProxy(
            Class<InterfaceType> interfaceType, InvocationHandler invocationHandler) {
        Objects.requireNonNull(interfaceType, "interfaceType");
        Objects.requireNonNull(invocationHandler, "invocationHandler");
        if (!interfaceType.isInterface()) {
            throw new IllegalArgumentException(interfaceType.getName() + " is not an interface");
        }
        return interfaceType.cast(Proxy.newProxyInstance(
                interfaceType.getClassLoader(),
                new Class<?>[] {interfaceType},
                invocationHandler));
    }

    public static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        throw new IllegalArgumentException(
                "Unsupported primitive return type: " + returnType);
    }
}
