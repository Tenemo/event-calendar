import assert from "node:assert/strict";
import { Readable } from "node:stream";
import {
    DEFAULT_MAXIMUM_STANDARD_INPUT_BYTES,
    readBoundedUtf8Input,
} from "./bounded-standard-input.mjs";
import { resolveRailwayPreviewUrl } from "./railway-preview-url.mjs";

const validBody = `<!-- railway-bot-comment-version=2 -->
Deployed to the event-calendar-pr-19 environment.
| Service | Status | Web | Updated |
| :--- | :--- | :--- | :--- |
| shared-calendar-web | Success | [Web](https://shared-calendar-web-event-calendar-pr-19.up.railway.app) | now |`;

function trustedComment(overrides = {}) {
    return {
        id: 100,
        updated_at: "2026-07-24T10:00:00Z",
        user: { login: "railway-app[bot]", id: 68434857 },
        performed_via_github_app: { slug: "railway-app" },
        body: validBody,
        ...overrides,
    };
}

assert.equal(
    resolveRailwayPreviewUrl([trustedComment()], "19"),
    "https://shared-calendar-web-event-calendar-pr-19.up.railway.app",
);
assert.equal(
    resolveRailwayPreviewUrl([[trustedComment()]], 19),
    "https://shared-calendar-web-event-calendar-pr-19.up.railway.app",
);
assert.equal(
    resolveRailwayPreviewUrl([
        trustedComment({
            id: 101,
            updated_at: "2026-07-24T11:00:00Z",
            body: validBody.replace("event-calendar-pr-19", "event-calendar-pr-18"),
        }),
        trustedComment(),
    ], 19),
    "https://shared-calendar-web-event-calendar-pr-19.up.railway.app",
);

for (const untrustedComment of [
    trustedComment({ user: { login: "attacker", id: 68434857 } }),
    trustedComment({ user: { login: "railway-app[bot]", id: 1 } }),
    trustedComment({ performed_via_github_app: null }),
    trustedComment({ body: validBody.replace("<!-- railway-bot-comment-version=2 -->", "") }),
]) {
    assert.throws(() => resolveRailwayPreviewUrl([untrustedComment], 19), /No trusted Railway preview URL/);
}

for (const invalidUrl of [
    "http://shared-calendar-web-event-calendar-pr-19.up.railway.app",
    "https://user:password@shared-calendar-web-event-calendar-pr-19.up.railway.app",
    "https://shared-calendar-web-event-calendar-pr-19.up.railway.app:9443",
    "https://shared-calendar-web-event-calendar-pr-19.up.railway.app/login",
    "https://shared-calendar-web-event-calendar-pr-19.up.railway.app?value=1",
    "https://event-calendar-pr-19.example.com",
    "https://shared-calendar-web-event-calendar-pr-20.up.railway.app",
]) {
    const invalidComment = trustedComment({
        body: validBody.replace(
            "https://shared-calendar-web-event-calendar-pr-19.up.railway.app",
            invalidUrl,
        ),
    });
    assert.throws(
        () => resolveRailwayPreviewUrl([invalidComment], 19),
        /invalid preview service URL|No trusted Railway preview URL/,
    );
}

for (const invalidPullRequestNumber of [undefined, "", "0", "-1", "19x", "1.5"]) {
    assert.throws(
        () => resolveRailwayPreviewUrl([trustedComment()], invalidPullRequestNumber),
        /positive integer/,
    );
}

assert.equal(DEFAULT_MAXIMUM_STANDARD_INPUT_BYTES, 64 * 1024 * 1024);
const inputLargerThanOneMegabyte = "x".repeat(1_250_000);
assert.equal(
    await readBoundedUtf8Input(Readable.from([inputLargerThanOneMegabyte])),
    inputLargerThanOneMegabyte,
);
await assert.rejects(
    readBoundedUtf8Input(Readable.from(["123", "\u20ac"]), 5),
    /exceeded the 5-byte safety limit/,
);

process.stdout.write("Railway preview URL tests passed.\n");
