// /static/js/tutorials/dashboard.js
(function () {
    // Simple dashboard navigation: clicking a tile navigates to the target page.
    // Tutorials are started from the page dropdown + modal flow, not from this file.

    document.addEventListener("DOMContentLoaded", () => {
        document.querySelectorAll(".tutorial-tile").forEach(tile => {
            tile.addEventListener("click", () => {
                const href = tile.dataset.href;
                if (href) window.location.href = href;
            });
        });
    });
})();
