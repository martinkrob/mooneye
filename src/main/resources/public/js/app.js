// MoonEye web UI - entry point.

import { api } from "./api.js";
import { PrinterCard, FrameView, riskView, renderRisk } from "./cards.js";
import { EVENT_LABELS, fmt } from "./format.js";
import { initPrinterForm, openPrinterForm } from "./printer-form.js";
import { initTheme, setThemeMode } from "./theme.js";
import { $, $$, closeMenu, confirmDialog, drawBoxes, fitFrameToImage, initDialogs, initMenus, isMenuOpen, showMenu, toast } from "./ui.js";

/** Status poll period. Cheap: the backend serves it from its telemetry cache. */
const POLL_MS = 2000;
/** The printer list itself changes only through this UI, but another browser may edit it. */
const LIST_REFRESH_MS = 30_000;

const cards = new Map();     // printer id -> PrinterCard
let menuCard = null;         // card whose "⋯" menu is open
let health = null;
let livePrinterId = null;
let liveFrame = null;

// ---------------------------------------------------------------------------- startup

initTheme();
initDialogs();
initMenus();
initPrinterForm(() => loadPrinters());
wireMainMenu();
wireCardMenu();
wireSettings();
initLive();

loadPrinters();
pollHealth();
setInterval(pollHealth, 15_000);
setInterval(() => { if (!document.hidden) pollStatuses(); }, POLL_MS);
setInterval(() => { if (!document.hidden) loadPrinters(); }, LIST_REFRESH_MS);
document.addEventListener("visibilitychange", () => { if (!document.hidden) pollStatuses(); });

// ---------------------------------------------------------------------------- printers

async function loadPrinters() {
    let printers;
    try {
        printers = await api.printers();
    } catch (e) {
        toast(e.message, "fail");
        return;
    }
    const container = $("#printers");
    const seen = new Set();
    for (const p of printers) {
        seen.add(p.id);
        let card = cards.get(p.id);
        if (!card) {
            card = new PrinterCard(p, cardHandlers);
            cards.set(p.id, card);
        }
        card.update(p, card.status);
        container.append(card.el); // keeps the server's order
    }
    for (const [id, card] of cards) {
        if (!seen.has(id)) {
            card.el.remove();
            cards.delete(id);
        }
    }
    $("#empty").hidden = printers.length > 0;
    pollStatuses();
}

async function pollStatuses() {
    await Promise.all([...cards.values()].map(async (card) => {
        try {
            const status = await api.status(card.printer.id);
            card.update(card.printer, status);
            if (livePrinterId === card.printer.id) updateLive(card.printer, status);
        } catch {
            // Server restarting or printer just deleted - the next poll will tell.
        }
    }));
}

const cardHandlers = {
    onLive: (printer) => openLive(printer),
    onMenu: (card, anchor) => {
        const menu = $("#card-menu");
        if (isMenuOpen(menu) && menuCard === card) {
            closeMenu();
            return;
        }
        menuCard = card;
        const printing = (card.status?.telemetry?.printState || "").toLowerCase() === "printing";
        $('[data-card-action="mute"]', menu).disabled = !printing || !!card.status?.job?.alertsMuted;
        $('[data-card-action="analyse"]', menu).disabled = !card.printer.webcamUrl;
        showMenu(menu, anchor, { floating: true });
    },
    onToggleWatch: async (printer, on, input) => {
        try {
            const saved = await api.updatePrinter(printer.id, { ...printer, detectionEnabled: on });
            cards.get(printer.id)?.update(saved, cards.get(printer.id).status);
            toast(on ? `Hlídání tiskárny ${printer.name} zapnuto.` : `Hlídání tiskárny ${printer.name} vypnuto.`);
        } catch (e) {
            input.checked = !on;
            toast(e.message, "fail");
        }
    },
};

