package dev.dispatch.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import dev.dispatch.core.config.AppConfig;
import dev.dispatch.core.model.AuthType;
import dev.dispatch.core.model.Host;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a single SSH connection to one host using JSch.
 *
 * <p>All blocking methods must be called from a virtual thread, never from the FX Application
 * Thread.
 */
public class SshSession {

  private static final Logger log = LoggerFactory.getLogger(SshSession.class);

  private static final int CONNECT_TIMEOUT_MS = AppConfig.SSH_CONNECT_TIMEOUT_SECONDS * 1_000;
  private static final int EXEC_TIMEOUT_MS = AppConfig.SSH_EXEC_TIMEOUT_SECONDS * 1_000;
  private static final int KEEPALIVE_INTERVAL_MS = AppConfig.SSH_KEEPALIVE_INTERVAL_SECONDS * 1_000;

  private final Host host;
  private volatile SessionState state = SessionState.DISCONNECTED;
  private volatile Session jschSession;
  private volatile Consumer<SshSession> onLostCallback;

  public SshSession(Host host) {
    this.host = host;
  }

  /**
   * Establishes the SSH connection and authenticates.
   *
   * @throws SshException if connection or authentication fails
   */
  public void connect(SshCredentials credentials) {
    transitionTo(SessionState.CONNECTING);
    log.debug("Connecting to {} on port {}", host.getHostname(), host.getPort());
    try {
      JSch jsch = new JSch();

      if (host.getAuthType() == AuthType.KEY) {
        String keyPath = resolveKeyPath(host.getKeyPath());
        if (credentials.hasPassphrase()) {
          jsch.addIdentity(keyPath, credentials.getKeyPassphrase());
        } else {
          jsch.addIdentity(keyPath);
        }
        log.debug("Authenticating with key {} on {}", keyPath, host.getName());
      }

      jschSession = jsch.getSession(host.getUsername(), host.getHostname(), host.getPort());
      // Accept all host keys — homelab app, equivalent of AcceptAllServerKeyVerifier.
      jschSession.setConfig("StrictHostKeyChecking", "no");
      jschSession.setConfig(
          "PreferredAuthentications",
          host.getAuthType() == AuthType.KEY ? "publickey" : "password");

      if (host.getAuthType() == AuthType.PASSWORD) {
        jschSession.setPassword(credentials.getPassword());
        log.debug("Authenticating with password on {}", host.getName());
      }

      jschSession.setServerAliveInterval(KEEPALIVE_INTERVAL_MS);
      jschSession.setServerAliveCountMax(AppConfig.SSH_KEEPALIVE_MAX_FAILURES);
      log.debug("Keep-alive configured: interval={}s", KEEPALIVE_INTERVAL_MS / 1000);

      jschSession.connect(CONNECT_TIMEOUT_MS);
      transitionTo(SessionState.CONNECTED);
      log.info("SSH session established: {}", host.getName());

    } catch (JSchException e) {
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
    if (jschSession != null) {
      jschSession.disconnect();
    }
    transitionTo(SessionState.DISCONNECTED);
    log.info("Disconnected from {}", host.getName());
  }

  /**
   * Executes a remote command and returns stdout, stderr, and the exit code.
   *
   * @throws SshException if the session is not connected or the command fails to run
   */
  public ExecResult exec(String command) {
    requireConnected();
    log.debug("exec on {}: {}", host.getName(), command);
    ChannelExec channel = null;
    try {
      channel = (ChannelExec) jschSession.openChannel("exec");
      channel.setCommand(command);

      ByteArrayOutputStream stdout = new ByteArrayOutputStream();
      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      channel.setOutputStream(stdout);
      channel.setErrStream(stderr);

      channel.connect(EXEC_TIMEOUT_MS);

      long deadline = System.currentTimeMillis() + EXEC_TIMEOUT_MS;
      while (!channel.isClosed() && System.currentTimeMillis() < deadline) {
        try {
          Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }
      }

      int exitCode = channel.getExitStatus();
      ExecResult result =
          new ExecResult(
              stdout.toString(StandardCharsets.UTF_8),
              stderr.toString(StandardCharsets.UTF_8),
              exitCode);
      log.debug("exec result on {}: {}", host.getName(), result);
      return result;

    } catch (JSchException e) {
      throw new SshException("exec failed on " + host.getName() + ": " + command, e);
    } finally {
      if (channel != null) {
        channel.disconnect();
      }
    }
  }

  /**
   * Opens an interactive PTY shell channel.
   *
   * @param columns initial terminal width in characters
   * @param rows initial terminal height in characters
   * @return array of {@code [ChannelShell, InputStream stdout, OutputStream stdin]} — caller is
   *     responsible for closing the channel via {@code ((ChannelShell) result[0]).disconnect()}
   * @throws SshException if the session is not connected
   */
  public Object[] openShell(int columns, int rows) {
    requireConnected();
    log.debug("Opening shell on {} ({}x{})", host.getName(), columns, rows);
    try {
      ChannelShell channel = (ChannelShell) jschSession.openChannel("shell");
      channel.setPtyType("xterm-256color");
      channel.setPtySize(columns, rows, 0, 0);

      InputStream stdout = channel.getInputStream();
      OutputStream stdin = channel.getOutputStream();

      channel.connect(CONNECT_TIMEOUT_MS);
      log.info("Shell opened on {} ({}x{})", host.getName(), columns, rows);
      return new Object[] {channel, stdout, stdin};

    } catch (JSchException | IOException e) {
      throw new SshException("Failed to open shell on " + host.getName(), e);
    }
  }

  /**
   * Opens an interactive exec channel with a PTY, running the given command. Equivalent to calling
   * {@code ssh host <command>} with an allocated pseudo-terminal — suitable for {@code docker exec
   * -it}.
   *
   * @param command full shell command to execute on the remote host
   * @param columns initial terminal width
   * @param rows initial terminal height
   * @return array of {@code [ChannelExec, InputStream stdout, OutputStream stdin]}
   * @throws SshException if the session is not connected or JSch cannot open the channel
   */
  public Object[] openInteractiveExec(String command, int columns, int rows) {
    requireConnected();
    log.debug("Opening interactive exec on {} ({}x{}): {}", host.getName(), columns, rows, command);
    try {
      ChannelExec channel = (ChannelExec) jschSession.openChannel("exec");
      channel.setPtyType("xterm-256color");
      channel.setPtySize(columns, rows, 0, 0);
      channel.setPty(true);
      channel.setCommand(command);

      InputStream stdout = channel.getInputStream();
      OutputStream stdin = channel.getOutputStream();

      channel.connect(CONNECT_TIMEOUT_MS);
      log.info("Interactive exec opened on {} ({}x{})", host.getName(), columns, rows);
      return new Object[] {channel, stdout, stdin};

    } catch (JSchException | IOException e) {
      throw new SshException("Failed to open interactive exec on " + host.getName(), e);
    }
  }

  /**
   * Opens a raw JSch channel of the given type. Package-private — used by TunnelService.
   *
   * @throws SshException if the session is not connected or JSch fails to open the channel
   */
  com.jcraft.jsch.Channel openJschChannel(String type) {
    requireConnected();
    try {
      return jschSession.openChannel(type);
    } catch (com.jcraft.jsch.JSchException e) {
      throw new SshException("Failed to open JSch channel '" + type + "' on " + host.getName(), e);
    }
  }

  /** Registers a callback invoked when the session drops unexpectedly (LOST state). */
  public void setOnLost(Consumer<SshSession> callback) {
    this.onLostCallback = callback;
  }

  /**
   * Transitions to {@link SessionState#LOST} and fires the onLost callback. Called by the terminal
   * bridge when the SSH output stream ends unexpectedly.
   */
  public void markAsLost() {
    if (state == SessionState.CONNECTED) {
      transitionTo(SessionState.LOST);
      if (onLostCallback != null) {
        onLostCallback.accept(this);
      }
    }
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

  private static String resolveKeyPath(String raw) {
    if (raw != null && raw.startsWith("~")) {
      return Paths.get(System.getProperty("user.home"), raw.substring(1)).toString();
    }
    return raw;
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
