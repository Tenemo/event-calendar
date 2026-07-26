const configuredBaseUrl = process.env.LIGHTHOUSE_BASE_URL || "http://localhost:9082";
const baseUrl = validatedLoopbackBaseUrl(configuredBaseUrl);
const chromePort = validatedChromePort(process.env.LIGHTHOUSE_CHROME_PORT);
const everyRunScoresAtLeastNinetyFive = () => [
    "error",
    {
        minScore: 0.95,
        aggregationMethod: "pessimistic",
    },
];

module.exports = {
    ci: {
        collect: {
            url: [new URL("/", baseUrl).toString(), new URL("/login", baseUrl).toString()],
            numberOfRuns: 3,
            settings: {
                onlyCategories: ["performance", "accessibility", "best-practices", "seo"],
                port: chromePort,
            },
        },
        assert: {
            assertions: {
                "categories:performance": everyRunScoresAtLeastNinetyFive(),
                "categories:accessibility": everyRunScoresAtLeastNinetyFive(),
                "categories:best-practices": everyRunScoresAtLeastNinetyFive(),
                "categories:seo": everyRunScoresAtLeastNinetyFive(),
                "first-contentful-paint": everyRunScoresAtLeastNinetyFive(),
                "largest-contentful-paint": everyRunScoresAtLeastNinetyFive(),
                "speed-index": everyRunScoresAtLeastNinetyFive(),
                "total-blocking-time": everyRunScoresAtLeastNinetyFive(),
                "cumulative-layout-shift": everyRunScoresAtLeastNinetyFive(),
            },
        },
        upload: {
            target: "filesystem",
            outputDir: ".build/lighthouse",
        },
    },
};

function validatedLoopbackBaseUrl(value) {
    const baseUrl = new URL(value);
    const loopbackHosts = new Set(["localhost", "127.0.0.1", "[::1]"]);
    if (!loopbackHosts.has(baseUrl.hostname)
        || !["http:", "https:"].includes(baseUrl.protocol)
        || baseUrl.username
        || baseUrl.password
        || (baseUrl.pathname !== "/" && baseUrl.pathname !== "")
        || baseUrl.search
        || baseUrl.hash) {
        throw new Error("LIGHTHOUSE_BASE_URL must be an HTTP(S) loopback origin without credentials or a path.");
    }
    return baseUrl;
}

function validatedChromePort(value) {
    if (!/^[1-9][0-9]*$/.test(value ?? "")) {
        throw new Error("LIGHTHOUSE_CHROME_PORT must be a port from 1 through 65535.");
    }
    const port = Number.parseInt(value, 10);
    if (!Number.isSafeInteger(port) || port > 65535) {
        throw new Error("LIGHTHOUSE_CHROME_PORT must be a port from 1 through 65535.");
    }
    return port;
}
