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
