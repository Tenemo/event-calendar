window.copyLink = function (button) {
    const copyControl = button.closest(".copy-control");
    const linkInput = copyControl.querySelector("input");
    const copyStatus = copyControl.querySelector(".copy-status");

    function reportCopySuccess() {
        copyStatus.textContent = "Link copied.";
    }

    function selectForManualCopy() {
        linkInput.focus();
        linkInput.select();
        linkInput.setSelectionRange(0, linkInput.value.length);
        copyStatus.textContent = "Clipboard access is unavailable. Select the link and copy it manually.";
    }

    if (navigator.clipboard) {
        navigator.clipboard.writeText(linkInput.value).then(reportCopySuccess, selectForManualCopy);
        return;
    }
    selectForManualCopy();
};
