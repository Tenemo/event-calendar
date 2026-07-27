package app.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

final class WorkflowPipelineSafetyTest {
    private static final Path CODEQL_WORKFLOW =
            Path.of(".github", "workflows", "codeql.yml");
    private static final Path MAVEN_CONFIGURATION = Path.of("pom.xml");
    private static final Path PULL_REQUEST_WORKFLOW =
            Path.of(".github", "workflows", "pr-checks.yml");
    private static final Path PREVIEW_VERIFICATION_WORKFLOW =
            Path.of(".github", "workflows", "preview-verification.yml");

    @Test
    void everyRequiredShellPipelineEnablesPipeFailurePropagation() throws Exception {
        List<String> workflowLines = Files.readAllLines(PULL_REQUEST_WORKFLOW);

        assertPipelineEnablesPipeFailure(
                workflowLines,
                "docker image save \"$PRODUCTION_IMAGE_NAME\" | gzip --best");
        assertPipelineEnablesPipeFailure(
                workflowLines,
                "mise run image-scan 2>&1 | tee");
    }

    @Test
    void previewCredentialsUseTrustedVerifierAndDocumentThePreviewRecipientBoundary()
            throws Exception {
        String workflow = Files.readString(PREVIEW_VERIFICATION_WORKFLOW);

        assertTrue(workflow.contains("ref: ${{ github.event.repository.default_branch }}"));
        assertTrue(workflow.contains("github.event.deployment.creator.login == 'railway-app[bot]'"));
        assertTrue(workflow.contains("github.event.deployment.creator.id == 68434857"));
        assertTrue(workflow.contains("scripts/resolve-railway-preview-url.mjs"));
        assertTrue(workflow.contains("scripts/verify-production-deployment.java"));
        assertTrue(workflow.contains("verify-preview-deployment"));
        assertTrue(workflow.contains("PREVIEW_BOOTSTRAP_INVITE_TOKEN"));
        assertTrue(workflow.contains("PREVIEW_VERIFICATION_PASSWORD"));
        assertTrue(workflow.contains("The PR-built preview receives"));
        assertTrue(workflow.contains("intentionally reusable, low-sensitivity preview credentials"));
        assertTrue(workflow.contains("timeout-minutes: 45"));
        assertTrue(workflow.contains("format('calendar.social / event-calendar-pr-{0}', inputs.pull_request_number)"));
        assertTrue(!workflow.contains("pull_request_target"));

        List<String> githubApiCallLines = workflow.lines()
                .filter(line -> line.contains("gh api "))
                .toList();
        assertEquals(2, githubApiCallLines.size());
        assertTrue(githubApiCallLines.stream()
                .allMatch(line -> line.contains("timeout --signal=TERM")));
        assertEquals(
                1,
                workflow.lines()
                        .filter(line -> line.contains("--paginate --slurp"))
                        .count());
        assertTrue(workflow.contains("preview_resolution_deadline=$((SECONDS + 900))"));
        assertTrue(workflow.contains("while (( SECONDS < preview_resolution_deadline )); do"));
        assertTrue(workflow.contains(
                "request_timeout_seconds=$(( remaining_resolution_seconds < 120 ? remaining_resolution_seconds : 120 ))"));
        assertTrue(workflow.contains(
                "timeout --signal=TERM \"${request_timeout_seconds}s\" gh api"));
        assertTrue(workflow.contains(
                "sleep_seconds=$(( remaining_resolution_seconds < 15 ? remaining_resolution_seconds : 15 ))"));
        assertTrue(workflow.contains("sleep \"$sleep_seconds\""));
        assertTrue(!workflow.contains("seq 1 60"));

        int trustedCheckoutIndex = workflow.indexOf("Check out trusted verifier code");
        int firstRepositorySecretIndex = workflow.indexOf("${{ secrets.");
        assertTrue(trustedCheckoutIndex >= 0);
        assertTrue(
                firstRepositorySecretIndex > trustedCheckoutIndex,
                "The trusted default-branch verifier must be checked out before preview credentials are used.");
    }

