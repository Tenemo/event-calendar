import chromeLauncher from "chrome-launcher";
import fs from "node:fs";
import path from "node:path";

export function resolveChromePath(environment = process.env) {
    const configuredChromePath = environment.CHROME_PATH?.trim();
    if (configuredChromePath) {
        if (!fs.existsSync(configuredChromePath)) {
            throw new Error("CHROME_PATH does not identify an installed browser.");
        }
        return configuredChromePath;
    }
    const detectedChromePath = chromeLauncher.Launcher.getFirstInstallation();
    if (!detectedChromePath) {
        throw new Error("Chrome or Chromium is required for Lighthouse measurement.");
    }
    return detectedChromePath;
}

export function browserArguments(userDataDirectory, platform = process.platform) {
    const normalizedUserDataDirectory = path.resolve(userDataDirectory);
    const argumentsForBrowser = [
        ...chromeLauncher.Launcher.defaultFlags(),
        "--headless=new",
        "--remote-debugging-address=127.0.0.1",
        "--remote-debugging-port=0",
        `--user-data-dir=${normalizedUserDataDirectory}`,
    ];
    if (platform === "linux") {
        argumentsForBrowser.push("--disable-dev-shm-usage", "--no-sandbox");
    }
    argumentsForBrowser.push("about:blank");
    return argumentsForBrowser;
}

export function parseDevToolsPort(devToolsActivePortContent) {
    const portLine = String(devToolsActivePortContent).split(/\r?\n/, 1)[0].trim();
    if (!/^[1-9][0-9]*$/.test(portLine)) {
        throw new Error("Chrome produced an invalid DevTools port.");
    }
    const port = Number.parseInt(portLine, 10);
    if (!Number.isSafeInteger(port) || port > 65535) {
        throw new Error("Chrome produced an invalid DevTools port.");
    }
    return port;
}
