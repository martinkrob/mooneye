// Add / edit printer drawer.

import { api } from "./api.js";
import { fmt } from "./format.js";
import { $, $$, toast } from "./ui.js";

const dlg = () => $("#printer-form");

const TEXT_FIELDS = ["name", "moonrakerUrl", "webcamUrl", "webhookUrl", "mqttBroker", "mqttTopic", "mqttUsername", "mqttClientId"];
const SECRET_FIELDS = ["apiKey", "mqttPassword"];
const CHECKBOXES = ["enabled", "detectionEnabled", "webhookTelemetry", "mqttTelemetry", "m117Status"];

const DEFAULTS = {
    enabled: true, detectionEnabled: true, onFailure: "PAUSE", sensitivity: 1.0,
    webhookTelemetry: false, mqttTelemetry: false, m117Status: false,
};

let editing = null;     // printer being edited, null when adding
let roi = null;         // {x, y, w, h} in frame fractions, or null for the whole frame
let onSaved = () => {};

export function initPrinterForm(savedCallback) {
    onSaved = savedCallback;
    const d = dlg();
    const form = $("form", d);

    $$("[data-tab]", d).forEach((tab) => tab.addEventListener("click", () => showTab(tab.dataset.tab)));
    $('[name="sensitivity"]', d).addEventListener("input", updateSensitivityLabel);
    $('[data-test="moonraker"]', d).addEventListener("click", testMoonraker);
    $('[data-test="camera"]', d).addEventListener("click", testCamera);
    $('[data-test="notification"]', d).addEventListener("click", testNotification);
    $("[data-roi-load]", d).addEventListener("click", loadRoiImage);
    $("[data-roi-clear]", d).addEventListener("click", () => { roi = null; drawRoi(); });
    initRoiDrag();

    form.addEventListener("submit", async (e) => {
        e.preventDefault();
        await save();
    });
}

export function openPrinterForm(printer = null) {
    editing = printer;
    const d = dlg();
    const form = $("form", d);
    form.reset();
    const p = { ...DEFAULTS, ...(printer || {}) };

    $("#pf-title").textContent = printer ? `Upravit tiskárnu ${printer.name}` : "Přidat tiskárnu";
    for (const f of TEXT_FIELDS) form.elements[f].value = p[f] ?? "";
    for (const f of SECRET_FIELDS) form.elements[f].value = "";
    for (const f of CHECKBOXES) form.elements[f].checked = !!p[f];
    form.elements.sensitivity.value = p.sensitivity;
    $$('[name="onFailure"]', form).forEach((r) => { r.checked = r.value === p.onFailure; });

    form.elements.apiKey.placeholder = printer?.apiKeySet ? "uložen – ponech prázdné pro zachování" : "";
    form.elements.mqttPassword.placeholder = printer?.mqttPasswordSet ? "uloženo – ponech prázdné" : "";

    roi = p.roiX != null ? { x: p.roiX, y: p.roiY, w: p.roiW, h: p.roiH } : null;
    $("[data-roi-image]", d).removeAttribute("src");
    $("[data-roi-placeholder]", d).hidden = false;
    $("[data-camera-test]", d).hidden = true;
    $$(".test-result", d).forEach((el) => { el.textContent = ""; el.className = "test-result"; });
    $$("[aria-invalid]", d).forEach((el) => el.removeAttribute("aria-invalid"));
    $("[data-form-error]", d).textContent = "";
    updateSensitivityLabel();
    drawRoi();
    showTab("connection");
    d.showModal();
    form.elements.name.focus();
}

function showTab(name) {
    const d = dlg();
    $$("[data-tab]", d).forEach((t) => t.setAttribute("aria-selected", String(t.dataset.tab === name)));
    $$("[data-panel]", d).forEach((p) => { p.hidden = p.dataset.panel !== name; });
    if (name === "detection" && !$("[data-roi-image]", d).getAttribute("src") && $('[name="webcamUrl"]', d).value.trim()) {
        loadRoiImage();
    }
}

function updateSensitivityLabel() {
    const d = dlg();
    $("[data-sensitivity-value]", d).textContent = fmt.one(Number($('[name="sensitivity"]', d).value));
}

/** The printer as the form currently describes it. Secrets are sent only when typed in. */
function collect() {
    const form = $("form", dlg());
    const p = { id: editing?.id };
    for (const f of TEXT_FIELDS) p[f] = form.elements[f].value.trim() || null;
    for (const f of SECRET_FIELDS) p[f] = form.elements[f].value || null;
    for (const f of CHECKBOXES) p[f] = form.elements[f].checked;
    p.sensitivity = Number(form.elements.sensitivity.value);
    p.onFailure = form.querySelector('[name="onFailure"]:checked')?.value || "PAUSE";
    Object.assign(p, roi ? { roiX: roi.x, roiY: roi.y, roiW: roi.w, roiH: roi.h } : { roiX: null, roiY: null, roiW: null, roiH: null });
    return p;
}

