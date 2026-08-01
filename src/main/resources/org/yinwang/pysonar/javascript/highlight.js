(function () {
    "use strict";

    var highlighted = [];
    var tooltip;
    var tooltipTimer;

    function symbolElements(symbolId) {
        if (!symbolId) {
            return [];
        }
        return Array.prototype.filter.call(
            document.querySelectorAll("[xid]"),
            function (element) {
                return element.getAttribute("xid") === symbolId;
            }
        );
    }

    function clearHighlight() {
        highlighted.forEach(function (element) {
            element.classList.remove("active");
        });
        highlighted = [];
    }

    window.highlight = function (symbolId) {
        clearHighlight();
        highlighted = symbolElements(symbolId);
        highlighted.forEach(function (element) {
            element.classList.add("active");
        });
    };

    function hideTooltip() {
        window.clearTimeout(tooltipTimer);
        tooltip.classList.remove("is-visible");
        tooltip.setAttribute("aria-hidden", "true");
    }

    function positionTooltip(target) {
        var rect = target.getBoundingClientRect();
        var margin = 12;
        var left = rect.left + rect.width / 2;
        var top = rect.bottom + 10;

        tooltip.style.left = left + "px";
        tooltip.style.top = top + "px";
        tooltip.style.transform = "translateX(-50%)";

        var tipRect = tooltip.getBoundingClientRect();
        if (tipRect.right > window.innerWidth - margin) {
            tooltip.style.left = window.innerWidth - margin - tipRect.width / 2 + "px";
        }
        if (tipRect.left < margin) {
            tooltip.style.left = margin + tipRect.width / 2 + "px";
        }
        if (tipRect.bottom > window.innerHeight - margin) {
            tooltip.style.top = rect.top - tipRect.height - 10 + "px";
        }
    }

    function showTooltip(target) {
        var message = target.getAttribute("data-tooltip");
        if (!message) {
            return;
        }
        window.clearTimeout(tooltipTimer);
        tooltip.textContent = message;
        tooltip.setAttribute("aria-hidden", "false");
        positionTooltip(target);
        tooltipTimer = window.setTimeout(function () {
            tooltip.classList.add("is-visible");
            positionTooltip(target);
        }, 60);
    }

    function interactiveTarget(event) {
        return event.target.closest("a[xid]");
    }

    window.addEventListener("DOMContentLoaded", function () {
        tooltip = document.getElementById("symbol-tooltip");

        document.addEventListener("mouseover", function (event) {
            var target = interactiveTarget(event);
            if (!target) {
                return;
            }
            window.highlight(target.getAttribute("xid"));
            showTooltip(target);
        });

        document.addEventListener("mouseout", function (event) {
            var target = interactiveTarget(event);
            if (target && !target.contains(event.relatedTarget)) {
                hideTooltip();
                clearHighlight();
            }
        });

        document.addEventListener("focusin", function (event) {
            var target = interactiveTarget(event);
            if (target) {
                window.highlight(target.getAttribute("xid"));
                showTooltip(target);
            }
        });

        document.addEventListener("focusout", function (event) {
            if (interactiveTarget(event)) {
                hideTooltip();
                clearHighlight();
            }
        });

        document.addEventListener("keydown", function (event) {
            if (event.key === "Escape") {
                hideTooltip();
                clearHighlight();
            }
        });

        window.addEventListener("scroll", hideTooltip, true);
        window.addEventListener("resize", hideTooltip);
    });
}());