function wireCardMenu() {
    $("#card-menu").addEventListener("click", async (e) => {
        const button = e.target.closest("[data-card-action]");
        if (!button || button.disabled || !menuCard) return;
        const printer = menuCard.printer;
        closeMenu();
        switch (button.dataset.cardAction) {
            case "live": openLive(printer); break;
            case "edit": openPrinterForm(printer); break;
            case "analyse": analyse(printer); break;
            case "mute":
                try {
                    await api.mute(printer.id);
                    toast("Upozornění ztlumena do konce tisku.");
                    pollStatuses();
                } catch (err) { toast(err.message, "fail"); }
                break;
            case "reset":
                if (await confirmDialog("Vynulovat základní úroveň?",
                        `MoonEye zapomene, jak záběr kamery tiskárny ${printer.name} běžně vypadá, a začne se to učit znovu. `
                        + "Hodí se po přesunutí kamery nebo změně osvětlení.", "Vynulovat")) {
                    try {
                        await api.resetBaseline(printer.id);
                        toast("Základní úroveň vynulována.");
                    } catch (err) { toast(err.message, "fail"); }
                }
                break;
            case "delete":
                if (await confirmDialog(`Smazat tiskárnu ${printer.name}?`,
                        "Smaže se i celá historie tisků, incidenty a uložené snímky. Tuto akci nejde vrátit.", "Smazat")) {
                    try {
                        await api.deletePrinter(printer.id);
                        toast(`Tiskárna ${printer.name} smazána.`);
                        loadPrinters();
                    } catch (err) { toast(err.message, "fail"); }
                }
                break;
        }
    });
}

// ---------------------------------------------------------------------------- header

async function pollHealth() {
    const el = $("#health");
    const dot = $(".dot", el);
    const text = $(".health-text", el);
    try {
        health = await api.health();
        if (health.modelReady) {
            dot.className = "dot dot-ok";
            text.textContent = "Detekce připravena";
            el.title = "Model pro detekci selhání je načtený.";
        } else if ((health.modelError || "").startsWith("Model is loading")) {
            dot.className = "dot dot-warn";
            text.textContent = "Model se načítá";
            el.title = "Při prvním spuštění se stahuje model (~200 MB), může to trvat několik minut.";
        } else {
            dot.className = "dot dot-fail";
            text.textContent = "Detekce nedostupná";
            el.title = health.modelError || "Model není k dispozici.";
        }
    } catch {
        dot.className = "dot dot-fail";
        text.textContent = "Server neodpovídá";
        el.title = "MoonEye server neodpovídá.";
    }
}

function wireMainMenu() {
    const button = $("#main-menu-btn");
    const menu = $("#main-menu");
    button.addEventListener("click", () => (isMenuOpen(menu) ? closeMenu() : showMenu(menu, button)));

    document.addEventListener("click", (e) => {
        const action = e.target.closest("[data-action]")?.dataset.action;
        if (!action) return;
        closeMenu();
        switch (action) {
            case "add-printer": openPrinterForm(); break;
            case "settings": openSettings(); break;
            case "events": openEvents(); break;
            case "about": openAbout(); break;
        }
    });
    $$("[data-theme-mode]", menu).forEach((b) =>
        b.addEventListener("click", () => setThemeMode(b.dataset.themeMode)));
}

// ---------------------------------------------------------------------------- dialogs

function wireSettings() {
    const dlg = $("#settings-dialog");
    $("form", dlg).addEventListener("submit", async (e) => {
        e.preventDefault();
        const form = e.target;
        const error = $("[data-form-error]", dlg);
        error.textContent = "";
        try {
            await api.saveSettings({
                retentionPrintCount: Number(form.elements.retentionPrintCount.value),
                snapshotIntervalSec: Number(form.elements.snapshotIntervalSec.value),
            });
            dlg.close();
            toast("Nastavení uloženo.");
        } catch (err) {
            error.textContent = err.message;
        }
    });
}

async function openSettings() {
    const dlg = $("#settings-dialog");
    const form = $("form", dlg);
    $("[data-form-error]", dlg).textContent = "";
    try {
        const [s, h] = await Promise.all([api.settings(), api.health()]);
        form.elements.retentionPrintCount.value = s.retentionPrintCount;
        form.elements.snapshotIntervalSec.value = s.snapshotIntervalSec;
        const facts = [
            ["Verze", h.version],
            ["Model", h.modelReady ? "Obico, 1. generace – načtený" : (h.modelError || "nenačtený")],
            ["Tiskáren", String(cards.size)],
        ];
        $("[data-system]", dlg).innerHTML = facts.map(() => "<dt></dt><dd></dd>").join("");
        const cells = $$("[data-system] dt, [data-system] dd", dlg);
        facts.forEach(([k, v], i) => { cells[i * 2].textContent = k; cells[i * 2 + 1].textContent = v; });
        dlg.showModal();
    } catch (e) {
        toast(e.message, "fail");
    }
}

