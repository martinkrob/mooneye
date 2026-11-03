// Light / dark / follow-the-system theme. The choice is per browser (localStorage); the resolved
// theme is written to <html data-theme>, which the CSS keys off.

const KEY = "mooneye.theme";
const media = window.matchMedia("(prefers-color-scheme: dark)");

export function themeMode() {
    try {
        return localStorage.getItem(KEY) || "auto";
    } catch {
        return "auto";
    }
}

export function setThemeMode(mode) {
    try {
        localStorage.setItem(KEY, mode);
    } catch {
        // Private mode or blocked storage: the choice simply does not persist.
    }
    apply(mode);
}

function apply(mode) {
    const dark = mode === "dark" || (mode === "auto" && media.matches);
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    document.querySelectorAll("[data-theme-mode]").forEach((b) =>
        b.setAttribute("aria-pressed", String(b.dataset.themeMode === mode)));
}

export function initTheme() {
    apply(themeMode());
    media.addEventListener("change", () => {
        if (themeMode() === "auto") apply("auto");
    });
}
