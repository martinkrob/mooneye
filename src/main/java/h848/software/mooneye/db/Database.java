package h848.software.mooneye.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the H2 connection pool and hands out one repository per aggregate.
 */
public final class Database implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(Database.class);

    private final HikariDataSource dataSource;
    private final Jdbi jdbi;

    private final SettingsRepository settings;
    private final PrinterRepository printers;
    private final JobRepository jobs;
    private final PredictionRepository predictions;
    private final IncidentRepository incidents;
    private final DetectionLogRepository detectionLog;
    private final EventRepository events;

    public Database(Path dbPath) {
        this("jdbc:h2:file:" + dbPath.toAbsolutePath()
                // AUTO_SERVER lets the H2 console attach to the live file for debugging.
                + ";AUTO_SERVER=TRUE");
    }

    /** Accepts any H2 URL, e.g. {@code jdbc:h2:mem:test;DB_CLOSE_DELAY=-1} in tests. */
    public Database(String jdbcUrl) {
        logger.info("Opening database {}", jdbcUrl);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(8);
        config.setPoolName("mooneye-db");
        this.dataSource = new HikariDataSource(config);
        this.jdbi = Jdbi.create(dataSource);

        Migrations.apply(jdbi);

        this.settings = new SettingsRepository(jdbi);
        this.printers = new PrinterRepository(jdbi);
        this.jobs = new JobRepository(jdbi);
        this.predictions = new PredictionRepository(jdbi);
        this.incidents = new IncidentRepository(jdbi);
        this.detectionLog = new DetectionLogRepository(jdbi);
        this.events = new EventRepository(jdbi);
    }

    public SettingsRepository settings() { return settings; }
    public PrinterRepository printers() { return printers; }
    public JobRepository jobs() { return jobs; }
    public PredictionRepository predictions() { return predictions; }
    public IncidentRepository incidents() { return incidents; }
    public DetectionLogRepository detectionLog() { return detectionLog; }
    public EventRepository events() { return events; }

    public Jdbi jdbi() {
        return jdbi;
    }

    @Override
    public void close() {
        if (!dataSource.isClosed()) {
            logger.info("Closing database.");
            dataSource.close();
        }
    }
}
