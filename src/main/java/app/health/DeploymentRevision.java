package app.health;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

final class DeploymentRevision {
    static final String ARTIFACT_RESOURCE_PATH = "/META-INF/deployment-revision";

    private static final int FULL_GIT_COMMIT_SHA_LENGTH = 40;
    private static final Pattern FULL_GIT_COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{40}");

    private DeploymentRevision() {
    }

    static Optional<String> fromApplicationArtifact() {
        return fromResource(() -> DeploymentRevision.class.getResourceAsStream(ARTIFACT_RESOURCE_PATH));
    }

    static Optional<String> fromResource(Supplier<InputStream> resourceStream) {
        try (InputStream deploymentRevisionStream = resourceStream.get()) {
            if (deploymentRevisionStream == null) {
                return Optional.empty();
            }

            byte[] revisionBytes = deploymentRevisionStream.readNBytes(FULL_GIT_COMMIT_SHA_LENGTH + 1);
            if (revisionBytes.length != FULL_GIT_COMMIT_SHA_LENGTH) {
                return Optional.empty();
            }

            String configuredRevision = new String(revisionBytes, StandardCharsets.US_ASCII);
            if (!FULL_GIT_COMMIT_SHA.matcher(configuredRevision).matches()) {
                return Optional.empty();
            }
            return Optional.of(configuredRevision.toLowerCase(Locale.ROOT));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read the deployment revision from the application artifact.", exception);
        }
    }
}
