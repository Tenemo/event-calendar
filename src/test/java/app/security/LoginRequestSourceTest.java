package app.security;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.Collections;
import org.junit.jupiter.api.Test;

final class LoginRequestSourceTest {
    @Test
    void sourceComesFromTheSharedDeploymentAwareResolver() {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class },
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getRemoteAddr")) {
                        return "203.0.113.25";
                    }
                    if (method.getName().equals("getHeaders")) {
                        return Collections.enumeration(Collections.singletonList("198.51.100.18"));
                    }
                    throw new AssertionError("Unsupported request method: " + method.getName());
                });
        LoginRequestSource loginRequestSource = new LoginRequestSource();
        setField(loginRequestSource, "request", request);
        setField(loginRequestSource, "clientRequestSourceResolver", new ClientRequestSourceResolver(null));

        assertEquals("203.0.113.25", loginRequestSource.getSourceIdentifier());
    }
}
