package app.security;

import static app.testsupport.ProxyReturnValues.defaultValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class RequestBodySecurityFilterTest {
    @Test
    void rejectsEveryUnsupportedOrMalformedContentEncodingBeforeBodyParsing()
            throws Exception {
        for (List<String> contentEncodingHeaders : List.of(
                List.of("gzip"),
                List.of("br"),
                List.of("deflate"),
                List.of("identity, gzip"),
                List.of(""))) {
            RequestCapture requestCapture = new RequestCapture(
                    contentEncodingHeaders,
                    32);
            ResponseCapture responseCapture = new ResponseCapture();
            AtomicInteger filterChainCalls = new AtomicInteger();

            new RequestBodySecurityFilter().doFilter(
                    requestCapture.request(),
                    responseCapture.response(),
                    (request, response) -> filterChainCalls.incrementAndGet());

            assertAll(
                    () -> assertEquals(0, filterChainCalls.get()),
                    () -> assertEquals(0, requestCapture.bodyAccessCount.get()),
                    () -> assertEquals(
                            HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                            responseCapture.status),
                    () -> assertEquals("no-store", responseCapture.headers.get("Cache-Control")),
                    () -> assertEquals(
                            "Unsupported request content encoding.",
                            responseCapture.body()));
        }
    }

    @Test
    void rejectsDeclaredOversizedBodiesBeforeBodyParsing() throws Exception {
        RequestCapture requestCapture = new RequestCapture(
                List.of(),
                RequestBodySecurityFilter.MAXIMUM_HTTP_MESSAGE_SIZE_BYTES + 1);
        ResponseCapture responseCapture = new ResponseCapture();
        AtomicInteger filterChainCalls = new AtomicInteger();

        new RequestBodySecurityFilter().doFilter(
                requestCapture.request(),
                responseCapture.response(),
                (request, response) -> filterChainCalls.incrementAndGet());

        assertAll(
                () -> assertEquals(0, filterChainCalls.get()),
                () -> assertEquals(0, requestCapture.bodyAccessCount.get()),
                () -> assertEquals(
                        HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        responseCapture.status),
                () -> assertEquals("Request payload is too large.", responseCapture.body()));
    }

    @Test
    void acceptsAbsentAndIdentityEncodingWithinTheDeclaredLimit() throws Exception {
        for (List<String> contentEncodingHeaders : List.of(
                List.<String>of(),
                List.of("identity"),
                List.of("IDENTITY, identity"))) {
            AtomicInteger filterChainCalls = new AtomicInteger();

            new RequestBodySecurityFilter().doFilter(
                    new RequestCapture(
                                    contentEncodingHeaders,
                                    RequestBodySecurityFilter.MAXIMUM_HTTP_MESSAGE_SIZE_BYTES)
                            .request(),
                    new ResponseCapture().response(),
                    (request, response) -> filterChainCalls.incrementAndGet());

            assertEquals(1, filterChainCalls.get());
        }
    }

    @Test
    void rejectsUnknownLengthRequestBodiesBeforeParsing() throws Exception {
        for (String requestMethod : List.of("POST", "PUT", "PATCH", "DELETE")) {
            RequestCapture requestCapture = new RequestCapture(
                    List.of(),
                    List.of(),
                    -1,
                    requestMethod);
            ResponseCapture responseCapture = new ResponseCapture();
            AtomicInteger filterChainCalls = new AtomicInteger();

            new RequestBodySecurityFilter().doFilter(
                    requestCapture.request(),
                    responseCapture.response(),
                    (request, response) -> filterChainCalls.incrementAndGet());

            assertAll(
                    () -> assertEquals(0, filterChainCalls.get()),
                    () -> assertEquals(0, requestCapture.bodyAccessCount.get()),
                    () -> assertEquals(
                            HttpServletResponse.SC_LENGTH_REQUIRED,
                            responseCapture.status),
                    () -> assertEquals(
                            "A Content-Length header is required for request bodies.",
                            responseCapture.body()));
        }

        RequestCapture chunkedGetRequest = new RequestCapture(
                List.of(),
                List.of("chunked"),
                -1,
                "GET");
        ResponseCapture chunkedGetResponse = new ResponseCapture();
        AtomicInteger chunkedGetFilterChainCalls = new AtomicInteger();
        new RequestBodySecurityFilter().doFilter(
                chunkedGetRequest.request(),
                chunkedGetResponse.response(),
                (request, response) -> chunkedGetFilterChainCalls.incrementAndGet());

        assertAll(
                () -> assertEquals(0, chunkedGetFilterChainCalls.get()),
                () -> assertEquals(
                        HttpServletResponse.SC_LENGTH_REQUIRED,
                        chunkedGetResponse.status));
    }

    @Test
    void acceptsBodylessRequestsWithoutDeclaredLengthAndDeclaredEmptyPosts()
            throws Exception {
        for (RequestCapture requestCapture : List.of(
                new RequestCapture(List.of(), List.of(), -1, "GET"),
                new RequestCapture(List.of(), List.of(), -1, "HEAD"),
                new RequestCapture(List.of(), List.of(), -1, "OPTIONS"),
                new RequestCapture(List.of(), List.of(), -1, "TRACE"),
                new RequestCapture(List.of(), List.of(), 0, "POST"))) {
            AtomicInteger filterChainCalls = new AtomicInteger();

            new RequestBodySecurityFilter().doFilter(
                    requestCapture.request(),
                    new ResponseCapture().response(),
                    (request, response) -> filterChainCalls.incrementAndGet());

            assertEquals(1, filterChainCalls.get());
        }
    }

    private static final class RequestCapture {
        private final List<String> contentEncodingHeaders;
        private final List<String> transferEncodingHeaders;
        private final long contentLength;
        private final String requestMethod;
        private final AtomicInteger bodyAccessCount = new AtomicInteger();

        private RequestCapture(List<String> contentEncodingHeaders, long contentLength) {
            this(contentEncodingHeaders, List.of(), contentLength, "POST");
        }

        private RequestCapture(
                List<String> contentEncodingHeaders,
                List<String> transferEncodingHeaders,
                long contentLength,
                String requestMethod) {
            this.contentEncodingHeaders = contentEncodingHeaders;
            this.transferEncodingHeaders = transferEncodingHeaders;
            this.contentLength = contentLength;
            this.requestMethod = requestMethod;
        }

        private HttpServletRequest request() {
            return (HttpServletRequest) Proxy.newProxyInstance(
                    HttpServletRequest.class.getClassLoader(),
                    new Class<?>[] {HttpServletRequest.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getHeaders" -> Collections.enumeration(
                                headerValues((String) arguments[0]));
                        case "getHeader" -> headerValues((String) arguments[0]).stream()
                                .findFirst()
                                .orElse(null);
                        case "getContentLengthLong" -> contentLength;
                        case "getMethod" -> requestMethod;
                        case "getInputStream", "getReader", "getParameter",
                                "getParameterMap", "getParameterNames", "getParameterValues" -> {
                            bodyAccessCount.incrementAndGet();
                            throw new AssertionError("The rejected body was parsed.");
                        }
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private List<String> headerValues(String headerName) {
            if ("Content-Encoding".equalsIgnoreCase(headerName)) {
                return contentEncodingHeaders;
            }
            if ("Transfer-Encoding".equalsIgnoreCase(headerName)) {
                return transferEncodingHeaders;
            }
            return List.of();
        }
    }

    private static final class ResponseCapture {
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final StringWriter body = new StringWriter();
        private final PrintWriter writer = new PrintWriter(body);
        private int status;

        private HttpServletResponse response() {
            return (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[] {HttpServletResponse.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "setStatus" -> setStatus((Integer) arguments[0]);
                        case "setContentType" -> null;
                        case "setHeader" -> headers.put(
                                (String) arguments[0],
                                (String) arguments[1]);
                        case "getWriter" -> writer;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private Object setStatus(int responseStatus) {
            status = responseStatus;
            return null;
        }

        private String body() {
            writer.flush();
            return body.toString();
        }
    }
}
