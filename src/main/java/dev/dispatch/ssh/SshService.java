package dev.dispatch.ssh;

import dev.dispatch.core.model.Host;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.sshd.client.SshClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of SSH sessions across all homelab hosts.
 *
 * <p>Holds a single shared {@link SshClient} (Apache MINA) and a map of active
 * {@link SshSession}s keyed by host id. All blocking methods (connect, exec) must be called from
 * virtual threads — never from the FX Application Thread.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * SshService ssh = new SshService();
 * Thread.ofVirtual().start(() -> {
 *   SshSession session = ssh.connect(host, SshCredentials.password("secret"));
 *   ExecResult result = session.exec("docker info");
 * });
 * }</pre>
 */
public class SshService implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SshService.class);

  private final SshClient client;
  private final Map<Long, SshSession> sessions = new ConcurrentHashMap<>();

  /** Creates the service and starts the underlying MINA SSH client. */
  public SshService() {
    this.client = SshClient.setUpDefaultClient();
    this.client.start();
    log.info("SshService started");
  }

  /**
   * Connects to a host and authenticates. Blocks until connected or throws.
   *
   * <p>If a session already exists for the host, it is disconnected first.
   *
   * @param host        the host profile to connect to
   * @param credentials the authentication credentials
   * @return the connected {@link SshSession}
   * @throws SshException if connection or authentication fails
   */
  public SshSession connect(Host host, SshCredentials credentials) {
    disconnectIfPresent(host.getId());
    log.debug("Initiating connection to {}", host.getName());
    SshSession session = new SshSession(host);
    session.setOnLost(lost -> onSessionLost(lost));
    session.connect(client, credentials);
    sessions.put(host.getId(), session);
    return session;
  }

  /**
   * Disconnects the active session for the given host id. No-op if no session exists.
   */
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

  /**
   * Disconnects all sessions and stops the MINA SSH client. Call from {@code App.stop()}.
   */
  @Override
  public void close() {
    log.info("Shutting down SshService ({} active sessions)", sessions.size());
    sessions.values().forEach(SshSession::disconnect);
    sessions.clear();
    client.stop();
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
    // Keep the session in the map so the UI can display the LOST state and offer reconnect.
    // The UI registers its own onLost callback via session.setOnLost() after connect() returns.
    log.warn(
        "Session LOST for host {} — kept in map for reconnect", session.getHost().getName());
  }
}
