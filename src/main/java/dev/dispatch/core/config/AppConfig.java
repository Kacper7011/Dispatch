package dev.dispatch.core.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Central registry of application-wide constants.
 *
 * <p>All tuneable values live here so they can be changed in one place. No magic numbers elsewhere.
 */
public final class AppConfig {

  // ── File system ───────────────────────────────────────────────────────────────

  /** Name of the hidden directory created in the user's home folder. */
  public static final String APP_DIR_NAME = ".dispatch";

  /** SQLite database file name inside {@link #APP_DIR_NAME}. */
  public static final String DB_FILE_NAME = "dispatch.db";

  /** Java KeyStore file name inside {@link #APP_DIR_NAME}. Stores SSH passwords/passphrases. */
  public static final String KEYSTORE_FILE_NAME = "dispatch.jks";

  /** Default SSH private key path, resolved against the user's home directory. */
  public static final String DEFAULT_SSH_KEY_RELATIVE = ".ssh/id_rsa";

  // ── SSH timeouts ──────────────────────────────────────────────────────────────

  /** Seconds to wait when establishing a new SSH connection before giving up. */
  public static final int SSH_CONNECT_TIMEOUT_SECONDS = 10;

  /** Seconds to wait for a remote command (exec channel) to complete. */
  public static final int SSH_EXEC_TIMEOUT_SECONDS = 30;

  /** Interval in seconds between SSH keep-alive packets. */
  public static final int SSH_KEEPALIVE_INTERVAL_SECONDS = 30;

  /** Number of consecutive keep-alive failures before marking the session as LOST. */
  public static final int SSH_KEEPALIVE_MAX_FAILURES = 3;

  // ── Terminal ──────────────────────────────────────────────────────────────────

  /** Default terminal width in characters used when opening a new shell. */
  public static final int TERMINAL_INITIAL_COLS = 220;

  /** Default terminal height in characters used when opening a new shell. */
  public static final int TERMINAL_INITIAL_ROWS = 50;

  // ── Docker ────────────────────────────────────────────────────────────────────

  /** Maximum number of log lines kept in memory per container log view. */
  public static final int DOCKER_LOG_MAX_LINES = 5_000;

  // ── UI / window ───────────────────────────────────────────────────────────────

  /** Default window width in pixels on first launch. */
  public static final int WINDOW_DEFAULT_WIDTH = 1280;

  /** Default window height in pixels on first launch. */
  public static final int WINDOW_DEFAULT_HEIGHT = 800;

  /** Minimum window width — enforced during manual resize. */
  public static final int WINDOW_MIN_WIDTH = 700;

  /** Minimum window height — enforced during manual resize. */
  public static final int WINDOW_MIN_HEIGHT = 480;

  // ── Convenience helpers ───────────────────────────────────────────────────────

  /** Returns the absolute path to the {@code ~/.dispatch} directory. */
  public static Path appDir() {
    return Paths.get(System.getProperty("user.home"), APP_DIR_NAME);
  }

  /** Returns the absolute path to the SQLite database file. */
  public static Path dbPath() {
    return appDir().resolve(DB_FILE_NAME);
  }

  /** Returns the absolute path to the Java KeyStore file. */
  public static Path keystorePath() {
    return appDir().resolve(KEYSTORE_FILE_NAME);
  }

  /** Returns the default SSH private key path for the current user. */
  public static Path defaultSshKeyPath() {
    return Paths.get(System.getProperty("user.home"), DEFAULT_SSH_KEY_RELATIVE);
  }

  private AppConfig() {}
}
