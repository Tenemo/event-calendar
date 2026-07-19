import { readFileSync } from "node:fs";

const wrapperProperties = readFileSync(".mvn/wrapper/maven-wrapper.properties", "utf8");
const dockerfile = readFileSync("Dockerfile", "utf8");
const pom = readFileSync("pom.xml", "utf8");

const distributionUrl = requiredProperty(wrapperProperties, "distributionUrl");
const wrapperVersion = requiredMatch(
    distributionUrl,
    /\/apache-maven\/(\d+\.\d+\.\d+)\/apache-maven-\1-bin\.zip$/,
    "Maven Wrapper distribution URL",
)[1];

const mavenImageMatch = requiredMatch(
    dockerfile,
    /^FROM maven:(\d+\.\d+\.\d+)-eclipse-temurin-25@sha256:[0-9a-f]{64} AS maven-toolchain$/m,
    "Pinned Maven Docker stage",
);
requireEqual(mavenImageMatch[1], wrapperVersion, "Maven Docker image version");

const seededDistributionMatch = requiredMatch(
    dockerfile.replaceAll("\\\r\n", " ").replaceAll("\\\n", " "),
    /\/root\/\.m2\/wrapper\/dists\/apache-maven-(\d+\.\d+\.\d+)\/apache-maven-(\d+\.\d+\.\d+)/,
    "Seeded Maven Wrapper destination",
);
requireEqual(seededDistributionMatch[1], wrapperVersion, "Seeded Maven distribution directory");
requireEqual(seededDistributionMatch[2], wrapperVersion, "Seeded Maven installation directory");

const enforcerRangeMatch = requiredMatch(
    pom,
    /<requireMavenVersion>\s*<version>\[(\d+\.\d+\.\d+),(\d+\.\d+\.\d+)\)<\/version>\s*<\/requireMavenVersion>/,
    "Maven Enforcer version range",
);
requireEqual(enforcerRangeMatch[1], wrapperVersion, "Maven Enforcer minimum version");
requireEqual(enforcerRangeMatch[2], nextPatchVersion(wrapperVersion), "Maven Enforcer exclusive maximum version");

const libertyDevProfileStart = pom.indexOf("<id>liberty-dev</id>");
const libertyDevProfileEnd = pom.indexOf("</profile>", libertyDevProfileStart);
if (libertyDevProfileStart < 0 || libertyDevProfileEnd < 0) {
    throw new Error("The Liberty development Maven profile is missing or malformed.");
}
const libertyDevProfile = pom.slice(libertyDevProfileStart, libertyDevProfileEnd);
requireSingleTrueSetting(
    libertyDevProfile,
    "changeOnDemandTestsAction",
    "Liberty development on-demand tests must require an explicit command",
);
requireSingleTrueSetting(
    libertyDevProfile,
    "skipITs",
    "Liberty development mode must not run integration or browser tests",
);

const finalDockerStage = dockerfile.slice(dockerfile.lastIndexOf("\nFROM ") + 1);
const finalStageUserDirectives = [...finalDockerStage.matchAll(/^USER\s+(\S+)\s*$/gmu)];
if (finalStageUserDirectives.length !== 1 || finalStageUserDirectives[0][1] !== "1001") {
    throw new Error("The final Docker stage must contain exactly one USER directive and it must be USER 1001.");
}

const generatedLtpaPasswordSetting = "ENV GENERATE_LTPA_KEYS_PASSWORD=false";
const generatedLtpaPasswordSettingIndex = finalDockerStage.indexOf(generatedLtpaPasswordSetting);
const libertyConfigurationIndex = finalDockerStage.indexOf("RUN configure.sh");
if (
    generatedLtpaPasswordSettingIndex < 0 ||
    libertyConfigurationIndex < 0 ||
    generatedLtpaPasswordSettingIndex > libertyConfigurationIndex
) {
    throw new Error(
        "The final Docker stage must disable generated LTPA passwords before RUN configure.sh.",
    );
}

console.log(`Build toolchain configuration contract test passed for Maven ${wrapperVersion}.`);

function requiredProperty(contents, propertyName) {
    const matchingLine = contents
        .split(/\r?\n/u)
        .find((line) => line.startsWith(`${propertyName}=`));
    if (matchingLine === undefined) {
        throw new Error(`${propertyName} is missing from Maven Wrapper properties.`);
    }
    return matchingLine.slice(propertyName.length + 1).trim();
}

function requiredMatch(value, pattern, description) {
    const match = value.match(pattern);
    if (match === null) {
        throw new Error(`${description} does not match the required pinned form.`);
    }
    return match;
}

function requireEqual(actual, expected, description) {
    if (actual !== expected) {
        throw new Error(`${description} must be ${expected}, but was ${actual}.`);
    }
}

function requireSingleTrueSetting(contents, settingName, description) {
    const settingPattern = new RegExp(
        `<${settingName}>\\s*([^<]+?)\\s*</${settingName}>`,
        "gu",
    );
    const matches = [...contents.matchAll(settingPattern)];
    if (matches.length !== 1 || matches[0][1].trim() !== "true") {
        throw new Error(`${description}; expected exactly one <${settingName}>true</${settingName}> setting.`);
    }
}

function nextPatchVersion(version) {
    const versionParts = version.split(".").map(Number);
    versionParts[2] += 1;
    return versionParts.join(".");
}
