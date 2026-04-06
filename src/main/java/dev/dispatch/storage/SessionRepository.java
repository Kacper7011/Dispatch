package dev.dispatch.storage;

import dev.dispatch.core.model.Session;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists and retrieves SSH connection history from the {@code sessions} table. */
public class SessionRepository {

  private static final Logger log = LoggerFactory.getLogger(SessionRepository.class);

  private final DatabaseManager db;

  public SessionRepository(DatabaseManager db) {
    this.db = db;
  }

  /**
   * Records the start of a new SSH session for the given host.
   *
   * @return the persisted {@link Session} with its generated id
   */
  public Session openSession(long hostId) {
    String sql = "INSERT INTO sessions (host_id, connected_at) VALUES (?, ?)";
    LocalDateTime now = LocalDateTime.now();
    log.debug("Opening session for host id={}", hostId);
    try (PreparedStatement stmt =
        connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      stmt.setLong(1, hostId);
      stmt.setString(2, now.toString());
      stmt.executeUpdate();
      try (ResultSet keys = stmt.getGeneratedKeys()) {
        if (!keys.next()) {
          throw new StorageException("No generated key returned for new session");
        }
        Session session = new Session(hostId, now);
        session.setId(keys.getLong(1));
        log.info("Session opened: id={} for host id={}", session.getId(), hostId);
        return session;
      }
    } catch (SQLException e) {
      throw new StorageException("Failed to open session for host id=" + hostId, e);
    }
  }

  /**
   * Records the end of a session by setting its {@code disconnected_at} timestamp to now.
   *
   * <p>No-op if the session id does not exist.
   */
  public void closeSession(long sessionId) {
    String sql = "UPDATE sessions SET disconnected_at=? WHERE id=?";
    String now = LocalDateTime.now().toString();
    log.debug("Closing session id={}", sessionId);
    try (PreparedStatement stmt = connection().prepareStatement(sql)) {
      stmt.setString(1, now);
      stmt.setLong(2, sessionId);
      stmt.executeUpdate();
      log.info("Session closed: id={}", sessionId);
    } catch (SQLException e) {
      throw new StorageException("Failed to close session id=" + sessionId, e);
    }
  }

  /**
   * Returns all sessions for the given host, ordered from newest to oldest.
   *
   * @param hostId the host whose session history to fetch
   */
  public List<Session> findByHostId(long hostId) {
    String sql = "SELECT * FROM sessions WHERE host_id=? ORDER BY connected_at DESC";
    log.debug("Fetching sessions for host id={}", hostId);
    try (PreparedStatement stmt = connection().prepareStatement(sql)) {
      stmt.setLong(1, hostId);
      try (ResultSet rs = stmt.executeQuery()) {
        List<Session> sessions = mapRows(rs);
        log.debug("Found {} sessions for host id={}", sessions.size(), hostId);
        return sessions;
      }
    } catch (SQLException e) {
      throw new StorageException("Failed to fetch sessions for host id=" + hostId, e);
    }
  }

  /**
   * Returns the most recent sessions across all hosts, useful for a "recent connections" view.
   *
   * @param limit maximum number of sessions to return
   */
  public List<Session> findRecent(int limit) {
    String sql = "SELECT * FROM sessions ORDER BY connected_at DESC LIMIT ?";
    log.debug("Fetching {} most recent sessions", limit);
    try (PreparedStatement stmt = connection().prepareStatement(sql)) {
      stmt.setInt(1, limit);
      try (ResultSet rs = stmt.executeQuery()) {
        List<Session> sessions = mapRows(rs);
        log.debug("Found {} recent sessions", sessions.size());
        return sessions;
      }
    } catch (SQLException e) {
      throw new StorageException("Failed to fetch recent sessions", e);
    }
  }

  private List<Session> mapRows(ResultSet rs) throws SQLException {
    List<Session> sessions = new ArrayList<>();
    while (rs.next()) {
      sessions.add(mapRow(rs));
    }
    return sessions;
  }

  private Session mapRow(ResultSet rs) throws SQLException {
    Session session = new Session();
    session.setId(rs.getLong("id"));
    session.setHostId(rs.getLong("host_id"));
    session.setConnectedAt(LocalDateTime.parse(rs.getString("connected_at")));
    String disconnectedAt = rs.getString("disconnected_at");
    if (disconnectedAt != null) {
      session.setDisconnectedAt(LocalDateTime.parse(disconnectedAt));
    }
    return session;
  }

  private Connection connection() {
    return db.getConnection();
  }
}
