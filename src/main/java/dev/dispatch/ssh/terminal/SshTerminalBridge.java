package dev.dispatch.ssh.terminal;

import com.jcraft.jsch.ChannelShell;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges an open SSH {@link ChannelShell} to an xterm.js terminal running inside a {@link
 * WebEngine}.
 *
 * <ul>
 *   <li>SSH stdout → base64-encoded → {@code term.write()} via {@code WebEngine.executeScript()}
 *   <li>JS input events → {@link JavaTerminalHandler#sendInput(String)} → SSH stdin
 * </ul>
 *
 * <p>The bridge starts reading SSH output as soon as {@link #start()} is called. All {@code
 * executeScript} calls are dispatched to the FX thread via {@link Platform#runLater}.
 */
public class SshTerminalBridge {

  private static final Logger log = LoggerFactory.getLogger(SshTerminalBridge.class);

  private final WebEngine engine;
  private final ChannelShell channel;
  private final InputStream stdout;
  private final OutputStream stdin;
  private volatile boolean running = true;
  // Strong reference required — JSObject.setMember() holds only a weak ref,
  // so without this field the GC would collect the handler and break JS→Java calls.
  private JavaTerminalHandler terminalHandler;

  public SshTerminalBridge(
      WebEngine engine, ChannelShell channel, InputStream stdout, OutputStream stdin) {
    this.engine = engine;
    this.channel = channel;
    this.stdout = stdout;
    this.stdin = stdin;
  }

  /**
   * Registers the Java handler on the JS window and starts the SSH-to-terminal output pump. Must be
   * called after the WebEngine has finished loading (state == SUCCEEDED). May be called from any
   * thread.
   */
  public void start() {
    Platform.runLater(this::registerJsHandler);
    Thread.ofVirtual().name("ssh-output-pump").start(this::pumpSshOutput);
  }

  /** Sends raw input data to the SSH session stdin. Safe to call from any thread. */
  public void sendInput(String data) {
    if (terminalHandler != null) {
      terminalHandler.sendInput(data);
    }
  }

  /** Stops reading and closes the shell channel. */
  public void dispose() {
    running = false;
    channel.disconnect();
    log.info("Shell channel closed");
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void registerJsHandler() {
    Platform.runLater(
        () -> {
          terminalHandler = new JavaTerminalHandler(channel, stdin);
          JSObject window = (JSObject) engine.executeScript("window");
          window.setMember("javaTerminal", terminalHandler);
          // Sync PTY size immediately — the ResizeObserver fired before javaTerminal
          // was available, so the initial resize notification was silently dropped.
          engine.executeScript("notifyResize()");
          log.debug("JavaTerminalHandler registered on JS window");
        });
  }

  private void pumpSshOutput() {
    byte[] buf = new byte[4096];
    try {
      int read;
      while (running && (read = stdout.read(buf)) != -1) {
        // Base64-encode to avoid any JS string escaping issues with binary data
        String b64 = Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(buf, read));
        Platform.runLater(
            () ->
                engine.executeScript(
                    "term.write(Uint8Array.from(atob('" + b64 + "'), c => c.charCodeAt(0)))"));
      }
    } catch (IOException e) {
      if (running) {
        log.warn("SSH output stream ended: {}", e.getMessage());
        Platform.runLater(() -> engine.executeScript("term.writeln('\\r\\n[Connection closed]')"));
      }
    }
    log.info("SSH output pump stopped");
  }
}
