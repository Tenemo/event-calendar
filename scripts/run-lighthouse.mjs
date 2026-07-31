import {mkdir, writeFile} from "node:fs/promises";
import path from "node:path";

import {launch} from "chrome-launcher";
import lighthouse from "lighthouse";

const applicationBaseUrl = process.env.APP_BASE_URL ?? "http://localhost:9080";
const auditedPages = [
  {name: "Landing page", url: new URL("/", applicationBaseUrl), fileName: "landing"},
  {name: "Sign-in page", url: new URL("/sign-in", applicationBaseUrl), fileName: "sign-in"},
];

const outputDirectory = path.resolve(".build", "lighthouse");
const browserProfileDirectory = path.resolve(".build", "lighthouse-browser-profile");
const runCount = 3;
const budgets = [
  {name: "Performance score", minimum: 0.9, value: result => result.categories.performance.score},
  {name: "First contentful paint", audit: "first-contentful-paint", maximum: 2_000, unit: "ms"},
  {name: "Largest contentful paint", audit: "largest-contentful-paint", maximum: 2_500, unit: "ms"},
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

await Promise.all([
  mkdir(outputDirectory, {recursive: true}),
  mkdir(browserProfileDirectory, {recursive: true}),
]);
const chrome = await launch({
  chromeFlags: ["--headless=new", "--no-sandbox", "--ignore-certificate-errors"],
  userDataDir: browserProfileDirectory,
});

const pageResults = [];
try {
  for (const page of auditedPages) {
    const results = [];
    for (let runNumber = 1; runNumber <= runCount; runNumber++) {
      const lighthouseRun = await lighthouse(page.url.href, {
        port: chrome.port,
        output: "html",
        logLevel: "error",
        onlyCategories: ["performance"],
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
} finally {
  await chrome.kill();
}

const summary = pageResults.flatMap(page => budgets.map(budget => {
  const measured = median(page.results.map(result => budgetValue(budget, result)));
  const usesMaximum = budget.minimum === undefined;
  const passed = usesMaximum ? measured <= budget.maximum : measured >= budget.minimum;
  const threshold = usesMaximum ? budget.maximum : budget.minimum;
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
