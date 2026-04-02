package dev.dispatch.ssh;

import dev.dispatch.core.model.Host;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Manages the lifecycle of SSH sessions across all homelab hosts. */
public class SshService implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SshService.class);

  private final Map<Long, SshSession> sessions = new ConcurrentHashMap<>();

  /** Creates the service. JSch nie potrzebuje współdzielonego klienta — każda sesja tworzy własny. */
  public SshService() {
    log.info("SshService started");
  }

  /**
   * Connects to a host and authenticates. Blocks until connected or throws.
   *
   * <p>If a session already exists for the host, it is disconnected first.
   */
  public SshSession connect(Host host, SshCredentials credentials) {
    disconnectIfPresent(host.getId());
    log.debug("Initiating connection to {}", host.getName());
    SshSession session = new SshSession(host);
    session.setOnLost(this::onSessionLost);
    session.connect(credentials);
    sessions.put(host.getId(), session);
    return session;
  }

  /** Disconnects the active session for the given host id. No-op if no session exists. */
  public void disconnect(long hostId) {
    disconnectIfPresent(hostId);
  }

  /** Returns the active session for the given host id, if one exists. */
  public Optional<SshSession> getSession(long hostId) {
    return Optional.ofNullable(sessions.get(hostId));
  }

  /** Returns a read-only view of all active sessions. */
  public Collection<SshSession> getAllSessions() {
    return Collections.unmodifiableCollection(sessions.values());
  }

  /** Disconnects all sessions. Call from {@code App.stop()}. */
  @Override
  public void close() {
    log.info("Shutting down SshService ({} active sessions)", sessions.size());
    sessions.values().forEach(SshSession::disconnect);
    sessions.clear();
    log.info("SshService stopped");
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void disconnectIfPresent(long hostId) {
    SshSession existing = sessions.remove(hostId);
    if (existing != null) {
      log.debug("Closing existing session for host id={}", hostId);
      existing.disconnect();
    }
  }

  private void onSessionLost(SshSession session) {
    log.warn("Session LOST for host {} — kept in map for reconnect", session.getHost().getName());
  }
}
