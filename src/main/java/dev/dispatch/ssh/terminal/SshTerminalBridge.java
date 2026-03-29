package dev.dispatch.ssh.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import netscape.javascript.JSObject;
import org.apache.sshd.client.channel.ChannelShell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges an open SSH {@link ChannelShell} to an xterm.js terminal running inside a
 * {@link WebEngine}.
 *
 * <ul>
 *   <li>SSH stdout → base64-encoded → {@code term.write()} via {@code WebEngine.executeScript()}
 *   <li>JS input events → {@link JavaTerminalHandler#sendInput(String)} → SSH stdin
 * </ul>
 *
 * <p>The bridge starts reading SSH output as soon as {@link #start()} is called. All
 * {@code executeScript} calls are dispatched to the FX thread via {@link Platform#runLater}.
 */
public class SshTerminalBridge {

  private static final Logger log = LoggerFactory.getLogger(SshTerminalBridge.class);

  private final WebEngine engine;
  private final ChannelShell channel;
  private volatile boolean running = true;
  // Strong reference required — JSObject.setMember() holds only a weak ref,
  // so without this field the GC would collect the handler and break JS→Java calls.
  private JavaTerminalHandler terminalHandler;

  public SshTerminalBridge(WebEngine engine, ChannelShell channel) {
    this.engine = engine;
    this.channel = channel;
  }

  /**
   * Registers the Java handler on the JS window and starts the SSH-to-terminal output pump.
   * Must be called after the WebEngine has finished loading (state == SUCCEEDED).
   * May be called from any thread.
   */
  public void start() {
    // Page is guaranteed loaded here — we're called from the SUCCEEDED listener.
    // Always dispatch to FX thread; never access WebEngine from a non-FX thread.
    Platform.runLater(this::registerJsHandler);
    Thread.ofVirtual().name("ssh-output-pump").start(this::pumpSshOutput);
  }

  /** Stops reading and closes the shell channel. */
  public void dispose() {
    running = false;
    try {
      channel.close(false).await(3, TimeUnit.SECONDS);
      log.info("Shell channel closed");
    } catch (IOException e) {
      log.warn("Error closing shell channel: {}", e.getMessage());
    }
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void registerJsHandler() {
    Platform.runLater(
        () -> {
          terminalHandler = new JavaTerminalHandler(channel);
          JSObject window = (JSObject) engine.executeScript("window");
          window.setMember("javaTerminal", terminalHandler);
          log.debug("JavaTerminalHandler registered on JS window");
        });
  }

  private void pumpSshOutput() {
    byte[] buf = new byte[4096];
    // getInvertedOut() = InputStream ← data flows server → client (stdout)
    try (InputStream in = channel.getInvertedOut()) {
      int read;
      while (running && (read = in.read(buf)) != -1) {
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
        Platform.runLater(
            () -> engine.executeScript("term.writeln('\\r\\n[Connection closed]')"));
      }
    }
    log.info("SSH output pump stopped");
  }
}
