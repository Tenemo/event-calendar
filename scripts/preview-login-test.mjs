import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import process from "node:process";

const previewLoginScriptPath = fileURLToPath(new URL("./preview-login.mjs", import.meta.url));
const expectedUsage = "Usage: node scripts/preview-login.mjs <pull-request-number>";

function runPreviewLogin(argumentsToPass) {
    return spawnSync(process.execPath, [previewLoginScriptPath, ...argumentsToPass], {
        encoding: "utf8",
        windowsHide: true,
    });
}

for (const malformedArgumentList of [[], ["19", "unexpected"]]) {
    const result = runPreviewLogin(malformedArgumentList);
    assert.equal(result.status, 1);
    assert.equal(result.stdout, "");
    assert.equal(result.stderr.trim(), expectedUsage);
}

const invalidPullRequestNumberResult = runPreviewLogin(["not-a-number"]);
assert.equal(invalidPullRequestNumberResult.status, 1);
assert.equal(invalidPullRequestNumberResult.stdout, "");
assert.match(invalidPullRequestNumberResult.stderr, /positive integer/);
assert.doesNotMatch(invalidPullRequestNumberResult.stderr, /Usage:/);

process.stdout.write("Preview login tests passed.\n");
