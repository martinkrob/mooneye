// Small UI primitives: toasts, confirmation, dropdown menus, dialogs.

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

export function toast(message, tone = "") {
    const el = document.createElement("div");
    el.className = `toast ${tone}`;
    el.textContent = message;
    $("#toasts").append(el);
    setTimeout(() => el.remove(), tone === "fail" ? 6000 : 3500);
}

/** Resolves true when the user confirms. */
export function confirmDialog(title, text, okLabel = "Potvrdit") {
    const dlg = $("#confirm-dialog");
    $("[data-confirm-title]", dlg).textContent = title;
    $("[data-confirm-text]", dlg).textContent = text;
    $("[data-confirm-ok]", dlg).textContent = okLabel;
    dlg.returnValue = "";
    dlg.showModal();
    return new Promise((resolve) => {
        dlg.addEventListener("close", () => resolve(dlg.returnValue === "ok"), { once: true });
    });
}

/** Wires every [data-close] button and a click on the backdrop to close its dialog. */
export function initDialogs() {
    $$("dialog").forEach((dlg) => {
        $$("[data-close]", dlg).forEach((b) => b.addEventListener("click", () => dlg.close()));
        dlg.addEventListener("click", (e) => {
            if (e.target === dlg) dlg.close(); // click on the backdrop
        });
    });
}

let openMenu = null;

/** Opens a menu; `anchor` is the button that toggles it. */
export function showMenu(menu, anchor, { floating = false } = {}) {
    if (openMenu && openMenu.menu !== menu) closeMenu();
    if (floating) {
        menu.hidden = false;
        const r = anchor.getBoundingClientRect();
        const w = menu.offsetWidth;
        const h = menu.offsetHeight;
        const left = Math.max(8, Math.min(r.right - w, window.innerWidth - w - 8));
        const below = r.bottom + 6 + h <= window.innerHeight;
        menu.style.left = `${left}px`;
        menu.style.top = `${below ? r.bottom + 6 : Math.max(8, r.top - h - 6)}px`;
    } else {
        menu.hidden = false;
    }
    anchor.setAttribute("aria-expanded", "true");
    openMenu = { menu, anchor };
    menu.querySelector("button:not(:disabled)")?.focus({ preventScroll: true });
}

export function closeMenu() {
    if (!openMenu) return;
    openMenu.menu.hidden = true;
    openMenu.anchor.setAttribute("aria-expanded", "false");
    openMenu = null;
}

export function isMenuOpen(menu) {
    return openMenu?.menu === menu;
}

export function initMenus() {
    document.addEventListener("click", (e) => {
        if (openMenu && !openMenu.menu.contains(e.target) && !openMenu.anchor.contains(e.target)) closeMenu();
    });
    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape" && openMenu) {
            const anchor = openMenu.anchor;
            closeMenu();
            anchor.focus();
        }
    });
    window.addEventListener("resize", closeMenu);
    window.addEventListener("scroll", closeMenu, { passive: true });
}

/** Draws detection boxes (frame fractions) into a .boxes overlay. */
export function drawBoxes(layer, detections, minConfidence = 0) {
    layer.replaceChildren(...(detections || [])
        .filter((d) => d.confidence >= minConfidence)
        .map((d) => {
            const box = document.createElement("div");
            box.className = "box" + (d.confidence >= 0.5 ? " strong" : "");
            box.style.left = `${d.x1 * 100}%`;
            box.style.top = `${d.y1 * 100}%`;
            box.style.width = `${(d.x2 - d.x1) * 100}%`;
            box.style.height = `${(d.y2 - d.y1) * 100}%`;
            const label = document.createElement("span");
            label.textContent = `${Math.round(d.confidence * 100)} %`;
            box.append(label);
            return box;
        }));
}

/**
 * Keeps a frame's box shape equal to the image's, so overlay boxes given in image fractions
 * land on the right pixels whatever the camera's aspect ratio.
 */
export function fitFrameToImage(frame, img) {
    if (img.naturalWidth && img.naturalHeight) {
        frame.style.aspectRatio = `${img.naturalWidth} / ${img.naturalHeight}`;
    }
}
