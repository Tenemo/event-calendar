package app.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DeploymentRevisionTest {
    private static final String LOWERCASE_REVISION = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void validFullGitCommitIsNormalizedForStableComparison() {
        Optional<String> deploymentRevision = DeploymentRevision.fromResource(resourceContaining(
                LOWERCASE_REVISION.toUpperCase(Locale.ROOT)));

        assertEquals(Optional.of(LOWERCASE_REVISION), deploymentRevision);
    }

    @Test
    void missingOrMalformedValuesAreUnavailable() {
        for (String malformedRevision : new String[] {
            "",
            " ",
            "0123456789abcdef0123456789abcdef0123456",
            "0123456789abcdef0123456789abcdef012345678",
            "g123456789abcdef0123456789abcdef01234567",
            LOWERCASE_REVISION + "\n"
        }) {
            assertTrue(
                    DeploymentRevision.fromResource(resourceContaining(malformedRevision)).isEmpty(),
                    () -> "Expected malformed deployment revision to be unavailable: " + printable(malformedRevision));
        }

        assertTrue(DeploymentRevision.fromResource(() -> null).isEmpty());
    }

    @Test
    void artifactReadFailureIsNotMisreportedAsAMissingRevision() {
        IOException readFailure = new IOException("Synthetic artifact read failure.");
        InputStream failingResource = new InputStream() {
            @Override
            public int read() throws IOException {
                throw readFailure;
            }
        };

        IllegalStateException thrownFailure = assertThrows(
                IllegalStateException.class,
                () -> DeploymentRevision.fromResource(() -> failingResource));

        assertEquals(readFailure, thrownFailure.getCause());
    }

    private static java.util.function.Supplier<InputStream> resourceContaining(String revision) {
        byte[] revisionBytes = revision.getBytes(StandardCharsets.US_ASCII);
        return () -> new ByteArrayInputStream(revisionBytes);
    }

    private static String printable(String value) {
        return value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
