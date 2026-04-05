package dev.dispatch.docker;

import com.jcraft.jsch.ChannelExec;
import java.io.InputStream;
import java.io.OutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a JSch {@link ChannelExec} running {@code docker exec -it} on the remote SSH host.
 *
 * <p>Stdin/stdout are the raw JSch channel streams — the same mechanism used by the regular SSH
 * terminal ({@link dev.dispatch.ssh.terminal.SshTerminalBridge}), so interactive input works
 * reliably without any HTTP-hijacking concerns.
 *
 * <p>Obtain instances via {@link DockerService#openExecSession(String)}. Call {@link #close()} when
 * the tab is closed to disconnect the SSH channel.
 */
public class DockerExecSession implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DockerExecSession.class);

  private final ChannelExec channel;
  private final InputStream stdoutReader;
  private final OutputStream stdinWriter;

  /** Package-private — created exclusively by {@link DockerService#openExecSession(String)}. */
  DockerExecSession(ChannelExec channel, InputStream stdout, OutputStream stdin) {
    this.channel = channel;
    this.stdoutReader = stdout;
    this.stdinWriter = stdin;
  }

  /** Stream to read output from the container's stdout/stderr. */
  public InputStream getStdoutReader() {
    return stdoutReader;
  }

  /** Stream to write user input into the container's stdin. */
  public OutputStream getStdinWriter() {
    return stdinWriter;
  }

  /**
   * Resizes the PTY. Delegates to the underlying JSch channel — same mechanism as the regular SSH
   * terminal resize.
   */
  public void resize(int cols, int rows) {
    channel.setPtySize(cols, rows, 0, 0);
    log.debug("Exec PTY resized to {}×{}", cols, rows);
  }

  /** Disconnects the SSH exec channel and releases resources. Safe to call multiple times. */
  @Override
  public void close() {
    channel.disconnect();
    log.info("DockerExecSession closed");
  }
}
