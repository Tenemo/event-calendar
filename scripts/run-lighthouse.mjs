import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { browserArguments, parseDevToolsPort, resolveChromePath } from "./lighthouse-browser.mjs";

const PROJECT_DIRECTORY = path.resolve(import.meta.dirname, "..");
const LIGHTHOUSE_CLI_PATH = path.join(
    PROJECT_DIRECTORY,
    "node_modules",
    "@lhci",
    "cli",
    "src",
    "cli.js",
);
const BROWSER_PROFILE_PARENT_DIRECTORY = path.join(PROJECT_DIRECTORY, ".build");
const LIGHTHOUSE_OUTPUT_DIRECTORY = path.join(
    BROWSER_PROFILE_PARENT_DIRECTORY,
    "lighthouse",
);
const BROWSER_READY_TIMEOUT_MILLISECONDS = 30_000;
const BROWSER_READY_POLL_INTERVAL_MILLISECONDS = 100;
const BROWSER_EXIT_TIMEOUT_MILLISECONDS = 10_000;

await runLighthouse();

async function runLighthouse() {
    fs.mkdirSync(BROWSER_PROFILE_PARENT_DIRECTORY, { recursive: true });
    fs.rmSync(LIGHTHOUSE_OUTPUT_DIRECTORY, { recursive: true, force: true });
    const browserProfileDirectory = fs.mkdtempSync(
        path.join(BROWSER_PROFILE_PARENT_DIRECTORY, "lighthouse-browser-"),
    );
    const browserProcess = spawn(
        resolveChromePath(),
        browserArguments(browserProfileDirectory),
        {
            cwd: PROJECT_DIRECTORY,
            detached: process.platform !== "win32",
            stdio: "ignore",
            windowsHide: true,
        },
    );
    let browserExited = false;
    const browserExitPromise = new Promise((resolve) => {
        browserProcess.once("exit", (exitCode, signal) => {
            browserExited = true;
            resolve({ exitCode, signal });
        });
    });

    let lighthouseExitCode = 1;
    let primaryFailure;
    try {
        const devToolsPort = await waitForDevToolsPort(
            browserProfileDirectory,
            browserExitPromise,
        );
        lighthouseExitCode = await runLighthouseCi(devToolsPort);
    } catch (error) {
        primaryFailure = error;
    }

    try {
        await stopBrowser(browserProcess, browserExitPromise, () => browserExited);
        fs.rmSync(browserProfileDirectory, {
            recursive: true,
            force: true,
            maxRetries: 20,
            retryDelay: 100,
        });
    } catch (cleanupFailure) {
        if (primaryFailure === undefined) {
            primaryFailure = cleanupFailure;
        } else if (primaryFailure instanceof Error) {
            primaryFailure.cause = cleanupFailure;
        }
    }

    if (primaryFailure !== undefined) {
        throw primaryFailure;
    }
    if (lighthouseExitCode !== 0) {
        process.exitCode = lighthouseExitCode;
    }
}

async function waitForDevToolsPort(browserProfileDirectory, browserExitPromise) {
    const devToolsActivePortPath = path.join(browserProfileDirectory, "DevToolsActivePort");
    const deadline = Date.now() + BROWSER_READY_TIMEOUT_MILLISECONDS;
    while (Date.now() < deadline) {
        if (fs.existsSync(devToolsActivePortPath)) {
            return parseDevToolsPort(fs.readFileSync(devToolsActivePortPath, "utf8"));
        }
        const result = await Promise.race([
            browserExitPromise.then((browserExit) => ({ browserExit })),
            delay(BROWSER_READY_POLL_INTERVAL_MILLISECONDS).then(() => ({ browserExit: null })),
        ]);
        if (result.browserExit !== null) {
            throw new Error("Chrome exited before its DevTools endpoint became ready.");
        }
    }
    throw new Error("Chrome did not expose its DevTools endpoint within 30 seconds.");
}

function runLighthouseCi(devToolsPort) {
    return new Promise((resolve, reject) => {
        const lighthouseProcess = spawn(
            process.execPath,
            [LIGHTHOUSE_CLI_PATH, "autorun", "--config=lighthouserc.cjs"],
            {
                cwd: PROJECT_DIRECTORY,
                env: {
                    ...process.env,
                    LIGHTHOUSE_CHROME_PORT: String(devToolsPort),
                },
                stdio: "inherit",
                windowsHide: true,
            },
        );
        lighthouseProcess.once("error", reject);
        lighthouseProcess.once("exit", (exitCode, signal) => {
            if (signal !== null) {
                reject(new Error("Lighthouse CI was terminated before completing."));
                return;
            }
            resolve(exitCode ?? 1);
        });
    });
}

async function stopBrowser(browserProcess, browserExitPromise, hasBrowserExited) {
    if (!hasBrowserExited()) {
        if (process.platform === "win32") {
            const taskkillResult = spawnSync(
                "taskkill.exe",
                ["/PID", String(browserProcess.pid), "/T", "/F"],
                { encoding: "utf8", windowsHide: true },
            );
            if (taskkillResult.error || taskkillResult.status !== 0) {
                throw new Error("Could not stop the Lighthouse Chrome process tree.");
            }
        } else {
            process.kill(-browserProcess.pid, "SIGKILL");
        }
    }
    const browserExitResult = await Promise.race([
        browserExitPromise.then(() => "exited"),
        delay(BROWSER_EXIT_TIMEOUT_MILLISECONDS).then(() => "timeout"),
    ]);
    if (browserExitResult !== "exited") {
        throw new Error("The Lighthouse Chrome process did not exit within 10 seconds.");
    }
}

function delay(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
