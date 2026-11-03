// Czech formatting helpers.

const number1 = new Intl.NumberFormat("cs-CZ", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const number0 = new Intl.NumberFormat("cs-CZ", { maximumFractionDigits: 0 });
const dateTime = new Intl.DateTimeFormat("cs-CZ", { day: "numeric", month: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit" });

export const fmt = {
    one: (v) => number1.format(v),
    int: (v) => number0.format(v),

    temp(current, target) {
        if (current == null) return "–";
        const now = number0.format(current);
        return target > 0 ? `${now} / ${number0.format(target)} °C` : `${now} °C`;
    },

    /** Seconds to "1:05 h" or "12 min". */
    duration(seconds) {
        if (seconds == null || !isFinite(seconds) || seconds < 0) return "–";
        const total = Math.round(seconds / 60);
        const h = Math.floor(total / 60);
        const m = total % 60;
        return h > 0 ? `${h}:${String(m).padStart(2, "0")} h` : `${m} min`;
    },

    /** Seconds to "3:20" for short countdowns. */
    clock(seconds) {
        const s = Math.max(0, Math.round(seconds));
        return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
    },

    filament(mm) {
        if (mm == null) return "–";
        return mm >= 1000 ? `${number1.format(mm / 1000)} m` : `${number0.format(mm)} mm`;
    },

    dateTime: (iso) => (iso ? dateTime.format(new Date(iso)) : "–"),

    /** "ttsk1.h848" out of "http://ttsk1.h848:7125". */
    host(url) {
        if (!url) return "";
        return url.replace(/^https?:\/\//, "").replace(/:7125$/, "").replace(/\/$/, "");
    },
};

/** Klipper print_stats.state -> label and colour class. */
export function printState(telemetry, online) {
    if (!telemetry) return { label: "Připojuji…", tone: "" };
    if (!online) return { label: "Offline", tone: "fail" };
    switch ((telemetry.printState || "").toLowerCase()) {
        case "printing": return { label: "Tiskne", tone: "ok" };
        case "paused": return { label: "Pozastaveno", tone: "warn" };
        case "complete": return { label: "Dokončeno", tone: "" };
        case "cancelled": return { label: "Zrušeno", tone: "" };
        case "error": return { label: "Chyba", tone: "fail" };
        case "standby": return { label: "Nečinná", tone: "" };
        default: return { label: telemetry.klipperState === "ready" ? "Nečinná" : (telemetry.klipperState || "Neznámý stav"), tone: "" };
    }
}

/** Event types from the backend's audit log, in words. */
export const EVENT_LABELS = {
    PRINT_STARTED: "Tisk zahájen",
    PRINT_ENDED: "Tisk skončil",
    AI_WARNING: "Varování",
    AI_PAUSE: "Tisk pozastaven (selhání)",
    AI_FAILURE: "Selhání oznámeno",
    AI_PAUSE_FAILED: "Pozastavení se nepovedlo",
    ALERTS_MUTED: "Upozornění ztlumena",
    PREDICTION_RESET: "Základní úroveň vynulována",
    PRINTER_ADDED: "Tiskárna přidána",
    PRINTER_UPDATED: "Tiskárna upravena",
    PRINTER_DELETED: "Tiskárna smazána",
    INCIDENT_REVIEWED: "Incident ohodnocen",
    MODEL_LOADED: "Model načten",
};
