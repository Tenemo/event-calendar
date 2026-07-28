(function () {
    const eligibleControlSelector = [
        "input:not([type='hidden']):not([disabled]):not([readonly])",
        "select:not([disabled])",
        "textarea:not([disabled]):not([readonly])",
        "button:not([disabled])",
    ].join(", ");

    function isEligibleFocusTarget(element) {
        return element
            && element.matches(eligibleControlSelector)
            && element.tabIndex >= 0
            && element.getAttribute("aria-hidden") !== "true"
            && element.getClientRects().length > 0;
    }

    function findFocusTarget(root, candidateSelector) {
        for (const candidate of root.querySelectorAll(candidateSelector)) {
            if (isEligibleFocusTarget(candidate)) {
                return candidate;
            }
            const descendant = candidate.querySelector(eligibleControlSelector);
            if (isEligibleFocusTarget(descendant)) {
                return descendant;
            }
        }
        return null;
    }

    function hasErrorMessage() {
        return Boolean(document.querySelector(
            ".lightweight-message-error, .ui-messages-error, .ui-message-error"));
    }

    window.focusFirstInvalidControl = function (root) {
        const invalidControl = findFocusTarget(
            root || document,
            "[aria-invalid='true'], .ui-state-error");
        if (invalidControl) {
            invalidControl.focus();
            return true;
        }
        return false;
    };

    window.focusElementByIdSuffix = function (idSuffix) {
        window.requestAnimationFrame(function () {
            const element = document.querySelector("[id$='" + idSuffix + "']");
            if (element) {
                element.focus();
            }
        });
    };

    window.focusAfterAjax = function (ajaxArguments, successIdSuffix) {
        const validationFailed = ajaxArguments && ajaxArguments.validationFailed;
        if (validationFailed && window.focusFirstInvalidControl(document)) {
            return;
        }
        window.focusElementByIdSuffix(validationFailed ? "messages" : successIdSuffix);
    };

    window.focusConfirmationCancelButton = function () {
        window.setTimeout(function () {
            const cancelButton = document.querySelector(".ui-confirmdialog-no");
            if (cancelButton) {
                cancelButton.focus();
            }
        }, 0);
    };

    window.focusInitialPageControl = function () {
        window.requestAnimationFrame(function () {
            const activeElement = document.activeElement;
            const focusAlreadyPlaced = activeElement
                && activeElement !== document.body
                && activeElement !== document.documentElement;
            if (focusAlreadyPlaced || window.location.hash) {
                return;
            }

            const focusContainer = document.querySelector("[data-initial-focus='true']");
            if (!focusContainer) {
                return;
            }

            const target = findFocusTarget(
                focusContainer,
                "[aria-invalid='true'], .ui-state-error")
                || (hasErrorMessage()
                    ? findFocusTarget(focusContainer, "[data-error-focus='true']")
                    : null)
                || findFocusTarget(focusContainer, eligibleControlSelector);
            if (target) {
                target.focus();
            }
        });
    };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", window.focusInitialPageControl, {once: true});
    } else {
        window.focusInitialPageControl();
    }
}());
