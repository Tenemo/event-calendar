(function () {
    window.configureCalendarSchedule = function () {
        const options = this.cfg.options;
        options.firstDay = 1;
        options.fixedWeekCount = false;
        options.dayMaxEventRows = 3;
        options.navLinks = true;
        options.nowIndicator = true;
        options.initialView = "dayGridMonth";
        options.eventTimeFormat = {
            hour: "2-digit",
            minute: "2-digit",
            hour12: false,
        };
        options.views = {
            dayGridMonth: {
                dayHeaderFormat: {weekday: "short"},
            },
            timeGridWeek: {
                dayHeaderFormat: {weekday: "short", day: "numeric"},
            },
        };
    };

    window.showEventDialog = function () {
        window.requestAnimationFrame(function () {
            const eventDialog = PF("eventDialog");
            if (eventDialog) {
                eventDialog.show();
            }
        });
    };

    window.focusEventDialog = function () {
        window.requestAnimationFrame(function () {
            const eventDialog = document.querySelector(".event-dialog.ui-dialog");
            if (!eventDialog) {
                return;
            }
            const titleInput = eventDialog.querySelector("input[id$='eventTitle']");
            if (titleInput && titleInput.getClientRects().length > 0) {
                titleInput.focus();
                return;
            }
            const closeButton = eventDialog.querySelector(".ui-dialog-titlebar-close");
            if (closeButton) {
                closeButton.focus();
            }
        });
    };

    window.completeEventMutation = function (ajaxArguments) {
        const eventDialog = document.querySelector(".event-dialog.ui-dialog");
        if (ajaxArguments && ajaxArguments.validationFailed) {
            window.focusFirstInvalidControl(eventDialog || document);
            return;
        }
        if (!ajaxArguments || ajaxArguments.operationSucceeded !== true) {
            window.focusElementByIdSuffix("messages");
            return;
        }

        const calendarSchedule = PF("calendarSchedule");
        if (ajaxArguments.eventDate) {
            calendarSchedule.calendar.gotoDate(ajaxArguments.eventDate);
        }
        calendarSchedule.update();
        PF("eventDialog").hide();
        window.focusElementByIdSuffix("calendar-heading");
    };
}());
