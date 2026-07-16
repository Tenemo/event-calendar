package app.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Optional;

@ApplicationScoped
public class ClientRequestSourceResolver {
    static final String RAILWAY_ENVIRONMENT_ID_ENVIRONMENT_VARIABLE = "RAILWAY_ENVIRONMENT_ID";

    private static final String RAILWAY_REAL_IP_HEADER = "X-Real-IP";
    private static final String UNKNOWN_SOURCE = "<unknown-source>";
    private static final int MAXIMUM_REAL_IP_HEADER_LENGTH = 64;
    private static final String RAILWAY_INGRESS_ADDRESS_PREFIX = "100.";

    private final boolean railwayEnvironment;

    public ClientRequestSourceResolver() {
        this(System.getenv(RAILWAY_ENVIRONMENT_ID_ENVIRONMENT_VARIABLE));
    }

    public ClientRequestSourceResolver(String railwayEnvironmentId) {
        railwayEnvironment = railwayEnvironmentId != null && !railwayEnvironmentId.isBlank();
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN_SOURCE;
        }

        String connectedSource = normalizeAddress(request.getRemoteAddr()).orElse(UNKNOWN_SOURCE);
        if (!railwayEnvironment || !connectedSource.startsWith(RAILWAY_INGRESS_ADDRESS_PREFIX)) {
            return connectedSource;
        }

        Enumeration<String> realIpHeaders = request.getHeaders(RAILWAY_REAL_IP_HEADER);
        if (realIpHeaders == null || !realIpHeaders.hasMoreElements()) {
            return connectedSource;
        }
        String realIpHeader = realIpHeaders.nextElement();
        if (realIpHeaders.hasMoreElements()
                || realIpHeader == null
                || realIpHeader.length() > MAXIMUM_REAL_IP_HEADER_LENGTH) {
            return connectedSource;
        }

        return normalizeAddress(realIpHeader).orElse(connectedSource);
    }

    private Optional<String> normalizeAddress(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }

        String candidate = address.trim();
        if (candidate.indexOf(':') >= 0) {
            return normalizeInternetProtocolVersionSixAddress(candidate);
        }
        return normalizeInternetProtocolVersionFourAddress(candidate);
    }

    private Optional<String> normalizeInternetProtocolVersionFourAddress(String candidate) {
        String[] addressParts = candidate.split("\\.", -1);
        if (addressParts.length != 4) {
            return Optional.empty();
        }

        StringBuilder normalizedAddress = new StringBuilder();
        for (int addressPartIndex = 0; addressPartIndex < addressParts.length; addressPartIndex++) {
            String addressPart = addressParts[addressPartIndex];
            if (addressPart.isEmpty()
                    || addressPart.length() > 3
                    || !addressPart.chars().allMatch(Character::isDigit)) {
                return Optional.empty();
            }
            int numericAddressPart;
            try {
                numericAddressPart = Integer.parseInt(addressPart);
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
            if (numericAddressPart > 255) {
                return Optional.empty();
            }
            if (addressPartIndex > 0) {
                normalizedAddress.append('.');
            }
            normalizedAddress.append(numericAddressPart);
        }
        return Optional.of(normalizedAddress.toString());
    }

    private Optional<String> normalizeInternetProtocolVersionSixAddress(String candidate) {
        if (candidate.length() > 45
                || !candidate.matches("[0-9A-Fa-f:.]+")) {
            return Optional.empty();
        }
        try {
            InetAddress parsedAddress = InetAddress.getByName(candidate);
            return Optional.of(parsedAddress.getHostAddress());
        } catch (UnknownHostException exception) {
            return Optional.empty();
        }
    }
}
