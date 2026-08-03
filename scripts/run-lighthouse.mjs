import {mkdir, mkdtemp, rm, writeFile} from "node:fs/promises";
import path from "node:path";

import {launch} from "chrome-launcher";
import lighthouse from "lighthouse";
import {connect} from "puppeteer-core";

const applicationBaseUrl = process.env.APP_BASE_URL ?? "http://localhost:9080";
const authenticationUsername = process.env.REMOTE_VERIFICATION_USERNAME;
const authenticationPassword = process.env.REMOTE_VERIFICATION_PASSWORD;
if (Boolean(authenticationUsername) !== Boolean(authenticationPassword)) {
  throw new Error(
      "REMOTE_VERIFICATION_USERNAME and REMOTE_VERIFICATION_PASSWORD must be configured together.");
}

const publicPages = [
  {name: "Landing page", url: new URL("/", applicationBaseUrl), fileName: "landing"},
  {name: "Sign-in page", url: new URL("/sign-in", applicationBaseUrl), fileName: "sign-in"},
];
const authenticatedPages = authenticationUsername ? [
  {
    name: "My calendars",
    url: new URL("/app/calendars", applicationBaseUrl),
    fileName: "calendars",
    authenticated: true,
  },
  {
    name: "Invitations",
    url: new URL("/app/invitations", applicationBaseUrl),
    fileName: "invitations",
    authenticated: true,
  },
  {
    name: "Account settings",
    url: new URL("/app/account-settings", applicationBaseUrl),
    fileName: "account-settings",
    authenticated: true,
  },
] : [];

const outputDirectory = path.resolve(".build", "lighthouse");
const runCount = 3;
const budgets = [
  {name: "Performance score", minimum: 0.9, value: result => result.categories.performance.score},
  {
    name: "First contentful paint",
    audit: "first-contentful-paint",
    maximum: 2_000,
    authenticatedMaximum: 3_000,
    unit: "ms",
  },
  {
    name: "Largest contentful paint",
    audit: "largest-contentful-paint",
    maximum: 2_500,
    authenticatedMaximum: 3_000,
    unit: "ms",
  },
  {name: "Total blocking time", audit: "total-blocking-time", maximum: 300, unit: "ms"},
  {name: "Cumulative layout shift", audit: "cumulative-layout-shift", maximum: 0.1},
];

function median(values) {
  return [...values].sort((left, right) => left - right)[Math.floor(values.length / 2)];
}

function budgetValue(budget, result) {
  const value = budget.value?.(result) ?? result.audits[budget.audit]?.numericValue;
  if (!Number.isFinite(value)) {
    throw new Error(`Lighthouse did not produce ${budget.name}.`);
  }
  return value;
}

function formatValue(value, budget) {
  if (budget.name === "Performance score") {
    return `${Math.round(value * 100)}/100`;
  }
  return `${Math.round(value * 100) / 100}${budget.unit ?? ""}`;
}

await rm(outputDirectory, {recursive: true, force: true});
await mkdir(outputDirectory, {recursive: true});
const browserProfileDirectory = await mkdtemp(
    path.resolve(".build", "lighthouse-browser-profile-"));
let chrome;
const pageResults = [];

async function auditPages(pages, disableStorageReset) {
  for (const page of pages) {
    const results = [];
    for (let runNumber = 1; runNumber <= runCount; runNumber++) {
      const lighthouseRun = await lighthouse(page.url.href, {
        port: chrome.port,
        output: "html",
        logLevel: "error",
        onlyCategories: ["performance"],
        disableStorageReset,
      });
      if (!lighthouseRun || lighthouseRun.lhr.runtimeError) {
        const runtimeMessage = lighthouseRun?.lhr.runtimeError?.message ?? "Lighthouse returned no result.";
        throw new Error(runtimeMessage);
      }

      results.push(lighthouseRun.lhr);
      const reportName = `${page.fileName}-run-${runNumber}.report`;
      await Promise.all([
        writeFile(path.join(outputDirectory, `${reportName}.html`), lighthouseRun.report, "utf8"),
        writeFile(
            path.join(outputDirectory, `${reportName}.json`),
            JSON.stringify(lighthouseRun.lhr, null, 2),
            "utf8"),
      ]);
    }
    pageResults.push({...page, results});
  }
}

async function authenticateChromeProfile() {
  const browser = await connect({browserURL: `http://127.0.0.1:${chrome.port}`});
  const page = await browser.newPage();
  try {
    await page.goto(new URL("/sign-in", applicationBaseUrl).href, {waitUntil: "networkidle2"});
    await page.locator("input[id$='username']").fill(authenticationUsername);
    await page.locator("input[id$='password']").fill(authenticationPassword);
    await Promise.all([
      page.waitForNavigation({waitUntil: "networkidle2"}),
      page.locator("input[type='submit'][value='Sign in']").click(),
    ]);
    if (new URL(page.url()).pathname !== "/app/calendars") {
      throw new Error("Remote verification sign-in did not reach the calendars page.");
    }
  } finally {
    await page.close();
    browser.disconnect();
  }
}

try {
  chrome = await launch({
    chromeFlags: ["--headless=new", "--no-sandbox", "--ignore-certificate-errors"],
    userDataDir: browserProfileDirectory,
  });

  await auditPages(publicPages, false);
  if (authenticatedPages.length > 0) {
    await authenticateChromeProfile();
    await auditPages(authenticatedPages, true);
  }
} finally {
  if (chrome) {
    const browserClosed = chrome.process.exitCode === null
      ? new Promise(resolve => chrome.process.once("close", resolve))
      : Promise.resolve();
    chrome.kill();
    await browserClosed;
  }
  await rm(browserProfileDirectory, {recursive: true, force: true});
}

const summary = pageResults.flatMap(page => budgets.map(budget => {
  const measured = median(page.results.map(result => budgetValue(budget, result)));
  const usesMaximum = budget.minimum === undefined;
  const maximum = page.authenticated
    ? (budget.authenticatedMaximum ?? budget.maximum)
    : budget.maximum;
  const passed = usesMaximum ? measured <= maximum : measured >= budget.minimum;
  const threshold = usesMaximum ? maximum : budget.minimum;
  const limit = `${usesMaximum ? "at most" : "at least"} ${formatValue(threshold, budget)}`;
  return {page: page.name, url: page.url.href, ...budget, measured, passed, limit};
}));

await writeFile(
    path.join(outputDirectory, "summary.json"),
    JSON.stringify({runCount, metrics: summary.map(
        ({page, url, name, measured, passed, limit}) => (
          {page, url, name, measured, passed, limit}))}, null, 2),
    "utf8");

for (const metric of summary) {
  console.log(`${metric.page}: ${metric.name}: ${formatValue(metric.measured, metric)} (${metric.limit})`);
}

const failures = summary.filter(metric => !metric.passed);
if (failures.length > 0) {
  console.error(`Lighthouse failed ${failures.length} performance budget${failures.length === 1 ? "" : "s"}.`);
  process.exitCode = 1;
}
