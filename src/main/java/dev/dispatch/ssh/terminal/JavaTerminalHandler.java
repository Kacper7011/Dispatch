package dev.dispatch.ssh.terminal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.sshd.client.channel.ChannelShell;
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

  public JavaTerminalHandler(ChannelShell channel) {
    this.channel = channel;
  }

  /** Called by xterm.js for every keystroke and paste event. */
  public void sendInput(String data) {
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                // getInvertedIn() = OutputStream → data flows client → server (stdin)
                OutputStream out = channel.getInvertedIn();
                out.write(data.getBytes(StandardCharsets.UTF_8));
                out.flush();
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
              try {
                // pixel dimensions set to 0 — only character dimensions matter
                channel.sendWindowChange(cols, rows, 0, 0);
                log.debug("Terminal resized to {}x{}", cols, rows);
              } catch (IOException e) {
                log.warn("Failed to send window-change to SSH: {}", e.getMessage());
              }
            });
  }
}
