package app.startup;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorkflowPipelineSafetyTest {
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
        assertPipelineEnablesPipeFailure(
                workflowLines,
                "mise run verify-backup-restore 2>&1 | tee");
    }

    @Test
    void previewSecretsAreUsedOnlyByTrustedDefaultBranchVerifierCode() throws Exception {
        String workflow = Files.readString(PREVIEW_VERIFICATION_WORKFLOW);

        assertTrue(workflow.contains("ref: ${{ github.event.repository.default_branch }}"));
        assertTrue(workflow.contains("github.event.deployment.creator.login == 'railway-app[bot]'"));
        assertTrue(workflow.contains("github.event.deployment.creator.id == 68434857"));
        assertTrue(workflow.contains("scripts/resolve-railway-preview-url.mjs"));
        assertTrue(workflow.contains("scripts/verify-production-deployment.java"));
        assertTrue(workflow.contains("verify-preview-deployment"));
        assertTrue(workflow.contains("PREVIEW_BOOTSTRAP_INVITE_TOKEN"));
        assertTrue(workflow.contains("PREVIEW_VERIFICATION_PASSWORD"));
        assertTrue(!workflow.contains("pull_request_target"));

        int trustedCheckoutIndex = workflow.indexOf("Check out trusted verifier code");
        int firstRepositorySecretIndex = workflow.indexOf("${{ secrets.");
        assertTrue(trustedCheckoutIndex >= 0);
        assertTrue(
                firstRepositorySecretIndex > trustedCheckoutIndex,
                "Repository secrets must not be exposed before trusted default-branch code is checked out.");
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
