import { execFile } from "node:child_process";
import { resolve } from "node:path";
import process from "node:process";
import { promisify } from "node:util";
import { pathToFileURL } from "node:url";
import { requirePullRequestNumber, resolveRailwayPreviewUrl } from "./railway-preview-url.mjs";

const executeFile = promisify(execFile);

async function main() {
    try {
        if (process.argv.length !== 3) {
            throw new Error("Usage: node scripts/preview-login.mjs <pull-request-number>");
        }
        const pullRequestNumber = requirePullRequestNumber(process.argv[2]);

        const repository = process.env.GITHUB_REPOSITORY?.trim() || "Tenemo/event-calendar";
        if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository)) {
            throw new Error("GITHUB_REPOSITORY must contain one GitHub owner and repository name.");
        }
        const commentsResponse = await executeFile(
            "gh",
            [
                "api",
                `repos/${repository}/issues/${pullRequestNumber}/comments?per_page=100`,
                "--paginate",
                "--slurp",
            ], {
                encoding: "utf8",
                maxBuffer: 64 * 1024 * 1024,
                timeout: 60_000,
                windowsHide: true,
            },
        );

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
}

const launchedScriptUrl = process.argv[1] === undefined
    ? null
    : pathToFileURL(resolve(process.argv[1])).href;
if (launchedScriptUrl === import.meta.url) {
    await main();
}
