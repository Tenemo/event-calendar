package app.security;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

final class LoginRequestSourceTest {
    @Test
    void sourceComesFromTheContainerVerifiedRemoteAddress() {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class },
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getRemoteAddr")) {
                        return "203.0.113.25";
                    }
                    throw new AssertionError("Unsupported request method: " + method.getName());
                });
        LoginRequestSource loginRequestSource = new LoginRequestSource();
        setField(loginRequestSource, "request", request);

        assertEquals("203.0.113.25", loginRequestSource.getSourceIdentifier());
    }
}