    @Test
    void codeQlUsesOneMatrixEntryForEachRepositoryLanguage() throws Exception {
        String workflow = Files.readString(CODEQL_WORKFLOW);

        assertEquals(1, countTrimmedLine(workflow, "- language: java-kotlin"));
        assertEquals(1, countTrimmedLine(workflow, "- language: javascript-typescript"));
        assertEquals(1, countTrimmedLine(workflow, "- language: actions"));
        assertEquals(1, countTrimmedLine(workflow, "build-mode: manual"));
        assertEquals(2, countTrimmedLine(workflow, "build-mode: none"));
        assertTrue(workflow.contains("languages: ${{ matrix.language }}"));
        assertTrue(workflow.contains("build-mode: ${{ matrix.build-mode }}"));
        assertTrue(workflow.contains("category: /language:${{ matrix.language }}"));
    }

    @Test
    void exactTestSelectionsFailWhenTheyMatchNoTests() throws Exception {
        Document mavenConfiguration = readMavenConfiguration();
        XPath xpath = XPathFactory.newInstance().newXPath();

        assertEquals(
                "true",
                xpath.evaluate(
                        "/project/build/plugins/plugin[artifactId='maven-surefire-plugin']"
                                + "/configuration/failIfNoTests",
                        mavenConfiguration));
        for (String profileName : List.of(
                "end-to-end",
                "bootstrap-concurrency-end-to-end",
                "preview-deployment-end-to-end")) {
            assertEquals(
                    "true",
                    xpath.evaluate(
                            "/project/profiles/profile[id='" + profileName + "']"
                                    + "/build/plugins/plugin[artifactId='maven-failsafe-plugin']"
                                    + "/configuration/failIfNoTests",
                            mavenConfiguration),
                    () -> profileName + " must fail when its exact selection matches no tests.");
        }
    }

    @Test
    void flywayUsesPatchedJacksonDatabind() throws Exception {
        Document mavenConfiguration = readMavenConfiguration();
        XPath xpath = XPathFactory.newInstance().newXPath();

        assertEquals(
                "3.1.5",
                xpath.evaluate("/project/properties/jackson.databind.version", mavenConfiguration));
        assertEquals(
                "${jackson.databind.version}",
                xpath.evaluate(
                        "/project/dependencyManagement/dependencies/dependency"
                                + "[groupId='tools.jackson.core' and artifactId='jackson-databind']/version",
                        mavenConfiguration));
    }

    private static Document readMavenConfiguration() throws Exception {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
        documentBuilderFactory.setXIncludeAware(false);
        documentBuilderFactory.setExpandEntityReferences(false);
        return documentBuilderFactory.newDocumentBuilder().parse(MAVEN_CONFIGURATION.toFile());
    }

    private static long countTrimmedLine(String contents, String expectedLine) {
        return contents.lines()
                .map(String::trim)
                .filter(expectedLine::equals)
                .count();
    }

    private static void assertPipelineEnablesPipeFailure(
            List<String> workflowLines,
            String pipelineFragment) {
        int pipelineLineIndex = -1;
        for (int lineIndex = 0; lineIndex < workflowLines.size(); lineIndex++) {
            if (workflowLines.get(lineIndex).contains(pipelineFragment)) {
                pipelineLineIndex = lineIndex;
                break;
            }
        }
        assertTrue(
                pipelineLineIndex >= 0,
                "Required workflow pipeline was not found: " + pipelineFragment);

        int runBlockStartIndex = pipelineLineIndex;
        while (runBlockStartIndex >= 0
                && !workflowLines.get(runBlockStartIndex).trim().equals("run: |")) {
            runBlockStartIndex--;
        }
        assertTrue(
                runBlockStartIndex >= 0,
                () -> "Pipeline is not inside a multiline run block: " + pipelineFragment);

        boolean pipeFailureEnabled = workflowLines.subList(
                        runBlockStartIndex + 1,
                        pipelineLineIndex + 1)
                .stream()
                .map(String::trim)
                .anyMatch("set -euo pipefail"::equals);
        assertTrue(
                pipeFailureEnabled,
                () -> "Pipeline can mask an upstream failure: " + pipelineFragment);
    }
}
