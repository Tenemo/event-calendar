import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

class ProductionDeploymentVerifier {
    private static final Duration DEFAULT_VERIFICATION_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration MAXIMUM_VERIFICATION_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration MAXIMUM_POLL_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MAXIMUM_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAXIMUM_RESPONSE_BYTES = 512 * 1024;

    private static final String HEALTH_PATH = "/health";
    private static final String HOME_PATH = "/";
    private static final String SIGN_IN_PATH = "/login";
    private static final String PROTECTED_CALENDARS_PATH = "/app/calendars";
    private static final String LEGACY_CALENDAR_PATH = "/calendar/Abc_123-xY0";
    private static final String HOME_PAGE_MARKER = "<title>Shared calendar</title>";
    private static final String SIGN_IN_PAGE_MARKER = "<title>Sign in - Shared calendar</title>";
    private static final String PRIMEFACES_SECURE_COOKIES_MARKER = "cookiesSecure:true";
    private static final String DEPLOYMENT_REVISION_HEADER = "X-Deployment-Revision";
    private static final String CONTENT_SECURITY_POLICY =
            "frame-ancestors 'none'; base-uri 'self'; object-src 'none'";
    private static final String PERMISSIONS_POLICY =
            "camera=(), geolocation=(), microphone=(), payment=(), usb=()";
    private static final String STRICT_TRANSPORT_SECURITY = "max-age=31536000";
    private static final String SESSION_COOKIE_NAME = "JSESSIONID";
    private static final String FLASH_COOKIE_NAME = "oam.Flash.RENDERMAP.TOKEN";
    private static final Pattern FULL_GIT_COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{40}");