async function save() {
    const d = dlg();
    const form = $("form", d);
    const error = $("[data-form-error]", d);
    error.textContent = "";
    $$("[aria-invalid]", d).forEach((el) => el.removeAttribute("aria-invalid"));

    for (const f of ["name", "moonrakerUrl"]) {
        if (!form.elements[f].value.trim()) {
            form.elements[f].setAttribute("aria-invalid", "true");
            showTab("connection");
            form.elements[f].focus();
            error.textContent = f === "name" ? "Vyplň název tiskárny." : "Vyplň adresu Moonrakeru.";
            return;
        }
    }

    const button = $("[data-save]", d);
    button.disabled = true;
    try {
        const p = collect();
        const saved = editing ? await api.updatePrinter(editing.id, p) : await api.createPrinter(p);
        d.close();
        toast(editing ? "Tiskárna uložena." : `Tiskárna ${saved.name} přidána.`);
        onSaved(saved);
    } catch (e) {
        error.textContent = e.message;
    } finally {
        button.disabled = false;
    }
}

function result(name, text, tone = "") {
    const el = $(`[data-result="${name}"]`, dlg());
    el.textContent = text;
    el.className = `test-result ${tone}`;
}

async function testMoonraker() {
    const p = collect();
    if (!p.moonrakerUrl) return result("moonraker", "Vyplň adresu Moonrakeru.", "fail");
    result("moonraker", "Připojuji…");
    try {
        const r = await api.testMoonraker(p);
        if (r.ok) {
            result("moonraker", `Připojeno – Klipper: ${r.klipperState}, tisk: ${r.printState}`, "ok");
        } else {
            result("moonraker", `Nepovedlo se: ${r.message !== "null" ? r.message : r.klipperState}`, "fail");
        }
    } catch (e) {
        result("moonraker", e.message, "fail");
    }
}

async function fetchCameraImage() {
    const p = collect();
    if (!p.webcamUrl) throw new Error("Vyplň URL kamery.");
    return URL.createObjectURL(await api.testCamera(p));
}

async function testCamera() {
    const img = $("[data-camera-test]", dlg());
    result("camera", "Načítám snímek…");
    try {
        img.src = await fetchCameraImage();
        img.hidden = false;
        result("camera", "Kamera odpovídá.", "ok");
    } catch (e) {
        img.hidden = true;
        result("camera", e.message, "fail");
    }
}

async function testNotification() {
    result("notification", "Odesílám…");
    try {
        const r = await api.testNotification(collect());
        const text = r.results.map((line) => line
            .replace(/^webhook: OK$/, "webhook: odesláno")
            .replace(/^mqtt: OK$/, "MQTT: odesláno")
            .replace(/^No notification channel is configured\.$/, "Není nastavený žádný kanál.")).join(" · ");
        const failed = r.results.some((line) => !/: OK$/.test(line));
        result("notification", text, failed ? "fail" : "ok");
    } catch (e) {
        result("notification", e.message, "fail");
    }
}

// ------------------------------------------------------------------ region of interest

async function loadRoiImage() {
    const d = dlg();
    const img = $("[data-roi-image]", d);
    const info = $("[data-roi-info]", d);
    info.textContent = "Načítám snímek…";
    info.className = "test-result";
    try {
        img.src = await fetchCameraImage();
        await img.decode();
        $("[data-roi-placeholder]", d).hidden = true;
        drawRoi();
    } catch (e) {
        info.textContent = e.message;
        info.className = "test-result fail";
    }
}

function drawRoi() {
    const d = dlg();
    const rect = $("[data-roi-rect]", d);
    const info = $("[data-roi-info]", d);
    const hasImage = !!$("[data-roi-image]", d).getAttribute("src");
    if (!roi) {
        rect.hidden = true;
        if (hasImage || !info.classList.contains("fail")) {
            info.textContent = "Sleduje se celý snímek.";
            info.className = "test-result";
        }
        return;
    }
    rect.hidden = !hasImage;
    Object.assign(rect.style, {
        left: `${roi.x * 100}%`, top: `${roi.y * 100}%`, width: `${roi.w * 100}%`, height: `${roi.h * 100}%`,
    });
    info.textContent = `Výřez ${Math.round(roi.w * 100)} × ${Math.round(roi.h * 100)} % snímku.`;
    info.className = "test-result ok";
}

function initRoiDrag() {
    const d = dlg();
    const editor = $("[data-roi-editor]", d);
    const img = $("[data-roi-image]", d);
    let start = null;

    const point = (e) => {
        const r = img.getBoundingClientRect();
        return {
            x: Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)),
            y: Math.min(1, Math.max(0, (e.clientY - r.top) / r.height)),
        };
    };

    editor.addEventListener("pointerdown", (e) => {
        if (!img.getAttribute("src")) return;
        editor.setPointerCapture(e.pointerId);
        start = point(e);
        roi = { x: start.x, y: start.y, w: 0, h: 0 };
        drawRoi();
    });
    editor.addEventListener("pointermove", (e) => {
        if (!start) return;
        const p = point(e);
        roi = {
            x: Math.min(start.x, p.x), y: Math.min(start.y, p.y),
            w: Math.abs(p.x - start.x), h: Math.abs(p.y - start.y),
        };
        drawRoi();
    });
    const end = () => {
        if (!start) return;
        start = null;
        // A click without a real drag (or a sliver) means "never mind" - the backend refuses
        // regions under 5 % anyway.
        if (roi && (roi.w < 0.05 || roi.h < 0.05)) roi = null;
        drawRoi();
    };
    editor.addEventListener("pointerup", end);
    editor.addEventListener("pointercancel", end);
}
