import process from "node:process";
import { resolveRailwayPreviewUrl } from "./railway-preview-url.mjs";

const pullRequestNumber = process.argv[2];
let standardInput = "";
process.stdin.setEncoding("utf8");
for await (const inputChunk of process.stdin) {
    standardInput += inputChunk;
}

try {
    const comments = JSON.parse(standardInput);
    process.stdout.write(`${resolveRailwayPreviewUrl(comments, pullRequestNumber)}\n`);
} catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : "Could not resolve the Railway preview URL."}\n`);
    process.exitCode = 1;
}
