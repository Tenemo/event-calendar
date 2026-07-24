package app.startup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class BackupRestoreWorkflowContractTest {
    private static final Path PULL_REQUEST_WORKFLOW =
            Path.of(".github", "workflows", "pr-checks.yml");

    @Test
    void backupRestoreUsesTheExactProductionImageBeforeInitializingTheSourceDatabase()
            throws Exception {
        String workflow = Files.readString(PULL_REQUEST_WORKFLOW);
        String backupRestoreJob = workflowJob(workflow, "backup-restore", "required-pr-checks");

        assertFalse(
                backupRestoreJob.contains("needs: build"),
                "Backup verification must not run from only the Maven build artifact.");
        assertFragmentsInOrder(
                backupRestoreJob,
                "needs: production-image",
                "- name: Download exact production image",
                "name: production-image-${{ github.sha }}",
                "- name: Load and verify exact production image",
                "docker image load --input .build/production-image/shared-calendar-image.tar.gz",
                "test \"$actual_image_id\" = \"$expected_image_id\"",
                "- name: Initialize clean source database and verify backup and restore",
                "mise run verify-backup-restore 2>&1 | tee");
    }

    private static String workflowJob(
            String workflow,
            String jobName,
            String followingJobName) {
        int jobStart = workflow.indexOf("  " + jobName + ":");
        int jobEnd = workflow.indexOf("  " + followingJobName + ":", jobStart + 1);
        assertTrue(jobStart >= 0, () -> "Workflow job was not found: " + jobName);
        assertTrue(jobEnd > jobStart, () -> "Following workflow job was not found: " + followingJobName);
        return workflow.substring(jobStart, jobEnd);
    }

    private static void assertFragmentsInOrder(String text, String... fragments) {
        int previousFragmentIndex = -1;
        for (String fragment : fragments) {
            int fragmentIndex = text.indexOf(fragment, previousFragmentIndex + 1);
            assertTrue(
                    fragmentIndex > previousFragmentIndex,
                    () -> "Expected workflow fragment after the previous contract element: " + fragment);
            previousFragmentIndex = fragmentIndex;
        }
    }
}
