import { spawnSync } from "node:child_process";
import process from "node:process";
import { requirePullRequestNumber, resolveRailwayPreviewUrl } from "./railway-preview-url.mjs";

try {
    if (process.argv.length !== 3) {
        throw new Error("Usage: node scripts/preview-login.mjs <pull-request-number>");
    }
    const pullRequestNumber = requirePullRequestNumber(process.argv[2]);

    const repository = process.env.GITHUB_REPOSITORY?.trim() || "Tenemo/event-calendar";
    if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository)) {
        throw new Error("GITHUB_REPOSITORY must contain one GitHub owner and repository name.");
    }
    const commentsResponse = spawnSync(
        "gh",
        ["api", `repos/${repository}/issues/${pullRequestNumber}/comments?per_page=100`],
        { encoding: "utf8", windowsHide: true },
    );
    if (commentsResponse.error || commentsResponse.status !== 0) {
        throw new Error("Could not read the pull request comments with the authenticated GitHub CLI.");
    }

    const previewUrl = resolveRailwayPreviewUrl(
        JSON.parse(commentsResponse.stdout),
        pullRequestNumber,
    );
    process.stdout.write(`Preview sign-in: ${previewUrl}/login\n`);
    process.stdout.write(`Username: preview-pr-${pullRequestNumber}\n`);
    process.stdout.write("Password: the saved PREVIEW_VERIFICATION_PASSWORD value\n");
} catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : "Could not prepare preview sign-in."}\n`);
    process.exitCode = 1;
}
