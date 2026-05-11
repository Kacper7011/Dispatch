package dev.dispatch.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Key-value store backed by the {@code app_settings} SQLite table. Used for persisting UI
 * preferences (e.g. file manager filter state) across sessions.
 */
public final class AppSettingsRepository {

  private static final Logger log = LoggerFactory.getLogger(AppSettingsRepository.class);

  private final DatabaseManager db;

  public AppSettingsRepository(DatabaseManager db) {
    this.db = db;
  }

  /** Returns the stored value for {@code key}, or {@code defaultValue} when no entry exists. */
  public String get(String key, String defaultValue) {
    String sql = "SELECT value FROM app_settings WHERE key = ?";
    try (PreparedStatement ps = connection().prepareStatement(sql)) {
      ps.setString(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString("value") : defaultValue;
      }
    } catch (SQLException e) {
      log.error("Failed to read setting '{}': {}", key, e.getMessage(), e);
      return defaultValue;
    }
  }

  /** Returns the stored boolean for {@code key}, or {@code defaultValue} when absent. */
  public boolean getBoolean(String key, boolean defaultValue) {
    String val = get(key, null);
    return val != null ? Boolean.parseBoolean(val) : defaultValue;
  }

  /** Persists (upserts) {@code value} under {@code key}. */
  public void set(String key, String value) {
    String sql = "INSERT OR REPLACE INTO app_settings(key, value) VALUES(?, ?)";
    try (PreparedStatement ps = connection().prepareStatement(sql)) {
      ps.setString(1, key);
      ps.setString(2, value);
      ps.executeUpdate();
    } catch (SQLException e) {
      log.error("Failed to save setting '{}': {}", key, e.getMessage(), e);
    }
  }

  private Connection connection() {
    return db.getConnection();
  }

  /** Convenience overload for boolean values. */
  public void setBoolean(String key, boolean value) {
    set(key, String.valueOf(value));
  }
}
