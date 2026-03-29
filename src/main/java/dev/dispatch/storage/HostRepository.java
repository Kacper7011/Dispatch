package dev.dispatch.storage;

import dev.dispatch.core.model.AuthType;
import dev.dispatch.core.model.Host;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CRUD operations for SSH host profiles stored in SQLite. */
public class HostRepository {

  private static final Logger log = LoggerFactory.getLogger(HostRepository.class);

  private final DatabaseManager db;

  public HostRepository(DatabaseManager db) {
    this.db = db;
  }

  /** Returns all saved host profiles, ordered alphabetically by name. */
  public List<Host> findAll() {
    String sql = "SELECT * FROM hosts ORDER BY name";
    log.debug("Fetching all hosts");
    try (PreparedStatement stmt = connection().prepareStatement(sql);
        ResultSet rs = stmt.executeQuery()) {
      List<Host> hosts = new ArrayList<>();
      while (rs.next()) {
        hosts.add(mapRow(rs));
      }
      log.debug("Found {} hosts", hosts.size());
      return hosts;
    } catch (SQLException e) {
      throw new StorageException("Failed to fetch hosts", e);
    }
  }

  /** Returns the host with the given id, or empty if not found. */
  public Optional<Host> findById(long id) {
    String sql = "SELECT * FROM hosts WHERE id = ?";
    log.debug("Fetching host id={}", id);
    try (PreparedStatement stmt = connection().prepareStatement(sql)) {
      stmt.setLong(1, id);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new StorageException("Failed to fetch host id=" + id, e);
    }
  }

  /**
   * Persists a new host profile and sets its generated id.
   *
   * @return the same host instance, with id populated
   */
  public Host save(Host host) {
    String sql =
        "INSERT INTO hosts (name, hostname, port, username, auth_type, key_path, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)";
    log.debug("Saving host: {}", host.getName());
    try (PreparedStatement stmt =
        connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      bindInsertParams(stmt, host);
      stmt.executeUpdate();
      try (ResultSet keys = stmt.getGeneratedKeys()) {
        if (keys.next()) {
          host.setId(keys.getLong(1));
        }
      }
      log.info("Host saved: {} (id={})", host.getName(), host.getId());
      return host;
    } catch (SQLException e) {
      throw new StorageException("Failed to save host: " + host.getName(), e);
    }
  }

  /** Updates name, hostname, port, username, auth_type, and key_path for an existing host. */
  public void update(Host host) {
    String sql =
        "UPDATE hosts"
            + " SET name=?, hostname=?, port=?, username=?, auth_type=?, key_path=?"
            + " WHERE id=?";
    log.debug("Updating host id={}", host.getId());
    try (PreparedStatement stmt = connection().prepareStatement(sql)) {
      stmt.setString(1, host.getName());
      stmt.setString(2, host.getHostname());
      stmt.setInt(3, host.getPort());
      stmt.setString(4, host.getUsername());
      stmt.setString(5, host.getAuthType().name());
      stmt.setString(6, host.getKeyPath());
      stmt.setLong(7, host.getId());
      stmt.executeUpdate();
      log.info("Host updated: {} (id={})", host.getName(), host.getId());
    } catch (SQLException e) {
      throw new StorageException("Failed to update host id=" + host.getId(), e);
    }
  }

  /** Deletes the host with the given id. No-op if the id does not exist. */
  public void delete(long id) {
    String sql = "DELETE FROM hosts WHERE id=?";
    log.debug("Deleting host id={}", id);
    try (PreparedStatement stmt = connection().prepareStatement(sql)) {
      stmt.setLong(1, id);
      stmt.executeUpdate();
      log.info("Host deleted: id={}", id);
    } catch (SQLException e) {
      throw new StorageException("Failed to delete host id=" + id, e);
    }
  }

  private Host mapRow(ResultSet rs) throws SQLException {
    Host host = new Host();
    host.setId(rs.getLong("id"));
    host.setName(rs.getString("name"));
    host.setHostname(rs.getString("hostname"));
    host.setPort(rs.getInt("port"));
    host.setUsername(rs.getString("username"));
    host.setAuthType(AuthType.valueOf(rs.getString("auth_type")));
    host.setKeyPath(rs.getString("key_path"));
    host.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
    return host;
  }

  private void bindInsertParams(PreparedStatement stmt, Host host) throws SQLException {
    LocalDateTime createdAt = host.getCreatedAt() != null ? host.getCreatedAt() : LocalDateTime.now();
    stmt.setString(1, host.getName());
    stmt.setString(2, host.getHostname());
    stmt.setInt(3, host.getPort());
    stmt.setString(4, host.getUsername());
    stmt.setString(5, host.getAuthType().name());
    stmt.setString(6, host.getKeyPath());
    stmt.setString(7, createdAt.toString());
  }

  private Connection connection() {
    return db.getConnection();
  }
}
