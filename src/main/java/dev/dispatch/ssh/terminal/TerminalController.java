package dev.dispatch.ssh.terminal;

import dev.dispatch.ssh.SshException;
import dev.dispatch.ssh.SshSession;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import org.apache.sshd.client.channel.ChannelShell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and manages an xterm.js terminal embedded in a {@link WebView} for one SSH session.
 *
 * <p>Usage (on FX thread):
 *
 * <pre>{@code
 * TerminalController tc = new TerminalController(session);
 * tab.setContent(tc.createNode());
 * tab.setOnClosed(e -> tc.dispose());
 * }</pre>
 *
 * <p>The SSH shell is opened asynchronously on a virtual thread after the WebView finishes loading.
 */
public class TerminalController {

  private static final Logger log = LoggerFactory.getLogger(TerminalController.class);

  private static final int INITIAL_COLS = 220;
  private static final int INITIAL_ROWS = 50;

  private final SshSession session;
  private WebView webView;
  private SshTerminalBridge bridge;

  public TerminalController(SshSession session) {
    this.session = session;
  }

  /**
   * Creates the JavaFX node containing the terminal. Must be called on the FX thread.
   *
   * @return a {@link WebView} node ready to be placed in a tab
   */
  public Node createNode() {
    webView = new WebView();
    webView.setContextMenuEnabled(false);
    loadTerminalPage(webView.getEngine());
    return webView;
  }

  /** Closes the shell channel and releases resources. Call when the tab is closed. */
  public void dispose() {
    if (bridge != null) {
      bridge.dispose();
    }
    log.info("TerminalController disposed for {}", session.getHost().getName());
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void loadTerminalPage(WebEngine engine) {
    // Log any JavaScript errors so they appear in dispatch.log
    engine.setOnError(event -> log.error("WebEngine error: {}", event.getMessage()));
    engine
        .getLoadWorker()
        .exceptionProperty()
        .addListener(
            (obs, old, ex) -> {
              if (ex != null) {
                log.error("WebEngine load exception: {}", ex.getMessage(), ex);
              }
            });

    try {
      String html = buildTerminalHtml();
      engine.loadContent(html);
      engine
          .getLoadWorker()
          .stateProperty()
          .addListener(
              (obs, old, state) -> {
                if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                  log.debug("Terminal HTML loaded for {}", session.getHost().getName());
                  openShellAsync(engine);
                } else if (state == javafx.concurrent.Worker.State.FAILED) {
                  log.error(
                      "Terminal HTML failed to load for {}", session.getHost().getName());
                }
              });
    } catch (IOException e) {
      log.error("Failed to build terminal HTML for {}: {}", session.getHost().getName(), e.getMessage(), e);
    }
  }

  private void openShellAsync(WebEngine engine) {
    Thread.ofVirtual()
        .name("ssh-shell-open")
        .start(
            () -> {
              try {
                ChannelShell channel = session.openShell(INITIAL_COLS, INITIAL_ROWS);
                bridge = new SshTerminalBridge(engine, channel);
                bridge.start();
                log.info("Terminal ready for {}", session.getHost().getName());
              } catch (Exception e) {
                // Catch all — uncaught exceptions on virtual threads are silently swallowed
                log.error(
                    "Failed to open shell on {}: {}", session.getHost().getName(), e.getMessage(), e);
                Platform.runLater(
                    () ->
                        engine.executeScript(
                            "term.writeln('\\r\\n\\x1b[31mError: "
                                + e.getMessage().replace("'", "\\'")
                                + "\\x1b[0m')"));
              }
            });
  }

  private String buildTerminalHtml() throws IOException {
    String template = readResource("/terminal/terminal.html");
    String xtermJs = readResource("/terminal/xterm.js");
    String xtermCss = readResource("/terminal/xterm.css");
    return template
        .replace("{{XTERM_JS}}", xtermJs)
        .replace("{{XTERM_CSS}}", xtermCss);
  }

  private String readResource(String path) throws IOException {
    try (InputStream in = getClass().getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("Resource not found: " + path
            + " — run ./gradlew downloadXterm first");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
