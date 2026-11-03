package h848.software.mooneye.db;

import h848.software.mooneye.model.AppSettings;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/** Global key/value settings. Missing keys read as their defaults, so nothing needs seeding. */
public final class SettingsRepository {

    static final String RETENTION_PRINTS = "retention_print_count";
    static final String SNAPSHOT_INTERVAL = "snapshot_interval_sec";

    private final Jdbi jdbi;

    SettingsRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public AppSettings load() {
        return jdbi.withHandle(h -> {
            AppSettings s = new AppSettings();
            s.setRetentionPrintCount(intOr(get(h, RETENTION_PRINTS), AppSettings.DEFAULT_RETENTION_PRINTS, 1));
            s.setSnapshotIntervalSec(intOr(get(h, SNAPSHOT_INTERVAL), AppSettings.DEFAULT_SNAPSHOT_INTERVAL_SEC, 0));
            return s;
        });
    }

    public void save(AppSettings s) {
        jdbi.useTransaction(h -> {
            put(h, RETENTION_PRINTS, String.valueOf(Math.max(1, s.getRetentionPrintCount())));
            put(h, SNAPSHOT_INTERVAL, String.valueOf(Math.max(0, s.getSnapshotIntervalSec())));
        });
    }

    private static Optional<String> get(Handle h, String key) {
        return h.createQuery("SELECT v FROM settings WHERE k = :k")
                .bind("k", key)
                .mapTo(String.class)
                .findOne();
    }

    private static void put(Handle h, String key, String value) {
        h.createUpdate("MERGE INTO settings (k, v) KEY (k) VALUES (:k, :v)")
                .bind("k", key)
                .bind("v", value)
                .execute();
    }

    private static int intOr(Optional<String> raw, int fallback, int min) {
        try {
            return raw.map(v -> Math.max(min, Integer.parseInt(v.trim()))).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
