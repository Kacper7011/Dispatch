package dev.dispatch.ssh.terminal;

import com.jcraft.jsch.ChannelShell;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives calls from JavaScript (xterm.js) and forwards them to the SSH channel.
 *
 * <p>An instance is registered on the WebView's JS window object as {@code javaTerminal}, allowing
 * xterm.js to call {@code javaTerminal.sendInput(data)} and {@code javaTerminal.resize(cols, rows)}
 * directly.
 *
 * <p>All methods run on the JavaFX WebView JS thread — IO is dispatched to a virtual thread.
 */
public class JavaTerminalHandler {

  private static final Logger log = LoggerFactory.getLogger(JavaTerminalHandler.class);

  private final ChannelShell channel;
  private final OutputStream stdin;

  public JavaTerminalHandler(ChannelShell channel, OutputStream stdin) {
    this.channel = channel;
    this.stdin = stdin;
  }

  /** Called by xterm.js for every keystroke and paste event. */
  public void sendInput(String data) {
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                stdin.write(data.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
              } catch (IOException e) {
                log.warn("Failed to send input to SSH channel: {}", e.getMessage());
              }
            });
  }

  /** Called by xterm.js when the terminal is resized. Sends a PTY window-change signal. */
  public void resize(int cols, int rows) {
    Thread.ofVirtual()
        .start(
            () -> {
              // pixel dimensions set to 0 — only character dimensions matter
              channel.setPtySize(cols, rows, 0, 0);
              log.debug("Terminal resized to {}x{}", cols, rows);
            });
  }
}
