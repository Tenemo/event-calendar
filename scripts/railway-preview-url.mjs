const RAILWAY_BOT_LOGIN = "railway-app[bot]";
const RAILWAY_BOT_USER_ID = 68434857;
const RAILWAY_GITHUB_APP_SLUG = "railway-app";
const RAILWAY_COMMENT_MARKER = "<!-- railway-bot-comment-version=2 -->";
const PREVIEW_SERVICE_NAME = "shared-calendar-web";

export function requirePullRequestNumber(value) {
    const normalizedValue = String(value ?? "").trim();
    if (!/^[1-9][0-9]*$/.test(normalizedValue)) {
        throw new Error("The pull request number must be a positive integer.");
    }
    return normalizedValue;
}

export function resolveRailwayPreviewUrl(commentPages, pullRequestNumber) {
    const normalizedPullRequestNumber = requirePullRequestNumber(pullRequestNumber);
    const comments = flattenCommentPages(commentPages);
    const trustedComments = comments
        .filter(isTrustedRailwayComment)
        .sort((firstComment, secondComment) => commentRecency(secondComment) - commentRecency(firstComment));

    for (const comment of trustedComments) {
        const previewUrl = previewUrlFromComment(comment, normalizedPullRequestNumber);
        if (previewUrl !== null) {
            return previewUrl;
        }
    }

    throw new Error(
        `No trusted Railway preview URL was found for pull request ${normalizedPullRequestNumber}.`,
    );
}

function flattenCommentPages(commentPages) {
    if (!Array.isArray(commentPages)) {
        throw new Error("GitHub comments must be a JSON array.");
    }
    const flattenedComments = commentPages.flatMap((page) => (Array.isArray(page) ? page : [page]));
    if (flattenedComments.some((comment) => comment === null || typeof comment !== "object")) {
        throw new Error("Every GitHub comment must be a JSON object.");
    }
    return flattenedComments;
}

function isTrustedRailwayComment(comment) {
    return comment.user?.login === RAILWAY_BOT_LOGIN
        && comment.user?.id === RAILWAY_BOT_USER_ID
        && comment.performed_via_github_app?.slug === RAILWAY_GITHUB_APP_SLUG
        && typeof comment.body === "string"
        && comment.body.includes(RAILWAY_COMMENT_MARKER);
}

function commentRecency(comment) {
    const updatedTime = Date.parse(comment.updated_at ?? "");
    if (Number.isFinite(updatedTime)) {
        return updatedTime;
    }
    return Number.isSafeInteger(comment.id) ? comment.id : 0;
}

function previewUrlFromComment(comment, pullRequestNumber) {
    const environmentName = `event-calendar-pr-${pullRequestNumber}`;
    if (!comment.body.includes(environmentName)) {
        return null;
    }

    const serviceRowPattern = new RegExp(
        `^\\|\\s*${escapeRegularExpression(PREVIEW_SERVICE_NAME)}\\s*\\|[^\\n]*?\\|\\s*`
            + `\\[Web\\]\\((https:\\/\\/[^)\\s]+)\\)\\s*\\|`,
        "m",
    );
    const serviceRowMatch = comment.body.match(serviceRowPattern);
    if (serviceRowMatch === null) {
        return null;
    }

    const previewUrl = new URL(serviceRowMatch[1]);
    const expectedHostnameSuffix = `-${environmentName}.up.railway.app`;
    if (previewUrl.protocol !== "https:"
        || previewUrl.username !== ""
        || previewUrl.password !== ""
        || previewUrl.port !== ""
        || previewUrl.pathname !== "/"
        || previewUrl.search !== ""
        || previewUrl.hash !== ""
        || !previewUrl.hostname.endsWith(expectedHostnameSuffix)) {
        throw new Error("The trusted Railway comment contained an invalid preview service URL.");
    }
    return previewUrl.origin;
}

function escapeRegularExpression(value) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
