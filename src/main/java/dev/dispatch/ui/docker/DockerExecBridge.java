package dev.dispatch.ui.docker;

import dev.dispatch.docker.DockerExecSession;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges a {@link DockerExecSession} to an xterm.js terminal running inside a {@link WebEngine}.
 *
 * <ul>
 *   <li>Exec stdout → base64-encoded → {@code term.write()} via {@code WebEngine.executeScript()}
 *   <li>JS input events → {@link DockerExecTerminalHandler#sendInput(String)} → exec stdin
 * </ul>
 *
 * <p>Mirrors the pattern of {@link dev.dispatch.ssh.terminal.SshTerminalBridge}.
 */
class DockerExecBridge {

  private static final Logger log = LoggerFactory.getLogger(DockerExecBridge.class);

  private final WebEngine engine;
  private final DockerExecSession execSession;
  private volatile boolean running = true;
  // Strong reference — JSObject.setMember() holds only a weak ref, so without this field the GC
  // would collect the handler and break JS→Java calls.
  private DockerExecTerminalHandler terminalHandler;

  DockerExecBridge(WebEngine engine, DockerExecSession execSession) {
    this.engine = engine;
    this.execSession = execSession;
  }

  /**
   * Registers the Java handler on the JS window and starts the exec output pump. Must be called
   * after the WebEngine has finished loading. May be called from any thread.
   */
  void start() {
    Platform.runLater(this::registerJsHandler);
    Thread.ofVirtual().name("docker-exec-output-pump").start(this::pumpExecOutput);
  }

  /** Sends raw input data to the exec session stdin. Safe to call from any thread. */
  void sendInput(String data) {
    if (terminalHandler != null) terminalHandler.sendInput(data);
  }

  /** Stops reading and closes the exec session. */
  void dispose() {
    running = false;
    execSession.close();
    log.info("DockerExecBridge disposed");
  }

  private void registerJsHandler() {
    Platform.runLater(
        () -> {
          terminalHandler = new DockerExecTerminalHandler(execSession);
          JSObject window = (JSObject) engine.executeScript("window");
          window.setMember("javaTerminal", terminalHandler);
          // Sync PTY size — the ResizeObserver fired before javaTerminal was available.
          engine.executeScript("notifyResize()");
          log.debug("DockerExecTerminalHandler registered on JS window");
        });
  }

  private void pumpExecOutput() {
    InputStream stdout = execSession.getStdoutReader();
    byte[] buf = new byte[4096];
    try {
      int read;
      while (running && (read = stdout.read(buf)) != -1) {
        String b64 = Base64.getEncoder().encodeToString(Arrays.copyOf(buf, read));
        Platform.runLater(
            () ->
                engine.executeScript(
                    "term.write(Uint8Array.from(atob('" + b64 + "'), c => c.charCodeAt(0)))"));
      }
    } catch (IOException e) {
      if (running) {
        log.warn("Exec output stream ended: {}", e.getMessage());
        Platform.runLater(() -> engine.executeScript("term.writeln('\\r\\n[Session closed]')"));
      }
    }
    log.info("Docker exec output pump stopped");
  }
}
