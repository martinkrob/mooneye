// Thin wrapper over the MoonEye REST API. Every call rejects with an Error whose message is
// already fit to show to the user.

async function request(method, path, body, { raw = false } = {}) {
    const init = { method, headers: {} };
    if (body !== undefined) {
        init.headers["Content-Type"] = "application/json";
        init.body = JSON.stringify(body);
    }
    init.headers["Accept"] = raw ? "image/jpeg, application/json" : "application/json";

    let res;
    try {
        res = await fetch(path, init);
    } catch (e) {
        throw new Error("Server MoonEye neodpovídá.");
    }
    if (!res.ok) {
        throw new Error(await errorMessage(res));
    }
    if (raw) {
        return res.blob();
    }
    if (res.status === 204) {
        return null;
    }
    const text = await res.text();
    return text ? JSON.parse(text) : null;
}

async function errorMessage(res) {
    const text = await res.text().catch(() => "");
    try {
        const json = JSON.parse(text);
        // Our own errors use {error}; Javalin's built-in ones use {title}.
        return translate(json.error || json.title || json.message || text);
    } catch {
        return translate(text) || `Chyba ${res.status}`;
    }
}

// The API speaks English; the few messages a user can trigger are translated here.
const MESSAGES = [
    [/^Name is required/, "Vyplň název tiskárny."],
    [/^Moonraker address is required/, "Vyplň adresu Moonrakeru."],
    [/^Moonraker address is not a valid URL/, "Adresa Moonrakeru není platná."],
    [/^Webcam URL must start/, "URL kamery musí začínat http:// nebo https://."],
    [/^Sensitivity must be/, "Citlivost musí být mezi 0,5 a 2,0."],
    [/^Region of interest is too small/, "Vybraný výřez je příliš malý."],
    [/^Region of interest needs/, "Výřez musí mít všechny čtyři hodnoty."],
    [/^Printer not found/, "Tiskárna neexistuje."],
    [/^Nothing is printing/, "Tiskárna právě netiskne."],
    [/^No frame analysed yet/, "Zatím nebyl analyzován žádný snímek."],
    [/^This printer has no webcam/, "Tiskárna nemá nastavenou kameru."],
    [/^Camera: (.*)/, "Kamera: $1"],
    [/^Model is loading/, "Model se ještě načítá."],
    [/^Model unavailable: (.*)/, "Model není k dispozici: $1"],
];

function translate(message) {
    if (!message) return message;
    for (const [pattern, replacement] of MESSAGES) {
        if (pattern.test(message)) return message.replace(pattern, replacement);
    }
    return message;
}

export const api = {
    health: () => request("GET", "/api/health"),
    settings: () => request("GET", "/api/settings"),
    saveSettings: (s) => request("PUT", "/api/settings", s),
    events: (limit = 200) => request("GET", `/api/events?limit=${limit}`),

    printers: () => request("GET", "/api/printers"),
    createPrinter: (p) => request("POST", "/api/printers", p),
    updatePrinter: (id, p) => request("PUT", `/api/printers/${id}`, p),
    deletePrinter: (id) => request("DELETE", `/api/printers/${id}`),
    status: (id) => request("GET", `/api/printers/${id}/status`),
    analyse: (id) => request("POST", `/api/printers/${id}/analyse`),
    mute: (id) => request("POST", `/api/printers/${id}/mute`),
    resetBaseline: (id) => request("POST", `/api/printers/${id}/reset-baseline`),

    testMoonraker: (p) => request("POST", "/api/test/moonraker", p),
    testCamera: (p) => request("POST", "/api/test/camera", p, { raw: true }),
    testNotification: (p) => request("POST", "/api/test/notification", p),

    frameUrl: (id, stamp) => `/api/printers/${id}/frame?t=${encodeURIComponent(stamp)}`,
    cameraUrl: (id) => `/api/printers/${id}/camera?t=${Date.now()}`,
};
