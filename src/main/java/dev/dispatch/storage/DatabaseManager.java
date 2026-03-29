package dev.dispatch.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the SQLite database connection and schema migrations.
 *
 * <p>Opens a single shared connection to {@code ~/.dispatch/dispatch.db} and creates all tables on
 * first run. Implements {@link AutoCloseable} — close in {@code App.stop()}.
 */
public class DatabaseManager implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
  private static final String DB_DIR = ".dispatch";
  private static final String DB_FILE = "dispatch.db";

  private final Connection connection;

  /** Opens the default database at {@code ~/.dispatch/dispatch.db}. */
  public DatabaseManager() {
    this(resolveDefaultDbPath());
  }

  /** Opens a database at the given path — useful for tests. */
  public DatabaseManager(Path dbPath) {
    this.connection = openConnection(dbPath);
    runMigrations();
  }

  private static Path resolveDefaultDbPath() {
    return Paths.get(System.getProperty("user.home"), DB_DIR, DB_FILE);
  }

  private Connection openConnection(Path dbPath) {
    try {
      Files.createDirectories(dbPath.getParent());
      String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
      log.debug("Opening SQLite connection at {}", dbPath);
      Connection conn = DriverManager.getConnection(url);
      log.info("Database connected: {}", dbPath);
      return conn;
    } catch (IOException e) {
      throw new StorageException("Cannot create database directory: " + dbPath.getParent(), e);
    } catch (SQLException e) {
      throw new StorageException("Cannot open database at: " + dbPath, e);
    }
  }

  private void runMigrations() {
    log.debug("Running schema migrations");
    try (Statement stmt = connection.createStatement()) {
      createHostsTable(stmt);
      createSessionsTable(stmt);
      createAppSettingsTable(stmt);
      log.info("Schema up to date");
    } catch (SQLException e) {
      throw new StorageException("Schema migration failed", e);
    }
  }

  private void createHostsTable(Statement stmt) throws SQLException {
    stmt.execute(
        """
        CREATE TABLE IF NOT EXISTS hosts (
          id          INTEGER PRIMARY KEY AUTOINCREMENT,
          name        TEXT    NOT NULL,
          hostname    TEXT    NOT NULL,
          port        INTEGER NOT NULL DEFAULT 22,
          username    TEXT    NOT NULL,
          auth_type   TEXT    NOT NULL,
          key_path    TEXT,
          created_at  TEXT    NOT NULL
        )
        """);
    log.debug("Table 'hosts' ready");
  }

  private void createSessionsTable(Statement stmt) throws SQLException {
    stmt.execute(
        """
        CREATE TABLE IF NOT EXISTS sessions (
          id               INTEGER PRIMARY KEY AUTOINCREMENT,
          host_id          INTEGER NOT NULL REFERENCES hosts(id),
          connected_at     TEXT    NOT NULL,
          disconnected_at  TEXT
        )
        """);
    log.debug("Table 'sessions' ready");
  }

  private void createAppSettingsTable(Statement stmt) throws SQLException {
    stmt.execute(
        """
        CREATE TABLE IF NOT EXISTS app_settings (
          key    TEXT PRIMARY KEY,
          value  TEXT NOT NULL
        )
        """);
    log.debug("Table 'app_settings' ready");
  }

  /** Returns the shared database connection. All access is synchronized. */
  public synchronized Connection getConnection() {
    return connection;
  }

  @Override
  public void close() {
    try {
      if (!connection.isClosed()) {
        connection.close();
        log.info("Database connection closed");
      }
    } catch (SQLException e) {
      log.error("Error closing database connection: {}", e.getMessage(), e);
    }
  }
}
