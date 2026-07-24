package app.startup;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorkflowPipelineSafetyTest {
    private static final Path PULL_REQUEST_WORKFLOW =
            Path.of(".github", "workflows", "pr-checks.yml");

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
