import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const configurationPath = resolve("railway.json");
const configuration = JSON.parse(readFileSync(configurationPath, "utf8"));

requirePlainObject(configuration, "railway.json");
requirePlainObject(configuration.build, "build");
requirePlainObject(configuration.deploy, "deploy");

requireEqual(configuration.build.builder, "DOCKERFILE", "build.builder");
requireEqual(configuration.build.dockerfilePath, "Dockerfile", "build.dockerfilePath");
requireStringArrayEqual(
    configuration.build.watchPatterns,
    [
        ".mvn/**",
        ".dockerignore",
        "Dockerfile",
        "mvnw",
        "pom.xml",
        "railway.json",
        "src/main/**",
    ],
    "build.watchPatterns",
);

requireEqual(configuration.deploy.region, "europe-west4-drams3a", "deploy.region");
requireEqual(configuration.deploy.numReplicas, 1, "deploy.numReplicas");
requireEqual(configuration.deploy.healthcheckPath, "/health", "deploy.healthcheckPath");
requireBoundedInteger(configuration.deploy.healthcheckTimeout, 30, 300, "deploy.healthcheckTimeout");
requireEqual(configuration.deploy.restartPolicyType, "ON_FAILURE", "deploy.restartPolicyType");
requireBoundedInteger(configuration.deploy.restartPolicyMaxRetries, 1, 10, "deploy.restartPolicyMaxRetries");
requireEqual(configuration.deploy.overlapSeconds, 0, "deploy.overlapSeconds");
requireBoundedInteger(configuration.deploy.drainingSeconds, 10, 60, "deploy.drainingSeconds");

console.log("Railway configuration contract test passed.");

function requirePlainObject(value, fieldName) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
        throw new Error(`${fieldName} must be a JSON object.`);
    }
}

function requireEqual(actual, expected, fieldName) {
    if (actual !== expected) {
        throw new Error(`${fieldName} must be ${JSON.stringify(expected)}, but was ${JSON.stringify(actual)}.`);
    }
}

function requireBoundedInteger(actual, minimum, maximum, fieldName) {
    if (!Number.isInteger(actual) || actual < minimum || actual > maximum) {
        throw new Error(`${fieldName} must be an integer from ${minimum} through ${maximum}, but was ${actual}.`);
    }
}

function requireStringArrayEqual(actual, expected, fieldName) {
    if (!Array.isArray(actual) || actual.some((value) => typeof value !== "string")) {
        throw new Error(`${fieldName} must be an array of strings.`);
    }
    if (new Set(actual).size !== actual.length) {
        throw new Error(`${fieldName} must not contain duplicates.`);
    }
    if (actual.length !== expected.length || actual.some((value, index) => value !== expected[index])) {
        throw new Error(
            `${fieldName} must equal ${JSON.stringify(expected)}, but was ${JSON.stringify(actual)}.`,
        );
    }
}
