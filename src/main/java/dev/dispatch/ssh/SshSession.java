package dev.dispatch.ssh;

import dev.dispatch.core.model.AuthType;
import dev.dispatch.core.model.Host;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.core.CoreModuleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a single SSH connection to one host.
 *
 * <p>Manages the session state machine, keep-alive, and exposes {@link #exec(String)} for running
 * commands and {@link #openShell()} for the terminal emulator. All blocking methods must be called
 * from a virtual thread.
 */
public class SshSession {

  private static final Logger log = LoggerFactory.getLogger(SshSession.class);

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration KEEPALIVE_INTERVAL = Duration.ofSeconds(30);
  private final Host host;
  private volatile SessionState state = SessionState.DISCONNECTED;
  private volatile ClientSession minaSession;
  private volatile Consumer<SshSession> onLostCallback;

  public SshSession(Host host) {
    this.host = host;
  }

  /**
   * Establishes the SSH connection and authenticates.
   *
   * @throws SshException if connection or authentication fails
   */
  public void connect(SshClient client, SshCredentials credentials) {
    transitionTo(SessionState.CONNECTING);
    log.debug("Connecting to {} on port {}", host.getHostname(), host.getPort());
    try {
      ConnectFuture future =
          client.connect(host.getUsername(), host.getHostname(), host.getPort());
      future.verify(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      minaSession = future.getSession();
      configureKeepAlive();
      registerDisconnectListener();
      authenticate(credentials);
      transitionTo(SessionState.CONNECTED);
      log.info("SSH session established: {}", host.getName());
    } catch (IOException e) {
      transitionTo(SessionState.DISCONNECTED);
      log.error("Connection failed to {}: {}", host.getName(), e.getMessage(), e);
      throw new SshException("Failed to connect to " + host.getName() + ": " + e.getMessage(), e);
    }
  }

  /** Closes the SSH session gracefully. Safe to call in any state. */
  public void disconnect() {
    if (state == SessionState.DISCONNECTED || state == SessionState.DISCONNECTING) {
      return;
    }
    transitionTo(SessionState.DISCONNECTING);
    log.debug("Disconnecting from {}", host.getName());
    try {
      if (minaSession != null) {
        minaSession.close(false).await(5, TimeUnit.SECONDS);
      }
    } catch (IOException e) {
      log.warn("Error while disconnecting from {}: {}", host.getName(), e.getMessage());
    } finally {
      transitionTo(SessionState.DISCONNECTED);
      log.info("Disconnected from {}", host.getName());
    }
  }

  /**
   * Executes a remote command and returns stdout, stderr, and the exit code.
   *
   * @throws SshException if the session is not connected or the command fails to run
   */
  public ExecResult exec(String command) {
    requireConnected();
    log.debug("exec on {}: {}", host.getName(), command);
    try (ChannelExec channel = minaSession.createExecChannel(command)) {
      ByteArrayOutputStream stdout = new ByteArrayOutputStream();
      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      channel.setOut(stdout);
      channel.setErr(stderr);
      channel.open().verify(EXEC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), EXEC_TIMEOUT.toMillis());
      int exitCode = channel.getExitStatus() != null ? channel.getExitStatus() : -1;
      ExecResult result =
          new ExecResult(
              stdout.toString(StandardCharsets.UTF_8),
              stderr.toString(StandardCharsets.UTF_8),
              exitCode);
      log.debug("exec result on {}: {}", host.getName(), result);
      return result;
    } catch (IOException e) {
      throw new SshException("exec failed on " + host.getName() + ": " + command, e);
    }
  }

  /**
   * Opens a PTY shell channel sized to the given terminal dimensions.
   *
   * @param columns initial terminal width in characters
   * @param rows    initial terminal height in characters
   * @return an open {@link ChannelShell} — caller is responsible for closing it
   * @throws SshException if the session is not connected
   */
  public ChannelShell openShell(int columns, int rows) {
    requireConnected();
    log.debug("Opening shell on {} ({}x{})", host.getName(), columns, rows);
    try {
      ChannelShell channel = minaSession.createShellChannel();
      // Request a PTY so the remote shell behaves as an interactive terminal.
      // Override the default vt100 type with xterm-256color so programs on the server
      // (ls, git, vim, etc.) know they can emit 256-color ANSI escape sequences.
      channel.setupSensibleDefaultPty();
      channel.setPtyType("xterm-256color");
      channel.setPtyColumns(columns);
      channel.setPtyLines(rows);
      channel.open().verify(EXEC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      log.info("Shell opened on {} ({}x{})", host.getName(), columns, rows);
      return channel;
    } catch (IOException e) {
      throw new SshException("Failed to open shell on " + host.getName(), e);
    }
  }

  /** Registers a callback invoked when the session drops unexpectedly (LOST state). */
  public void setOnLost(Consumer<SshSession> callback) {
    this.onLostCallback = callback;
  }

  public SessionState getState() {
    return state;
  }

  public Host getHost() {
    return host;
  }

  public boolean isConnected() {
    return state == SessionState.CONNECTED;
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void authenticate(SshCredentials credentials) throws IOException {
    if (host.getAuthType() == AuthType.KEY) {
      authenticateWithKey(credentials);
    } else {
      authenticateWithPassword(credentials);
    }
  }

  private void authenticateWithPassword(SshCredentials credentials) throws IOException {
    log.debug("Authenticating with password on {}", host.getName());
    minaSession.addPasswordIdentity(credentials.getPassword());
    minaSession.auth().verify(AUTH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void authenticateWithKey(SshCredentials credentials) throws IOException {
    log.debug("Authenticating with key {} on {}", host.getKeyPath(), host.getName());
    FileKeyPairProvider keyProvider =
        new FileKeyPairProvider(Collections.singletonList(Paths.get(host.getKeyPath())));
    if (credentials.hasPassphrase()) {
      // Passphrase is used to decrypt the private key file
      keyProvider.setPasswordFinder((session, resource, index) -> credentials.getKeyPassphrase());
    }
    minaSession.setKeyIdentityProvider(keyProvider);
    minaSession.auth().verify(AUTH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void configureKeepAlive() {
    // Send keep-alive every 30 s; MINA marks session as LOST when the TCP connection drops
    CoreModuleProperties.HEARTBEAT_INTERVAL.set(minaSession, KEEPALIVE_INTERVAL);
    log.debug("Keep-alive configured: interval={}s", KEEPALIVE_INTERVAL.getSeconds());
  }

  private void registerDisconnectListener() {
    minaSession.addSessionListener(
        new SessionListener() {
          @Override
          public void sessionException(Session session, Throwable t) {
            if (state == SessionState.CONNECTED || state == SessionState.CONNECTING) {
              log.warn(
                  "Session exception on {}: {}", host.getName(), t.getMessage());
              markLost();
            }
          }

          @Override
          public void sessionClosed(Session session) {
            if (state == SessionState.CONNECTED) {
              log.warn("Session closed unexpectedly on {}", host.getName());
              markLost();
            }
          }
        });
  }

  private void markLost() {
    transitionTo(SessionState.LOST);
    Consumer<SshSession> cb = onLostCallback;
    if (cb != null) {
      cb.accept(this);
    }
  }

  private void transitionTo(SessionState next) {
    log.debug("Session state {}: {} → {}", host.getName(), state, next);
    state = next;
  }

  private void requireConnected() {
    if (state != SessionState.CONNECTED) {
      throw new SshException(
          "Session for " + host.getName() + " is not connected (state=" + state + ")");
    }
  }
}