    public static void main(String[] arguments) {
        int exitCode = run(arguments, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int run(String[] arguments, PrintStream output, PrintStream errorOutput) {
        try {
            if (arguments.length == 1 && arguments[0].equals("self-test")) {
                runSelfTests(output);
                return 0;
            }
            if (arguments.length == 1 && arguments[0].equals("--help")) {
                printUsage(output);
                return 0;
            }

            VerificationConfiguration configuration = VerificationConfiguration.parse(arguments);
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            ProductionVerifier verifier = new ProductionVerifier(
                    configuration,
                    new JavaHttpEndpointClient(httpClient),
                    System::nanoTime,
                    duration -> Thread.sleep(duration.toMillis(), duration.toNanosPart() % 1_000_000));
            verifier.verify();
            output.println("Verified deployment " + configuration.expectedRevision()
                    + " and all read-only production smoke contracts at " + configuration.baseUri() + ".");
            return 0;
        } catch (InputException exception) {
            errorOutput.println("Production deployment verification configuration is invalid: "
                    + exception.getMessage());
            printUsage(errorOutput);
            return 2;
        } catch (VerificationException exception) {
            errorOutput.println(exception.getMessage());
            return 1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            errorOutput.println("Production deployment verification was interrupted.");
            return 1;
        } catch (Exception | AssertionError exception) {
            errorOutput.println("Production deployment verification failed unexpectedly: "
                    + exception.getClass().getSimpleName() + ".");
            return 1;
        }
    }

    private static void printUsage(PrintStream output) {
        output.println("Usage:");
        output.println("  java scripts/verify-production-deployment.java"
                + " --base-url <https-url> --expected-revision <40-character-git-sha>"
                + " [--timeout-seconds <1-1800>] [--poll-interval-seconds <1-60>]");
        output.println("  java scripts/verify-production-deployment.java self-test");
    }

    private static final class ProductionVerifier {
        private final VerificationConfiguration configuration;
        private final EndpointClient endpointClient;
        private final NanosecondClock clock;
        private final Sleeper sleeper;

        private ProductionVerifier(
                VerificationConfiguration configuration,
                EndpointClient endpointClient,
                NanosecondClock clock,
                Sleeper sleeper) {
            this.configuration = configuration;
            this.endpointClient = endpointClient;
            this.clock = clock;
            this.sleeper = sleeper;
        }

        private void verify() throws InterruptedException {
            long deadlineNanos = clock.nanoTime() + configuration.verificationTimeout().toNanos();
            String lastObservation = "the service did not return a response";

            while (remainingNanos(deadlineNanos) > 0) {
                try {
                    String contractFailure = verifyCurrentDeployment(deadlineNanos);
                    if (contractFailure == null) {
                        return;
                    }
                    lastObservation = contractFailure;
                } catch (IOException exception) {
                    lastObservation = "a production request could not be completed";
                } catch (VerificationWindowExpired exception) {
                    lastObservation = "the verification window expired during a production request";
                }

                long remainingNanos = remainingNanos(deadlineNanos);
                if (remainingNanos <= 0) {
                    break;
                }
                long sleepNanos = Math.min(configuration.pollInterval().toNanos(), remainingNanos);
                sleeper.sleep(Duration.ofNanos(sleepNanos));
            }

            throw new VerificationException(
                    "Production did not serve the expected deployment and read-only smoke contracts within "
                            + configuration.verificationTimeout().toSeconds()
                            + " seconds. Last observation: " + lastObservation + ".");
        }

        private String verifyCurrentDeployment(long deadlineNanos)
                throws IOException, InterruptedException, VerificationWindowExpired {
            EndpointResponse healthResponse = request(HEALTH_PATH, deadlineNanos);
            String healthFailure = deploymentHealthFailure(healthResponse);
            if (healthFailure != null) {
                return healthFailure;
            }

            EndpointResponse homeResponse = request(HOME_PATH, deadlineNanos);
            String homeFailure = htmlPageFailure("home page", homeResponse, HOME_PAGE_MARKER);
            if (homeFailure != null) {
                return homeFailure;
            }
            String homeSecurityFailure = dynamicSecurityPolicyFailure("home page", homeResponse, true);
            if (homeSecurityFailure != null) {
                return homeSecurityFailure;
            }

            EndpointResponse signInResponse = request(SIGN_IN_PATH, deadlineNanos);
            String signInFailure = htmlPageFailure("sign-in page", signInResponse, SIGN_IN_PAGE_MARKER);
            if (signInFailure != null) {
                return signInFailure;
            }
            String signInSecurityFailure = dynamicSecurityPolicyFailure("sign-in page", signInResponse, true);
            if (signInSecurityFailure != null) {
                return signInSecurityFailure;
            }
            String signInCookieFailure = signInCookieSecurityFailure(signInResponse);
            if (signInCookieFailure != null) {
                return signInCookieFailure;
            }

            EndpointResponse protectedCalendarsResponse = request(PROTECTED_CALENDARS_PATH, deadlineNanos);
            String protectedRedirectFailure = protectedRedirectFailure(protectedCalendarsResponse);
            if (protectedRedirectFailure != null) {
                return protectedRedirectFailure;
            }
            String protectedRedirectSecurityFailure =
                    dynamicSecurityPolicyFailure("protected calendars redirect", protectedCalendarsResponse, true);
            if (protectedRedirectSecurityFailure != null) {
                return protectedRedirectSecurityFailure;
            }

            EndpointResponse legacyCalendarResponse = request(LEGACY_CALENDAR_PATH, deadlineNanos);
            if (legacyCalendarResponse.statusCode() != 404) {
                return "the rejected legacy calendar route returned HTTP "
                        + legacyCalendarResponse.statusCode() + " instead of HTTP 404";
            }
            String legacyRouteSecurityFailure =
                    dynamicSecurityPolicyFailure("rejected legacy calendar route", legacyCalendarResponse, true);
            if (legacyRouteSecurityFailure != null) {
                return legacyRouteSecurityFailure;
            }

            String finalHealthFailure = deploymentHealthFailure(request(HEALTH_PATH, deadlineNanos));
            return finalHealthFailure == null
                    ? null
                    : "after the public route checks, " + finalHealthFailure;
        }

        private String deploymentHealthFailure(EndpointResponse healthResponse) {
            String healthFailure = exactPlainTextFailure("health", healthResponse, "ok");
            if (healthFailure != null) {
                return healthFailure;
            }
            if (!healthResponse.deploymentRevisionHeaders().equals(List.of(configuration.expectedRevision()))) {
                return "the health endpoint deployment revision did not match its contract";
            }
            return dynamicSecurityPolicyFailure("health endpoint", healthResponse, true);
        }

        private String protectedRedirectFailure(EndpointResponse response) {
            if (response.statusCode() != 302) {
                return "the protected calendars route returned HTTP "
                        + response.statusCode() + " instead of the direct sign-in redirect";
            }
            RedirectMetadata redirect = response.headers().redirect();
            if (redirect.headerCount() != 1
                    || !URI.create(SIGN_IN_PATH).equals(redirect.location())) {
                return "the protected calendars route did not redirect directly to the expected sign-in URL";
            }
            return null;
        }

        private static String signInCookieSecurityFailure(EndpointResponse response) {
            if (!response.body().contains(PRIMEFACES_SECURE_COOKIES_MARKER)) {
                return "the sign-in page did not enable secure PrimeFaces cookies";
            }

            List<ResponseCookieMetadata> cookies = response.headers().cookies();
            if (cookies.isEmpty()) {
                return "the sign-in page did not emit the expected secure session cookies";
            }
            if (cookies.stream().anyMatch(cookie -> !cookie.secure())) {
                return "the sign-in page emitted a cookie without the Secure attribute";
            }

            String sessionCookieFailure = exactCookieSecurityFailure(
                    cookies,
                    SESSION_COOKIE_NAME,
                    "Lax",
                    "session");
            if (sessionCookieFailure != null) {
                return sessionCookieFailure;
            }
            return optionalCookieSecurityFailure(cookies, FLASH_COOKIE_NAME, "Strict", "JSF flash");
        }

        private static String exactCookieSecurityFailure(
                List<ResponseCookieMetadata> cookies,
                String expectedCookieName,
                String expectedSameSite,
                String cookieDescription) {
            List<ResponseCookieMetadata> matchingCookies = cookies.stream()
                    .filter(cookie -> cookie.name().equals(expectedCookieName))
                    .toList();
            if (matchingCookies.size() != 1) {
                return "the sign-in page did not emit exactly one expected " + cookieDescription + " cookie";
            }
            ResponseCookieMetadata cookie = matchingCookies.getFirst();
            if (!cookie.secure()
                    || !cookie.httpOnly()
                    || !expectedSameSite.equalsIgnoreCase(cookie.sameSite())) {
                return "the sign-in page " + cookieDescription + " cookie attributes did not match the security contract";
            }
            return null;
        }

        private static String optionalCookieSecurityFailure(
                List<ResponseCookieMetadata> cookies,
                String expectedCookieName,
                String expectedSameSite,
                String cookieDescription) {
            List<ResponseCookieMetadata> matchingCookies = cookies.stream()
                    .filter(cookie -> cookie.name().equals(expectedCookieName))
                    .toList();
            if (matchingCookies.isEmpty()) {
                return null;
            }
            if (matchingCookies.size() != 1) {
                return "the sign-in page emitted an ambiguous number of " + cookieDescription + " cookies";
            }
            ResponseCookieMetadata cookie = matchingCookies.getFirst();
            if (!cookie.secure()
                    || !cookie.httpOnly()
                    || !expectedSameSite.equalsIgnoreCase(cookie.sameSite())) {
                return "the sign-in page " + cookieDescription + " cookie attributes did not match the security contract";
            }
            return null;
        }

        private static String dynamicSecurityPolicyFailure(
                String responseDescription,
                EndpointResponse response,
                boolean requireNoStore) {
            EndpointHeaders headers = response.headers();
            String exactHeaderFailure = exactHeaderFailure(
                    responseDescription,
                    "Content-Security-Policy",
                    headers.contentSecurityPolicy(),
                    CONTENT_SECURITY_POLICY);
            if (exactHeaderFailure != null) {
                return exactHeaderFailure;
            }
            exactHeaderFailure = exactHeaderFailure(
                    responseDescription,
                    "Permissions-Policy",
                    headers.permissionsPolicy(),
                    PERMISSIONS_POLICY);
            if (exactHeaderFailure != null) {
                return exactHeaderFailure;
            }
            exactHeaderFailure = exactHeaderFailure(
                    responseDescription,
                    "Strict-Transport-Security",
                    headers.strictTransportSecurity(),
                    STRICT_TRANSPORT_SECURITY);
            if (exactHeaderFailure != null) {
                return exactHeaderFailure;
            }
            exactHeaderFailure = exactHeaderFailure(
                    responseDescription,
                    "X-Frame-Options",
                    headers.frameOptions(),
                    "DENY");
            if (exactHeaderFailure != null) {
                return exactHeaderFailure;
            }
            exactHeaderFailure = exactHeaderFailure(
                    responseDescription,
                    "X-Content-Type-Options",
                    headers.contentTypeOptions(),
                    "nosniff");
            if (exactHeaderFailure != null) {
                return exactHeaderFailure;
            }
            exactHeaderFailure = exactHeaderFailure(
                    responseDescription,
                    "Referrer-Policy",
                    headers.referrerPolicy(),
                    "strict-origin-when-cross-origin");
            if (exactHeaderFailure != null) {
                return exactHeaderFailure;
            }
            if (requireNoStore && !containsCacheDirective(headers.cacheControl(), "no-store")) {
                return "the " + responseDescription + " did not carry the expected no-store cache policy";
            }
            return null;
        }

        private static String exactHeaderFailure(
                String responseDescription,
                String headerName,
                List<String> actualValues,
                String expectedValue) {
            if (!actualValues.equals(List.of(expectedValue))) {
                return "the " + responseDescription + " did not carry exactly one expected " + headerName + " header";
            }
            return null;
        }

        private static boolean containsCacheDirective(List<String> headerValues, String expectedDirective) {
            return headerValues.stream()
                    .flatMap(headerValue -> Pattern.compile(",").splitAsStream(headerValue))
                    .map(String::trim)
                    .anyMatch(directive -> directive.equalsIgnoreCase(expectedDirective));
        }

        private EndpointResponse request(String path, long deadlineNanos)
                throws IOException, InterruptedException, VerificationWindowExpired {
            long remainingNanos = remainingNanos(deadlineNanos);
            if (remainingNanos <= 0) {
                throw new VerificationWindowExpired();
            }

            Duration requestTimeout = Duration.ofNanos(Math.min(MAXIMUM_REQUEST_TIMEOUT.toNanos(), remainingNanos));
            return endpointClient.get(configuration.baseUri().resolve(path), requestTimeout);
        }

        private long remainingNanos(long deadlineNanos) {
            return deadlineNanos - clock.nanoTime();
        }

        private static String exactPlainTextFailure(
                String endpointName,
                EndpointResponse response,
                String expectedBody) {
            if (response.statusCode() != 200) {
                return "the " + endpointName + " endpoint returned HTTP " + response.statusCode();
            }
            if (response.bodyTooLarge()) {
                return "the " + endpointName + " endpoint returned an oversized response";
            }
            if (!response.hasContentType("text/plain")) {
                return "the " + endpointName + " endpoint returned a non-plain-text response";
            }
            if (!response.body().equals(expectedBody)) {
                return "the " + endpointName + " endpoint body did not match its contract";
            }
            return null;
        }

        private static String htmlPageFailure(String pageName, EndpointResponse response, String expectedMarker) {
            if (response.statusCode() != 200) {
                return "the " + pageName + " returned HTTP " + response.statusCode();
            }
            if (response.bodyTooLarge()) {
                return "the " + pageName + " returned an oversized response";
            }
            if (!response.hasContentType("text/html")) {
                return "the " + pageName + " returned a non-HTML response";
            }
            if (!response.body().contains(expectedMarker)) {
                return "the " + pageName + " did not contain its stable page marker";
            }
            return null;
        }
    }

    private record VerificationConfiguration(
            URI baseUri,
            String expectedRevision,
            Duration verificationTimeout,
            Duration pollInterval) {

        private static VerificationConfiguration parse(String[] arguments) {
            if (arguments.length == 0 || arguments.length % 2 != 0) {
                throw new InputException("Options must be supplied as name-value pairs.");
            }

            Map<String, String> optionValues = new HashMap<>();
            for (int argumentIndex = 0; argumentIndex < arguments.length; argumentIndex += 2) {
                String optionName = arguments[argumentIndex];
                if (!isSupportedOption(optionName)) {
                    throw new InputException("An unknown option was supplied.");
                }
                if (optionValues.putIfAbsent(optionName, arguments[argumentIndex + 1]) != null) {
                    throw new InputException("An option was supplied more than once.");
                }
            }

            String configuredBaseUrl = requiredOption(optionValues, "--base-url");
            String configuredExpectedRevision = requiredOption(optionValues, "--expected-revision");
            URI baseUri = parseBaseUri(configuredBaseUrl);
            String expectedRevision = parseRevision(configuredExpectedRevision);
            Duration timeout = parseSeconds(
                    optionValues.get("--timeout-seconds"),
                    DEFAULT_VERIFICATION_TIMEOUT,
                    MAXIMUM_VERIFICATION_TIMEOUT,
                    "Verification timeout");
            Duration pollInterval = parseSeconds(
                    optionValues.get("--poll-interval-seconds"),
                    DEFAULT_POLL_INTERVAL,
                    MAXIMUM_POLL_INTERVAL,
                    "Poll interval");
            if (pollInterval.compareTo(timeout) > 0) {
                throw new InputException("Poll interval cannot exceed the verification timeout.");
            }
            return new VerificationConfiguration(baseUri, expectedRevision, timeout, pollInterval);
        }

        private static boolean isSupportedOption(String optionName) {
            return optionName.equals("--base-url")
                    || optionName.equals("--expected-revision")
                    || optionName.equals("--timeout-seconds")
                    || optionName.equals("--poll-interval-seconds");
        }

        private static String requiredOption(Map<String, String> optionValues, String optionName) {
            String optionValue = optionValues.get(optionName);
            if (optionValue == null || optionValue.isBlank()) {
                throw new InputException("A required option is missing.");
            }
            return optionValue;
        }

        private static URI parseBaseUri(String configuredBaseUrl) {
            URI configuredUri;
            try {
                configuredUri = new URI(configuredBaseUrl);
            } catch (URISyntaxException exception) {
                throw new InputException("Base URL must be a valid absolute URL.");
            }

            String scheme = configuredUri.getScheme();
            String host = configuredUri.getHost();
            String rawPath = configuredUri.getRawPath();
            boolean rootPath = rawPath == null || rawPath.isEmpty() || rawPath.equals("/");
            if (scheme == null
                    || host == null
                    || configuredUri.getRawUserInfo() != null
                    || configuredUri.getRawQuery() != null
                    || configuredUri.getRawFragment() != null
                    || !rootPath
                    || configuredUri.getPort() > 65_535) {
                throw new InputException(
                        "Base URL must contain only an HTTP or HTTPS origin without credentials, a path, a query, or a fragment.");
            }

            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (!normalizedScheme.equals("https")
                    && !(normalizedScheme.equals("http") && isLiteralLoopbackHost(normalizedHost))) {
                throw new InputException("Base URL must use HTTPS, except for literal loopback development hosts.");
            }

            try {
                return new URI(
                        normalizedScheme,
                        null,
                        normalizedHost,
                        configuredUri.getPort(),
                        null,
                        null,
                        null);
            } catch (URISyntaxException exception) {
                throw new InputException("Base URL could not be normalized safely.");
            }
        }

        private static boolean isLiteralLoopbackHost(String host) {
            return host.equals("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("::1")
                    || host.equals("[::1]");
        }

        private static String parseRevision(String configuredRevision) {
            if (!FULL_GIT_COMMIT_SHA.matcher(configuredRevision).matches()) {
                throw new InputException("Expected revision must be one complete 40-character hexadecimal Git SHA.");
            }
            return configuredRevision.toLowerCase(Locale.ROOT);
        }

        private static Duration parseSeconds(
                String configuredSeconds,
                Duration defaultDuration,
                Duration maximumDuration,
                String optionDescription) {
            if (configuredSeconds == null) {
                return defaultDuration;
            }

            long seconds;
            try {
                seconds = Long.parseLong(configuredSeconds);
            } catch (NumberFormatException exception) {
                throw new InputException(optionDescription + " must be a whole number of seconds.");
            }
            if (seconds < 1 || seconds > maximumDuration.toSeconds()) {
                throw new InputException(optionDescription + " is outside its allowed range.");
            }
            return Duration.ofSeconds(seconds);
        }
    }

    private interface EndpointClient {
        EndpointResponse get(URI uri, Duration timeout) throws IOException, InterruptedException;
    }

    private static final class JavaHttpEndpointClient implements EndpointClient {
        private final HttpClient httpClient;

        private JavaHttpEndpointClient(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public EndpointResponse get(URI uri, Duration timeout) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", "text/plain, text/html;q=0.9")
                    .header("User-Agent", "event-calendar-production-verifier")
                    .GET()
                    .build();
            LimitedResponseBody responseBody = new LimitedResponseBody();
            HttpResponse<Void> response = httpClient.send(
                    request,
                    ignored -> HttpResponse.BodySubscribers.ofByteArrayConsumer(responseBody::accept));

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            return new EndpointResponse(
                    response.statusCode(),
                    responseBody.text(),
                    contentType,
                    responseBody.tooLarge,
                    EndpointHeaders.from(response.headers()));
        }
    }

    private static final class LimitedResponseBody {
        private final ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
        private boolean tooLarge;

        private void accept(Optional<byte[]> responseChunk) {
            if (responseChunk.isEmpty() || tooLarge) {
                return;
            }

            byte[] chunkBytes = responseChunk.orElseThrow();
            if (chunkBytes.length > MAXIMUM_RESPONSE_BYTES - responseBytes.size()) {
                tooLarge = true;
                responseBytes.reset();
                return;
            }
            responseBytes.writeBytes(chunkBytes);
        }

        private String text() {
            return tooLarge ? "" : responseBytes.toString(StandardCharsets.UTF_8);
        }
    }

    private record EndpointResponse(
            int statusCode,
            String body,
            String contentType,
            boolean bodyTooLarge,
            EndpointHeaders headers) {
        private EndpointResponse {
            Objects.requireNonNull(body);
            Objects.requireNonNull(contentType);
            Objects.requireNonNull(headers);
        }

        private List<String> deploymentRevisionHeaders() {
            return headers.deploymentRevision();
        }

        private boolean hasContentType(String expectedMediaType) {
            String normalizedContentType = contentType.toLowerCase(Locale.ROOT);
            return normalizedContentType.equals(expectedMediaType)
                    || normalizedContentType.startsWith(expectedMediaType + ";");
        }
    }

    private record EndpointHeaders(
            List<String> deploymentRevision,
            List<String> contentSecurityPolicy,
            List<String> permissionsPolicy,
            List<String> strictTransportSecurity,
            List<String> frameOptions,
            List<String> contentTypeOptions,
            List<String> referrerPolicy,
            List<String> cacheControl,
            RedirectMetadata redirect,
            List<ResponseCookieMetadata> cookies) {
        private EndpointHeaders {
            deploymentRevision = List.copyOf(deploymentRevision);
            contentSecurityPolicy = List.copyOf(contentSecurityPolicy);
            permissionsPolicy = List.copyOf(permissionsPolicy);
            strictTransportSecurity = List.copyOf(strictTransportSecurity);
            frameOptions = List.copyOf(frameOptions);
            contentTypeOptions = List.copyOf(contentTypeOptions);
            referrerPolicy = List.copyOf(referrerPolicy);
            cacheControl = List.copyOf(cacheControl);
            Objects.requireNonNull(redirect);
            cookies = List.copyOf(cookies);
        }

        private static EndpointHeaders from(java.net.http.HttpHeaders httpHeaders) {
            List<String> redirectHeaders = httpHeaders.allValues("Location");
            URI redirectLocation = redirectHeaders.size() == 1
                    ? safelyParseUri(redirectHeaders.getFirst())
                    : null;
            List<ResponseCookieMetadata> responseCookies = httpHeaders.allValues("Set-Cookie").stream()
                    .map(ResponseCookieMetadata::from)
                    .toList();
            return new EndpointHeaders(
                    httpHeaders.allValues(DEPLOYMENT_REVISION_HEADER),
                    httpHeaders.allValues("Content-Security-Policy"),
                    httpHeaders.allValues("Permissions-Policy"),
                    httpHeaders.allValues("Strict-Transport-Security"),
                    httpHeaders.allValues("X-Frame-Options"),
                    httpHeaders.allValues("X-Content-Type-Options"),
                    httpHeaders.allValues("Referrer-Policy"),
                    httpHeaders.allValues("Cache-Control"),
                    new RedirectMetadata(redirectHeaders.size(), redirectLocation),
                    responseCookies);
        }

        private static URI safelyParseUri(String value) {
            try {
                return new URI(value);
            } catch (URISyntaxException exception) {
                return null;
            }
        }
    }

    private record RedirectMetadata(int headerCount, URI location) {
        private RedirectMetadata {
            if (headerCount < 0) {
                throw new IllegalArgumentException("Redirect header count cannot be negative.");
            }
        }

        private static RedirectMetadata none() {
            return new RedirectMetadata(0, null);
        }
    }

    private record ResponseCookieMetadata(
            String name,
            boolean secure,
            boolean httpOnly,
            String sameSite) {
        private ResponseCookieMetadata {
            Objects.requireNonNull(name);
        }

        private static ResponseCookieMetadata from(String setCookieHeader) {
            String[] segments = setCookieHeader.split(";", -1);
            int cookieNameSeparator = segments[0].indexOf('=');
            String cookieName = cookieNameSeparator > 0
                    ? segments[0].substring(0, cookieNameSeparator).trim()
                    : "";
            boolean secure = false;
            boolean httpOnly = false;
            String sameSite = null;
            boolean sameSiteSeen = false;
            boolean ambiguousSameSite = false;
            for (int segmentIndex = 1; segmentIndex < segments.length; segmentIndex++) {
                String attribute = segments[segmentIndex].trim();
                int attributeValueSeparator = attribute.indexOf('=');
                String attributeName = attributeValueSeparator >= 0
                        ? attribute.substring(0, attributeValueSeparator).trim()
                        : attribute;
                String attributeValue = attributeValueSeparator >= 0
                        ? attribute.substring(attributeValueSeparator + 1).trim()
                        : "";
                if (attributeName.equalsIgnoreCase("Secure") && attributeValueSeparator < 0) {
                    secure = true;
                } else if (attributeName.equalsIgnoreCase("HttpOnly") && attributeValueSeparator < 0) {
                    httpOnly = true;
                } else if (attributeName.equalsIgnoreCase("SameSite")) {
                    if (sameSiteSeen) {
                        ambiguousSameSite = true;
                    } else {
                        sameSite = attributeValue;
                        sameSiteSeen = true;
                    }
                }
            }
            if (ambiguousSameSite) {
                sameSite = null;
            }
            return new ResponseCookieMetadata(cookieName, secure, httpOnly, sameSite);
        }
    }

    @FunctionalInterface
    private interface NanosecondClock {
        long nanoTime();
    }

    @FunctionalInterface
    private interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final class InputException extends IllegalArgumentException {
        private InputException(String message) {
            super(message);
        }
    }

    private static final class VerificationException extends RuntimeException {
        private VerificationException(String message) {
            super(message);
        }
    }

    private static final class VerificationWindowExpired extends Exception {
    }

    private static void runSelfTests(PrintStream output) throws Exception {
        List<ThrowingTest> selfTests = List.of(
                ProductionDeploymentVerifier::validConfigurationIsNormalized,
                ProductionDeploymentVerifier::unsafeAndMalformedConfigurationIsRejected,
                ProductionDeploymentVerifier::cookieMetadataRecognizesOnlyExactSecurityAttributes,
                ProductionDeploymentVerifier::exactDeploymentAndSmokeContractsPassWithoutWaiting,
                ProductionDeploymentVerifier::staleRevisionAndUnhealthyDeploymentKeepWaiting,
                ProductionDeploymentVerifier::incorrectHttpSemanticsAndMidSmokeDeploymentChangesCannotPass,
                ProductionDeploymentVerifier::weakenedProxyAndSecurityContractsCannotPass,
                ProductionDeploymentVerifier::transientNetworkFailureCanRecoverWithinTheWindow,
                ProductionDeploymentVerifier::verificationStopsAtItsDeadline);
        for (ThrowingTest selfTest : selfTests) {
            selfTest.run();
        }
        output.println("Production deployment verifier self-tests passed: " + selfTests.size() + ".");
    }

    private static void validConfigurationIsNormalized() {
        VerificationConfiguration configuration = VerificationConfiguration.parse(new String[] {
            "--expected-revision", "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
            "--base-url", "HTTPS://Calendar.Social/",
            "--poll-interval-seconds", "3",
            "--timeout-seconds", "120"
        });

        requireEquals(URI.create("https://calendar.social"), configuration.baseUri(), "normalized base URI");
        requireEquals(
                "abcdef0123456789abcdef0123456789abcdef01",
                configuration.expectedRevision(),
                "normalized revision");
        requireEquals(Duration.ofSeconds(120), configuration.verificationTimeout(), "verification timeout");
        requireEquals(Duration.ofSeconds(3), configuration.pollInterval(), "poll interval");

        VerificationConfiguration loopbackConfiguration = VerificationConfiguration.parse(new String[] {
            "--base-url", "http://[::1]:9080",
            "--expected-revision", "abcdef0123456789abcdef0123456789abcdef01"
        });
        requireEquals(URI.create("http://[::1]:9080"), loopbackConfiguration.baseUri(), "IPv6 loopback URI");
    }

    private static void unsafeAndMalformedConfigurationIsRejected() {
        String validRevision = "0123456789abcdef0123456789abcdef01234567";
        List<String[]> invalidArguments = List.of(
                new String[] {"--base-url", "http://calendar.social", "--expected-revision", validRevision},
                new String[] {"--base-url", "https://user:secret@calendar.social", "--expected-revision", validRevision},
                new String[] {"--base-url", "https://calendar.social/app", "--expected-revision", validRevision},
                new String[] {"--base-url", "https://calendar.social?token=secret", "--expected-revision", validRevision},
                new String[] {"--base-url", "https://calendar.social#fragment", "--expected-revision", validRevision},
                new String[] {"--base-url", "https://calendar.social:99999", "--expected-revision", validRevision},
                new String[] {"--base-url", "https://calendar.social", "--expected-revision", "abc123"},
                new String[] {"--base-url", "https://calendar.social", "--expected-revision", validRevision,
                    "--timeout-seconds", "0"},
                new String[] {"--base-url", "https://calendar.social", "--expected-revision", validRevision,
                    "--timeout-seconds", "5", "--poll-interval-seconds", "6"},
                new String[] {"--base-url", "https://calendar.social", "--expected-revision", validRevision,
                    "--base-url", "https://example.com"},
                new String[] {"--base-url", "https://calendar.social", "--expected-revision", validRevision,
                    "--unsupported", "value"});

        for (String[] arguments : invalidArguments) {
            requireThrows(InputException.class, () -> VerificationConfiguration.parse(arguments));
        }
    }

    private static void exactDeploymentAndSmokeContractsPassWithoutWaiting() throws Exception {
        TestFixture fixture = new TestFixture(Duration.ofSeconds(30), Duration.ofSeconds(2));
        fixture.endpointClient.addSuccessfulSmokeCycle(fixture.expectedRevision);

        fixture.verifier().verify();

        requireEquals(List.of(
                HEALTH_PATH,
                HOME_PATH,
                SIGN_IN_PATH,
                PROTECTED_CALENDARS_PATH,
                LEGACY_CALENDAR_PATH,
                HEALTH_PATH), fixture.endpointClient.requestedPaths, "requested paths");
        requireEquals(List.of(), fixture.sleeper.sleepDurations, "unexpected waits");
        fixture.endpointClient.requireAllOutcomesConsumed();
    }

    private static void staleRevisionAndUnhealthyDeploymentKeepWaiting() throws Exception {
        TestFixture fixture = new TestFixture(Duration.ofSeconds(30), Duration.ofSeconds(2));
        fixture.endpointClient.addResponse(
                HEALTH_PATH,
                healthResponse(200, "ok", "1111111111111111111111111111111111111111"));
        fixture.endpointClient.addResponse(HEALTH_PATH, healthResponse(503, "unavailable", fixture.expectedRevision));
        fixture.endpointClient.addSuccessfulSmokeCycle(fixture.expectedRevision);

        fixture.verifier().verify();

        requireEquals(
                List.of(Duration.ofSeconds(2), Duration.ofSeconds(2)),
                fixture.sleeper.sleepDurations,
                "poll waits");
        fixture.endpointClient.requireAllOutcomesConsumed();
    }

    private static void incorrectHttpSemanticsAndMidSmokeDeploymentChangesCannotPass() throws Exception {
        TestFixture fixture = new TestFixture(Duration.ofSeconds(30), Duration.ofSeconds(1));
        fixture.endpointClient.addResponse(
                HEALTH_PATH,
                healthResponse(200, "ok", fixture.expectedRevision));
        fixture.endpointClient.addResponse(HOME_PATH, htmlResponse(302, HOME_PAGE_MARKER));
        fixture.endpointClient.addResponse(
                HEALTH_PATH,
                new EndpointResponse(
                        200,
                        "ok",
                        "text/html",
                        false,
                        dynamicHeaders(List.of(fixture.expectedRevision), RedirectMetadata.none(), List.of())));
        fixture.endpointClient.addSmokeCycle(
                fixture.expectedRevision,
                "1111111111111111111111111111111111111111");
        fixture.endpointClient.addSuccessfulSmokeCycle(fixture.expectedRevision);

        fixture.verifier().verify();

        requireEquals(
                List.of(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                fixture.sleeper.sleepDurations,
                "HTTP contract waits");
        fixture.endpointClient.requireAllOutcomesConsumed();
    }

    private static void cookieMetadataRecognizesOnlyExactSecurityAttributes() {
        ResponseCookieMetadata hardenedCookie = ResponseCookieMetadata.from(
                "JSESSIONID=sensitive-value; Path=/; Secure; HttpOnly; SameSite=Lax");
        requireEquals(SESSION_COOKIE_NAME, hardenedCookie.name(), "parsed session cookie name");
        require(hardenedCookie.secure(), "Secure cookie attribute");
        require(hardenedCookie.httpOnly(), "HttpOnly cookie attribute");
        requireEquals("Lax", hardenedCookie.sameSite(), "SameSite cookie attribute");

        ResponseCookieMetadata lookalikeAttributes = ResponseCookieMetadata.from(
                "JSESSIONID=value-containing-Secure; Secure=true; HttpOnly=false; SameSiteX=Strict");
        require(!lookalikeAttributes.secure(), "Secure lookalikes must not pass");
        require(!lookalikeAttributes.httpOnly(), "HttpOnly lookalikes must not pass");
        requireEquals(null, lookalikeAttributes.sameSite(), "SameSite lookalikes must not pass");

        ResponseCookieMetadata ambiguousSameSite = ResponseCookieMetadata.from(
                "JSESSIONID=value; Secure; HttpOnly; SameSite=None; SameSite=Lax");
        requireEquals(null, ambiguousSameSite.sameSite(), "duplicate SameSite attributes must not pass");

        requireEquals(
                null,
                ProductionVerifier.signInCookieSecurityFailure(
                        signInResponse(List.of(hardenedSessionCookie()))),
                "optional flash cookie absence");
    }

    private static void weakenedProxyAndSecurityContractsCannotPass() throws Exception {
        TestFixture fixture = new TestFixture(Duration.ofSeconds(30), Duration.ofSeconds(1));

        fixture.endpointClient.addResponse(
                HEALTH_PATH,
                healthResponse(200, "ok", fixture.expectedRevision));
        fixture.endpointClient.addResponse(HOME_PATH, htmlResponse(200, HOME_PAGE_MARKER));
        fixture.endpointClient.addResponse(
                SIGN_IN_PATH,
                signInResponse(List.of(
                        new ResponseCookieMetadata(SESSION_COOKIE_NAME, false, true, "Lax"),
                        hardenedFlashCookie())));

        fixture.endpointClient.addResponse(
                HEALTH_PATH,
                healthResponse(200, "ok", fixture.expectedRevision));
        fixture.endpointClient.addResponse(HOME_PATH, htmlResponse(200, HOME_PAGE_MARKER));
        fixture.endpointClient.addResponse(SIGN_IN_PATH, signInResponse(hardenedSignInCookies()));
        fixture.endpointClient.addResponse(
                PROTECTED_CALENDARS_PATH,
                protectedRedirectResponse(URI.create("http://calendar.social/login")));

        fixture.endpointClient.addResponse(
                HEALTH_PATH,
                healthResponse(200, "ok", fixture.expectedRevision));
        fixture.endpointClient.addResponse(
                HOME_PATH,
                new EndpointResponse(
                        200,
                        HOME_PAGE_MARKER,
                        "text/html;charset=UTF-8",
                        false,
                        dynamicHeadersWithoutContentSecurityPolicy()));

        fixture.endpointClient.addSuccessfulSmokeCycle(fixture.expectedRevision);

        fixture.verifier().verify();

        requireEquals(
                List.of(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                fixture.sleeper.sleepDurations,
                "proxy and security contract waits");
        fixture.endpointClient.requireAllOutcomesConsumed();
    }

    private static void transientNetworkFailureCanRecoverWithinTheWindow() throws Exception {
        TestFixture fixture = new TestFixture(Duration.ofSeconds(30), Duration.ofSeconds(1));
        fixture.endpointClient.addFailure(HEALTH_PATH);
        fixture.endpointClient.addSuccessfulSmokeCycle(fixture.expectedRevision);

        fixture.verifier().verify();

        requireEquals(List.of(Duration.ofSeconds(1)), fixture.sleeper.sleepDurations, "network recovery wait");
        fixture.endpointClient.requireAllOutcomesConsumed();
    }

    private static void verificationStopsAtItsDeadline() {
        TestFixture fixture = new TestFixture(Duration.ofSeconds(5), Duration.ofSeconds(2));
        fixture.endpointClient.addResponse(HEALTH_PATH, healthResponse(200, "ok", "malformed"));
        fixture.endpointClient.addResponse(HEALTH_PATH, healthResponse(200, "ok", "malformed"));
        fixture.endpointClient.addResponse(HEALTH_PATH, healthResponse(200, "ok", "malformed"));

        VerificationException exception = requireThrows(VerificationException.class, () -> fixture.verifier().verify());

        require(exception.getMessage().contains("within 5 seconds"), "deadline failure must identify its bound");
        requireEquals(
                List.of(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1)),
                fixture.sleeper.sleepDurations,
                "bounded waits");
        requireEquals(Duration.ofSeconds(5).toNanos(), fixture.clock.nanoTime(), "deadline clock");
        fixture.endpointClient.requireAllOutcomesConsumed();
    }

    private static EndpointResponse healthResponse(int statusCode, String body, String deploymentRevision) {
        return new EndpointResponse(
                statusCode,
                body,
                "text/plain; charset=UTF-8",
                false,
                dynamicHeaders(List.of(deploymentRevision), RedirectMetadata.none(), List.of()));
    }

    private static EndpointResponse htmlResponse(int statusCode, String body) {
        return new EndpointResponse(
                statusCode,
                body,
                "text/html;charset=UTF-8",
                false,
                dynamicHeaders(List.of(), RedirectMetadata.none(), List.of()));
    }

    private static EndpointResponse signInResponse(List<ResponseCookieMetadata> cookies) {
        return new EndpointResponse(
                200,
                SIGN_IN_PAGE_MARKER + PRIMEFACES_SECURE_COOKIES_MARKER,
                "text/html;charset=UTF-8",
                false,
                dynamicHeaders(List.of(), RedirectMetadata.none(), cookies));
    }

    private static EndpointResponse protectedRedirectResponse(URI location) {
        return new EndpointResponse(
                302,
                "",
                "text/html;charset=UTF-8",
                false,
                dynamicHeaders(
                        List.of(),
                        new RedirectMetadata(1, location),
                        List.of()));
    }

    private static EndpointHeaders dynamicHeaders(
            List<String> deploymentRevision,
            RedirectMetadata redirect,
            List<ResponseCookieMetadata> cookies) {
        return new EndpointHeaders(
                deploymentRevision,
                List.of(CONTENT_SECURITY_POLICY),
                List.of(PERMISSIONS_POLICY),
                List.of(STRICT_TRANSPORT_SECURITY),
                List.of("DENY"),
                List.of("nosniff"),
                List.of("strict-origin-when-cross-origin"),
                List.of("no-store"),
                redirect,
                cookies);
    }

    private static EndpointHeaders dynamicHeadersWithoutContentSecurityPolicy() {
        return new EndpointHeaders(
                List.of(),
                List.of(),
                List.of(PERMISSIONS_POLICY),
                List.of(STRICT_TRANSPORT_SECURITY),
                List.of("DENY"),
                List.of("nosniff"),
                List.of("strict-origin-when-cross-origin"),
                List.of("no-store"),
                RedirectMetadata.none(),
                List.of());
    }

    private static List<ResponseCookieMetadata> hardenedSignInCookies() {
        return List.of(
                hardenedSessionCookie(),
                hardenedFlashCookie());
    }

    private static ResponseCookieMetadata hardenedSessionCookie() {
        return new ResponseCookieMetadata(SESSION_COOKIE_NAME, true, true, "Lax");
    }

    private static ResponseCookieMetadata hardenedFlashCookie() {
        return new ResponseCookieMetadata(FLASH_COOKIE_NAME, true, true, "Strict");
    }

    private static void require(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError("Self-test failed: " + description + ".");
        }
    }

    private static void requireEquals(Object expected, Object actual, String description) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Self-test failed for " + description + ".");
        }
    }

    private static <ExpectedException extends Throwable> ExpectedException requireThrows(
            Class<ExpectedException> expectedType,
            ThrowingTest action) {
        try {
            action.run();
        } catch (Throwable exception) {
            if (expectedType.isInstance(exception)) {
                return expectedType.cast(exception);
            }
            throw new AssertionError("Self-test threw an unexpected exception type.", exception);
        }
        throw new AssertionError("Self-test did not throw the expected exception.");
    }

    @FunctionalInterface
    private interface ThrowingTest {
        void run() throws Exception;
    }

    private static final class TestFixture {
        private final String expectedRevision = "0123456789abcdef0123456789abcdef01234567";
        private final VerificationConfiguration configuration;
        private final FakeNanosecondClock clock = new FakeNanosecondClock();
        private final FakeSleeper sleeper = new FakeSleeper(clock);
        private final FakeEndpointClient endpointClient = new FakeEndpointClient();

        private TestFixture(Duration verificationTimeout, Duration pollInterval) {
            configuration = new VerificationConfiguration(
                    URI.create("https://calendar.social"),
                    expectedRevision,
                    verificationTimeout,
                    pollInterval);
        }

        private ProductionVerifier verifier() {
            return new ProductionVerifier(configuration, endpointClient, clock, sleeper);
        }
    }

    private static final class FakeNanosecondClock implements NanosecondClock {
        private long currentNanos;

        @Override
        public long nanoTime() {
            return currentNanos;
        }

        private void advance(Duration duration) {
            currentNanos += duration.toNanos();
        }
    }

    private static final class FakeSleeper implements Sleeper {
        private final FakeNanosecondClock clock;
        private final List<Duration> sleepDurations = new ArrayList<>();

        private FakeSleeper(FakeNanosecondClock clock) {
            this.clock = clock;
        }

        @Override
        public void sleep(Duration duration) {
            sleepDurations.add(duration);
            clock.advance(duration);
        }
    }

    private static final class FakeEndpointClient implements EndpointClient {
        private final Deque<ScriptedOutcome> outcomes = new ArrayDeque<>();
        private final List<String> requestedPaths = new ArrayList<>();

        @Override
        public EndpointResponse get(URI uri, Duration timeout) throws IOException {
            require(!timeout.isZero() && !timeout.isNegative(), "request timeout must be positive");
            ScriptedOutcome outcome = outcomes.pollFirst();
            if (outcome == null) {
                throw new AssertionError("No scripted response remained for a production request.");
            }
            requestedPaths.add(uri.getPath());
            requireEquals(outcome.expectedPath, uri.getPath(), "production request path");
            if (outcome.failure != null) {
                throw outcome.failure;
            }
            return outcome.response;
        }

        private void addResponse(String expectedPath, EndpointResponse response) {
            outcomes.addLast(new ScriptedOutcome(expectedPath, response, null));
        }

        private void addFailure(String expectedPath) {
            outcomes.addLast(new ScriptedOutcome(
                    expectedPath,
                    null,
                    new IOException("Synthetic network failure with no production data.")));
        }

        private void addSuccessfulSmokeCycle(String expectedRevision) {
            addSmokeCycle(expectedRevision, expectedRevision);
        }

        private void addSmokeCycle(String initialRevision, String finalRevision) {
            addResponse(HEALTH_PATH, healthResponse(200, "ok", initialRevision));
            addResponse(HOME_PATH, htmlResponse(200, HOME_PAGE_MARKER));
            addResponse(SIGN_IN_PATH, signInResponse(hardenedSignInCookies()));
            addResponse(
                    PROTECTED_CALENDARS_PATH,
                    protectedRedirectResponse(URI.create(SIGN_IN_PATH)));
            addResponse(LEGACY_CALENDAR_PATH, htmlResponse(404, "Not found"));
            addResponse(HEALTH_PATH, healthResponse(200, "ok", finalRevision));
        }

        private void requireAllOutcomesConsumed() {
            require(outcomes.isEmpty(), "all scripted production responses must be consumed");
        }
    }

    private record ScriptedOutcome(String expectedPath, EndpointResponse response, IOException failure) {
    }
}