async function openEvents() {
    const dlg = $("#events-dialog");
    const body = $("[data-events]", dlg);
    body.innerHTML = `<tr><td colspan="4">Načítám…</td></tr>`;
    dlg.showModal();
    try {
        const events = await api.events(300);
        const names = new Map([...cards.values()].map((c) => [c.printer.id, c.printer.name]));
        if (!events.length) {
            body.innerHTML = `<tr><td colspan="4">Zatím žádné události.</td></tr>`;
            return;
        }
        body.replaceChildren(...events.map((ev) => {
            const tr = document.createElement("tr");
            [fmt.dateTime(ev.ts), ev.printerId ? (names.get(ev.printerId) || "smazaná") : "–",
             EVENT_LABELS[ev.eventType] || ev.eventType, ev.notes || ""].forEach((v) => {
                const td = document.createElement("td");
                td.textContent = v;
                tr.append(td);
            });
            return tr;
        }));
    } catch (e) {
        body.innerHTML = "";
        toast(e.message, "fail");
    }
}

function openAbout() {
    const dlg = $("#about-dialog");
    $("[data-version]", dlg).textContent = health?.version ? `verze ${health.version}` : "";
    dlg.showModal();
}

// ---------------------------------------------------------------------------- live view

function initLive() {
    const dlg = $("#live-dialog");
    liveFrame = new FrameView($("[data-live-frame]", dlg));
    dlg.addEventListener("close", () => { livePrinterId = null; });
}

function openLive(printer) {
    const dlg = $("#live-dialog");
    livePrinterId = printer.id;
    liveFrame.lastKey = null;
    $("[data-live-title]", dlg).textContent = `Živý náhled – ${printer.name}`;
    const card = cards.get(printer.id);
    updateLive(printer, card?.status);
    dlg.showModal();
}

function updateLive(printer, status) {
    const dlg = $("#live-dialog");
    liveFrame.update(printer, status);
    renderRisk($("[data-live-risk]", dlg), riskView(printer, status));
    const d = status?.detection;
    const facts = [
        ["Poslední snímek", d?.lastFrameAt ? fmt.dateTime(d.lastFrameAt) : "–"],
        ["Rámečků", d?.watching ? String(d.detections.length) : "–"],
        ["Součet jistot", d?.watching ? fmt.one(d.currentP) : "–"],
        ["Inference", d?.inferenceMs != null ? `${d.inferenceMs} ms` : "–"],
    ];
    const dl = $("[data-live-facts]", dlg);
    dl.innerHTML = facts.map(() => "<dt></dt><dd></dd>").join("");
    const cells = dl.querySelectorAll("dt, dd");
    facts.forEach(([k, v], i) => { cells[i * 2].textContent = k; cells[i * 2 + 1].textContent = v; });
}

// ---------------------------------------------------------------------------- one-off analysis

async function analyse(printer) {
    const dlg = $("#analyse-dialog");
    const frame = $("[data-analyse-frame]", dlg);
    const img = $("img", frame);
    const summary = $("[data-analyse-summary]", dlg);
    img.hidden = true;
    $(".boxes", frame).replaceChildren();
    summary.textContent = "Analyzuji snímek…";
    dlg.showModal();
    try {
        const r = await api.analyse(printer.id);
        img.onload = () => fitFrameToImage(frame, img);
        img.src = `data:image/jpeg;base64,${r.image}`;
        img.hidden = false;
        drawBoxes($(".boxes", frame), r.detections);
        const max = r.detections.reduce((m, d) => Math.max(m, d.confidence), 0);
        summary.textContent = r.detections.length
            ? `Model našel ${r.detections.length} ${r.detections.length === 1 ? "oblast" : r.detections.length < 5 ? "oblasti" : "oblastí"} `
              + `podobné selhání, nejvyšší jistota ${Math.round(max * 100)} %, součet ${fmt.one(r.frameScore)}. Analýza trvala ${r.inferenceMs} ms.`
            : `Model na snímku nic podezřelého nenašel. Analýza trvala ${r.inferenceMs} ms.`;
    } catch (e) {
        summary.textContent = e.message;
    }
}
