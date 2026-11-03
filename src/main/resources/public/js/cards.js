// Printer card rendering. Cards are created once and updated in place on every poll, so images do
// not flicker and an open menu or a focused switch survives the refresh.

import { api } from "./api.js";
import { fmt, printState } from "./format.js";
import { $, drawBoxes, fitFrameToImage } from "./ui.js";

/** Boxes below this are not worth showing on the dashboard (same as Obico's visualisation). */
const VISIBLE_CONFIDENCE = 0.2;
/** How often a fresh camera frame is fetched while the printer is not being watched. */
const IDLE_CAMERA_REFRESH_MS = 10_000;

const ICON = (name) => `<svg class="icon"><use href="img/icons.svg#${name}"/></svg>`;

/**
 * What the risk meter should say, from the printer config and its /status.
 * tone: ok | warn | fail | idle;  value: 0..100 or null when there is nothing to show.
 */
export function riskView(printer, status) {
    const d = status?.detection;
    const t = status?.telemetry;
    const job = status?.job;
    const state = (t?.printState || "").toLowerCase();

    if (!printer.detectionEnabled) return { tone: "idle", value: null, text: "Detekce je vypnutá", icon: "bell-off", hideBar: true };
    if (!printer.webcamUrl) return { tone: "idle", value: null, text: "Bez kamery – detekce neběží", icon: "camera-off", hideBar: true };
    if (!status) return { tone: "idle", value: null, text: "Načítám…" };
    if (d && !d.modelReady) return { tone: "idle", value: null, text: d.modelError?.startsWith("Model is loading") ? "Model se načítá…" : "Model není k dispozici", icon: "info" };
    if (!status.online) return { tone: "idle", value: null, text: "Tiskárna je offline" };

    if (state === "paused") {
        return job?.aiPauses > 0
            ? { tone: "fail", value: pct(d?.score), text: "Pozastaveno kvůli podezření na selhání", icon: "info" }
            : { tone: "idle", value: null, text: "Tisk je pozastavený, detekce čeká" };
    }
    if (state !== "printing") return { tone: "idle", value: null, text: "Netiskne se, detekce čeká" };
    if (job?.alertsMuted) return { tone: "idle", value: pct(d?.score), text: "Upozornění ztlumena do konce tisku", icon: "bell-off" };
    if (d?.cameraError) return { tone: "idle", value: null, text: `Kamera nedostupná: ${d.cameraError}`, icon: "camera-off" };
    if (!d?.watching) return { tone: "idle", value: null, text: "Čekám na první snímek…" };
    if (d.warmupSeconds > 0) return { tone: "idle", value: null, text: `Zahřívání – hodnocení začne za ${fmt.clock(d.warmupSeconds)}` };

    const value = pct(d.score);
    switch (d.decision) {
        case "FAILURE":
            return { tone: "fail", value, text: printer.onFailure === "NOTIFY" ? "Selhání – upozornění odesláno" : "Selhání – tisk se pozastavuje", icon: "info" };
        case "WARNING":
            return { tone: "warn", value, text: "Varování – upozornění odesláno", icon: "info" };
        default:
            return { tone: "ok", value, text: "V pořádku" };
    }
}

function pct(score) {
    return score == null ? null : Math.round(Math.min(1, Math.max(0, score)) * 100);
}

export function renderRisk(container, view) {
    container.className = `risk ${view.tone}`;
    container.innerHTML = `
        <div class="risk-head">
            <span class="risk-title">Riziko selhání</span>
            <span class="risk-value">${view.value == null ? "–" : view.value + " %"}</span>
        </div>
        ${view.hideBar ? "" : `
        <div class="risk-bar"><div class="risk-fill" style="width:${view.value ?? 0}%"></div></div>
        <div class="risk-scale"><span>v pořádku</span><span>varování</span><span>selhání</span></div>`}
        <div class="risk-state">${view.icon ? ICON(view.icon) : ""}<span></span></div>`;
    container.querySelector(".risk-state span").textContent = view.text;
}

/** Remaining time from Klipper's own progress and duration - same estimate Mainsail shows by default. */
function remaining(t) {
    const p = t?.progress;
    if (!p || p < 1 || p >= 100) return null;
    return t.printDuration * (100 - p) / p;
}

/**
 * Feeds a camera frame element: the last analysed frame (with boxes) while a print is being
 * watched, otherwise a fresh webcam snapshot every few seconds.
 */
export class FrameView {
    constructor(frame) {
        this.frame = frame;
        this.img = $("img", frame);
        this.boxes = $(".boxes", frame);
        this.placeholder = $(".frame-placeholder", frame);
        this.lastKey = null;
        this.lastIdleFetch = 0;
        this.img.addEventListener("load", () => {
            this.placeholder.hidden = true;
            this.img.hidden = false;
            fitFrameToImage(this.frame, this.img);
        });
        this.img.addEventListener("error", () => this.showPlaceholder("camera-off", "Kamera nedostupná"));
    }

