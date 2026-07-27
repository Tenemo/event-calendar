package app.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;

public final class RequestBodySecurityFilter implements Filter {
    static final long MAXIMUM_HTTP_MESSAGE_SIZE_BYTES = 1_048_576;

    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain filterChain) throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        if (hasUnsupportedContentEncoding(request)) {
            reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        long declaredContentLength = request.getContentLengthLong();
        if (declaredContentLength > MAXIMUM_HTTP_MESSAGE_SIZE_BYTES) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        if (declaredContentLength < 0 && hasUnknownLengthRequestBody(request)) {
            reject(response, HttpServletResponse.SC_LENGTH_REQUIRED);
            return;
        }

        filterChain.doFilter(servletRequest, servletResponse);
    }

    private static boolean hasUnknownLengthRequestBody(HttpServletRequest request) {
        String requestMethod = request.getMethod();
        boolean bodyCapableMethod = "POST".equals(requestMethod)
                || "PUT".equals(requestMethod)
                || "PATCH".equals(requestMethod)
                || "DELETE".equals(requestMethod);
        return bodyCapableMethod || hasHeader(request, "Transfer-Encoding");
    }

    private static boolean hasHeader(HttpServletRequest request, String headerName) {
        Enumeration<String> headerValues = request.getHeaders(headerName);
        return headerValues == null
                ? request.getHeader(headerName) != null
                : headerValues.hasMoreElements();
    }

    private static boolean hasUnsupportedContentEncoding(HttpServletRequest request) {
        Enumeration<String> headerValues = request.getHeaders("Content-Encoding");
        if (headerValues == null) {
            return request.getHeader("Content-Encoding") != null;
        }
        while (headerValues.hasMoreElements()) {
            String headerValue = headerValues.nextElement();
            if (headerValue == null) {
                return true;
            }
            for (String encoding : headerValue.split(",", -1)) {
                if (!"identity".equalsIgnoreCase(encoding.strip())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void reject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        String responseMessage = switch (status) {
            case HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE ->
                "Unsupported request content encoding.";
            case HttpServletResponse.SC_LENGTH_REQUIRED ->
                "A Content-Length header is required for request bodies.";
            default -> "Request payload is too large.";
        };
        response.getWriter().write(responseMessage);
    }
}
