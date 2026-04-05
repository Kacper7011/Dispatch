package dev.dispatch.ssh.terminal;

import com.jcraft.jsch.ChannelShell;
import dev.dispatch.ssh.SshSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
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
    installCtrlKeyFilter(webView);
    loadTerminalPage(webView.getEngine());
    return webView;
  }

  /**
   * Intercepts Ctrl+key combinations at the JavaFX level before WebView can absorb them (e.g.
   * Ctrl+L = "focus address bar" in browser context) and forwards the correct VT100 control
   * sequence directly to the SSH channel.
   */
  private void installCtrlKeyFilter(WebView view) {
    view.addEventFilter(
        KeyEvent.KEY_PRESSED,
        event -> {
          if (!event.isControlDown() || bridge == null) return;

          // Ctrl+Shift combinations are not VT100 sequences — handle paste and skip the rest
          // so they don't get mapped to raw control characters.
          if (event.isShiftDown()) {
            if (event.getCode() == KeyCode.V) {
              event.consume();
              String text = Clipboard.getSystemClipboard().getString();
              if (text != null && !text.isEmpty()) {
                bridge.sendInput(text);
              }
            }
            return;
          }

          if (event.getCode() == KeyCode.L) {
            // Erase full screen + cursor-home directly in xterm.js (fully blank, no kept line),
            // then send \x0c so the shell redraws exactly one prompt at the top.
            // Using term.clear() instead keeps the current line, causing a duplicate prompt.
            event.consume();
            view.getEngine().executeScript("term.write('\\x1b[H\\x1b[2J')");
            bridge.sendInput("\u000C");
            return;
          }

          String seq = ctrlSequence(event.getCode());
          if (seq != null) {
            event.consume();
            bridge.sendInput(seq);
          }
        });
  }

  /** Maps Ctrl+A … Ctrl+Z to their VT100 control-character sequences. */
  private static String ctrlSequence(KeyCode code) {
    return switch (code) {
      case A -> "\u0001"; // SOH  — beginning of line (readline)
      case B -> "\u0002"; // STX  — back one char (readline)
      case C -> "\u0003"; // ETX  — SIGINT
      case D -> "\u0004"; // EOT  — EOF / logout
      case E -> "\u0005"; // ENQ  — end of line (readline)
      case F -> "\u0006"; // ACK  — forward one char (readline)
      case G -> "\u0007"; // BEL  — cancel / bell
      case H -> "\u0008"; // BS   — backspace
      case J -> "\n"; // LF   — newline
      case K -> "\u000B"; // VT   — kill to end of line (readline)
      case N -> "\u000E"; // SO   — next history entry (readline)
      case P -> "\u0010"; // DLE  — previous history entry (readline)
      case Q -> "\u0011"; // DC1  — XON (resume output)
      case R -> "\u0012"; // DC2  — reverse history search (readline)
      case S -> "\u0013"; // DC3  — XOFF (pause output)
      case T -> "\u0014"; // DC4  — transpose chars (readline)
      case U -> "\u0015"; // NAK  — kill whole line (readline)
      case V -> "\u0016"; // SYN  — literal next char
      case W -> "\u0017"; // ETB  — kill previous word (readline)
      case X -> "\u0018"; // CAN  — cancel
      case Y -> "\u0019"; // EM   — yank (readline)
      case Z -> "\u001A"; // SUB  — SIGTSTP (suspend)
      case OPEN_BRACKET -> "\u001B"; // ESC
      case BACK_SLASH -> "\u001C"; // FS — SIGQUIT
      case CLOSE_BRACKET -> "\u001D"; // GS
      default -> null;
    };
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
                  log.error("Terminal HTML failed to load for {}", session.getHost().getName());
                }
              });
    } catch (IOException e) {
      log.error(
          "Failed to build terminal HTML for {}: {}",
          session.getHost().getName(),
          e.getMessage(),
          e);
    }
  }

  private void openShellAsync(WebEngine engine) {
    Thread.ofVirtual()
        .name("ssh-shell-open")
        .start(
            () -> {
              try {
                Object[] shell = session.openShell(INITIAL_COLS, INITIAL_ROWS);
                ChannelShell channel = (ChannelShell) shell[0];
                InputStream stdout = (InputStream) shell[1];
                OutputStream stdin = (OutputStream) shell[2];
                bridge = new SshTerminalBridge(engine, channel, stdout, stdin);
                bridge.start();
                log.info("Terminal ready for {}", session.getHost().getName());
              } catch (Exception e) {
                // Catch all — uncaught exceptions on virtual threads are silently swallowed
                log.error(
                    "Failed to open shell on {}: {}",
                    session.getHost().getName(),
                    e.getMessage(),
                    e);
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
    return template.replace("{{XTERM_JS}}", xtermJs).replace("{{XTERM_CSS}}", xtermCss);
  }

  private String readResource(String path) throws IOException {
    try (InputStream in = getClass().getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException(
            "Resource not found: " + path + " — run ./gradlew downloadXterm first");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
