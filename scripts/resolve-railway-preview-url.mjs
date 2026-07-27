import process from "node:process";
import { readBoundedUtf8Input } from "./bounded-standard-input.mjs";
import { resolveRailwayPreviewUrl } from "./railway-preview-url.mjs";

const pullRequestNumber = process.argv[2];

try {
    const standardInput = await readBoundedUtf8Input(process.stdin);
    const comments = JSON.parse(standardInput);
    process.stdout.write(`${resolveRailwayPreviewUrl(comments, pullRequestNumber)}\n`);
} catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : "Could not resolve the Railway preview URL."}\n`);
    process.exitCode = 1;
}
