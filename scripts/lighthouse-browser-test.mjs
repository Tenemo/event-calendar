import assert from "node:assert/strict";
import { createRequire } from "node:module";
import path from "node:path";
import { browserArguments, parseDevToolsPort } from "./lighthouse-browser.mjs";

const require = createRequire(import.meta.url);

assert.equal(parseDevToolsPort("9222\n/devtools/browser/example\n"), 9222);
for (const invalidPort of ["", "0", "-1", "1.5", "65536", "text"]) {
    assert.throws(() => parseDevToolsPort(invalidPort), /invalid DevTools port/);
}

const windowsArguments = browserArguments(".build/example profile", "win32");
assert(windowsArguments.includes("--headless=new"));
assert(windowsArguments.includes("--remote-debugging-address=127.0.0.1"));
assert(windowsArguments.includes("--remote-debugging-port=0"));
assert(windowsArguments.includes(`--user-data-dir=${path.resolve(".build/example profile")}`));
assert(!windowsArguments.includes("--no-sandbox"));
assert.equal(windowsArguments.at(-1), "about:blank");

const linuxArguments = browserArguments(".build/example-profile", "linux");
assert(linuxArguments.includes("--disable-dev-shm-usage"));
assert(linuxArguments.includes("--no-sandbox"));

process.env.LIGHTHOUSE_BASE_URL = "http://localhost:9082";
process.env.LIGHTHOUSE_CHROME_PORT = "9222";
const lighthouseConfiguration = require("../lighthouserc.cjs");
const assertions = lighthouseConfiguration.ci.assert.assertions;
const expectedAssertionNames = [
    "categories:accessibility",
    "categories:best-practices",
    "categories:performance",
    "categories:seo",
    "cumulative-layout-shift",
    "first-contentful-paint",
    "largest-contentful-paint",
    "speed-index",
    "total-blocking-time",
];

assert.deepEqual(
    lighthouseConfiguration.ci.collect.url,
    ["http://localhost:9082/", "http://localhost:9082/login"],
);
assert.equal(lighthouseConfiguration.ci.collect.numberOfRuns, 3);
assert.equal(lighthouseConfiguration.ci.collect.settings.port, 9222);
assert.deepEqual(Object.keys(assertions).sort(), expectedAssertionNames);
for (const assertionName of expectedAssertionNames) {
    assert.deepEqual(assertions[assertionName], [
        "error",
        { minScore: 0.95, aggregationMethod: "pessimistic" },
    ]);
}

process.stdout.write("Lighthouse browser and configuration tests passed.\n");
