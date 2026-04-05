package dev.dispatch.ui.docker;

import dev.dispatch.docker.DockerExecSession;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import javafx.application.Platform;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives calls from JavaScript (xterm.js) for a Docker exec session and forwards them to the
 * container process.
 *
 * <p>Mirrors {@link dev.dispatch.ssh.terminal.JavaTerminalHandler} but routes resize through the
 * Docker Exec resize API ({@link DockerExecSession#resize}) instead of a JSch PTY channel.
 *
 * <p>Must be registered on the JS window as {@code javaTerminal} and kept alive via a strong
 * reference in the bridge to prevent garbage collection.
 */
public class DockerExecTerminalHandler {

  private static final Logger log = LoggerFactory.getLogger(DockerExecTerminalHandler.class);

  private final DockerExecSession execSession;
  private final OutputStream stdin;

  DockerExecTerminalHandler(DockerExecSession execSession) {
    this.execSession = execSession;
    this.stdin = execSession.getStdinWriter();
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
                log.warn("Failed to send input to exec session: {}", e.getMessage());
              }
            });
  }

  /**
   * Called by xterm.js when the terminal selection changes. Writes the selected text to the system
   * clipboard.
   *
   * <p>{@code navigator.clipboard} is unavailable in WebView (no secure context), so clipboard
   * access is delegated to Java.
   */
  public void copyToClipboard(String text) {
    if (text == null || text.isEmpty()) return;
    Platform.runLater(
        () -> {
          ClipboardContent content = new ClipboardContent();
          content.putString(text);
          Clipboard.getSystemClipboard().setContent(content);
        });
  }

  /** Called by xterm.js when the terminal is resized. Sends resize to Docker exec PTY. */
  public void resize(int cols, int rows) {
    execSession.resize(cols, rows);
  }
}
