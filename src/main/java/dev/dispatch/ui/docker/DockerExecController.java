package dev.dispatch.ui.docker;

import dev.dispatch.docker.DockerExecSession;
import dev.dispatch.docker.DockerService;
import dev.dispatch.docker.model.ContainerInfo;
import java.io.IOException;
import java.io.InputStream;
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
 * Creates and manages an xterm.js terminal for a Docker exec session inside a {@link WebView}.
 * Equivalent to {@code docker exec -it <container> bash}.
 *
 * <p>Usage (on FX thread):
 *
 * <pre>{@code
 * DockerExecController ctrl = new DockerExecController(dockerService, container);
 * tab.setContent(ctrl.createNode());
 * tab.setOnClosed(e -> ctrl.dispose());
 * }</pre>
 *
 * <p>The exec session is opened asynchronously on a virtual thread after the WebView finishes
 * loading.
 */
public class DockerExecController {

  private static final Logger log = LoggerFactory.getLogger(DockerExecController.class);

  private static final int INITIAL_COLS = 220;
  private static final int INITIAL_ROWS = 50;

  private final DockerService dockerService;
  private final ContainerInfo container;
  private WebView webView;
  private DockerExecBridge bridge;

  public DockerExecController(DockerService dockerService, ContainerInfo container) {
    this.dockerService = dockerService;
    this.container = container;
  }

  /**
   * Creates the JavaFX node containing the exec terminal. Must be called on the FX thread.
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

  /** Closes the exec session and releases resources. Call when the tab is closed. */
  public void dispose() {
    if (bridge != null) bridge.dispose();
    log.info("DockerExecController disposed for {}", container.getName());
  }

  private void installCtrlKeyFilter(WebView view) {
    view.addEventFilter(
        KeyEvent.KEY_PRESSED,
        event -> {
          if (!event.isControlDown() || bridge == null) return;
          if (event.isShiftDown()) {
            if (event.getCode() == KeyCode.V) {
              event.consume();
              String text = Clipboard.getSystemClipboard().getString();
              if (text != null && !text.isEmpty()) bridge.sendInput(text);
            }
            return;
          }
          if (event.getCode() == KeyCode.L) {
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

  private static String ctrlSequence(KeyCode code) {
    return switch (code) {
      case A -> "\u0001";
      case B -> "\u0002";
      case C -> "\u0003";
      case D -> "\u0004";
      case E -> "\u0005";
      case F -> "\u0006";
      case G -> "\u0007";
      case H -> "\u0008";
      case J -> "\n";
      case K -> "\u000B";
      case N -> "\u000E";
      case P -> "\u0010";
      case Q -> "\u0011";
      case R -> "\u0012";
      case S -> "\u0013";
      case T -> "\u0014";
      case U -> "\u0015";
      case V -> "\u0016";
      case W -> "\u0017";
      case X -> "\u0018";
      case Y -> "\u0019";
      case Z -> "\u001A";
      case OPEN_BRACKET -> "\u001B";
      case BACK_SLASH -> "\u001C";
      case CLOSE_BRACKET -> "\u001D";
      default -> null;
    };
  }

  private void loadTerminalPage(WebEngine engine) {
    engine.setOnError(event -> log.error("WebEngine error: {}", event.getMessage()));
    engine
        .getLoadWorker()
        .exceptionProperty()
        .addListener(
            (obs, old, ex) -> {
              if (ex != null) log.error("WebEngine load exception: {}", ex.getMessage(), ex);
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
                  log.debug("Exec terminal HTML loaded for {}", container.getName());
                  openExecAsync(engine);
                } else if (state == javafx.concurrent.Worker.State.FAILED) {
                  log.error("Exec terminal HTML failed to load for {}", container.getName());
                }
              });
    } catch (IOException e) {
      log.error(
          "Failed to build exec terminal HTML for {}: {}", container.getName(), e.getMessage(), e);
    }
  }

  private void openExecAsync(WebEngine engine) {
    Thread.ofVirtual()
        .name("docker-exec-open-" + container.getShortId())
        .start(
            () -> {
              try {
                DockerExecSession session = dockerService.openExecSession(container.getId());
                bridge = new DockerExecBridge(engine, session);
                bridge.start();
                log.info("Exec terminal ready for {}", container.getName());
              } catch (Exception e) {
                log.error("Failed to open exec for {}: {}", container.getName(), e.getMessage(), e);
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
      if (in == null) throw new IOException("Resource not found: " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