    showPlaceholder(icon, text) {
        this.img.hidden = true;
        this.boxes.replaceChildren();
        this.placeholder.hidden = false;
        this.placeholder.innerHTML = `${ICON(icon)}<span></span>`;
        this.placeholder.querySelector("span").textContent = text;
    }

    update(printer, status, minConfidence = VISIBLE_CONFIDENCE) {
        if (!printer.webcamUrl) {
            this.lastKey = null;
            this.showPlaceholder("camera-off", "Kamera není nastavená");
            return;
        }
        const d = status?.detection;
        const printing = (status?.telemetry?.printState || "").toLowerCase() === "printing";
        if (printing && d?.watching && d.lastFrameAt) {
            const key = "frame:" + d.lastFrameAt;
            if (key !== this.lastKey) {
                this.lastKey = key;
                this.img.src = api.frameUrl(printer.id, d.lastFrameAt);
                drawBoxes(this.boxes, d.detections, minConfidence);
            }
            return;
        }
        const now = Date.now();
        if (!this.lastKey?.startsWith("camera") || now - this.lastIdleFetch >= IDLE_CAMERA_REFRESH_MS) {
            this.lastKey = "camera";
            this.lastIdleFetch = now;
            this.boxes.replaceChildren();
            this.img.src = api.cameraUrl(printer.id);
        }
    }
}

export class PrinterCard {
    constructor(printer, handlers) {
        this.printer = printer;
        this.el = document.getElementById("printer-card").content.firstElementChild.cloneNode(true);
        this.el.dataset.id = printer.id;
        this.frame = new FrameView($(".frame", this.el));

        $(".frame", this.el).addEventListener("click", () => handlers.onLive(this.printer));
        $(".card-menu-btn", this.el).addEventListener("click", (e) => handlers.onMenu(this, e.currentTarget));
        $(".watch-toggle", this.el).addEventListener("change", (e) => handlers.onToggleWatch(this.printer, e.target.checked, e.target));
        this.update(printer, null);
    }

    update(printer, status) {
        this.printer = printer;
        this.status = status;
        const el = this.el;
        const t = status?.telemetry;
        const state = (t?.printState || "").toLowerCase();
        const active = status?.online && (state === "printing" || state === "paused");

        $(".printer-name", el).textContent = printer.name;
        $(".printer-host", el).textContent = fmt.host(printer.moonrakerUrl);
        const badge = printState(t, status?.online);
        const badgeEl = $(".badge", el);
        badgeEl.textContent = printer.enabled ? badge.label : "Neaktivní";
        badgeEl.className = `badge ${printer.enabled ? badge.tone : ""}`;
        const toggle = $(".watch-toggle", el);
        if (document.activeElement !== toggle) toggle.checked = printer.detectionEnabled;

        // Job
        const job = $(".job", el);
        job.hidden = !active;
        if (active) {
            $(".job-file", el).textContent = t.filename || "bez názvu";
            $(".job-file", el).title = t.filename || "";
            $(".progress-fill", el).style.width = `${Math.min(100, Math.max(0, t.progress || 0))}%`;
            const left = remaining(t);
            const parts = [
                `${fmt.int(t.progress || 0)} %`,
                `${state === "paused" ? "pozastaveno" : "tiskne"} ${fmt.duration(t.printDuration)}`,
                left != null ? `zbývá ~${fmt.duration(left)}` : null,
                t.liveZ > 0 ? `výška ${fmt.one(t.liveZ)} mm` : null,
            ];
            $(".job-meta", el).textContent = parts.filter(Boolean).join(" · ");
        }

        // Readings
        const readings = [];
        if (t && status?.online) {
            readings.push(["Tryska", fmt.temp(t.extruderTemp, t.extruderTarget)]);
            readings.push(["Podložka", fmt.temp(t.bedTemp, t.bedTarget)]);
            readings.push(["Ventilátor", `${fmt.int(t.fanSpeed || 0)} %`]);
            if (active) readings.push(["Filament", fmt.filament(t.filamentUsed)]);
        } else if (status && !status.online) {
            readings.push(["Stav", t?.klipperMessage ? "nedostupná" : "připojuji…"]);
        }
        $(".readings", el).innerHTML = readings
            .map(([k]) => `<div><dt>${k}</dt><dd></dd></div>`).join("");
        [...$(".readings", el).querySelectorAll("dd")].forEach((dd, i) => { dd.textContent = readings[i][1]; });

        renderRisk($(".risk", el), riskView(printer, status));

        const foot = printer.detectionEnabled && printer.webcamUrl
            ? `Při selhání: ${printer.onFailure === "NOTIFY" ? "jen upozornit" : "pozastavit tisk"} · citlivost ${fmt.one(printer.sensitivity)}`
              + (printer.roiX != null ? " · sleduje se výřez" : "")
            : "";
        $(".printer-foot", el).textContent = foot;

        if (printer.enabled) {
            this.frame.update(printer, status);
        } else {
            this.frame.showPlaceholder("camera-off", "Tiskárna je neaktivní");
        }
    }
}
