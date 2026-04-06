package dev.dispatch.ui.ssh;

import dev.dispatch.core.model.Session;
import dev.dispatch.ssh.SshCredentials;
import dev.dispatch.ssh.SshException;
import dev.dispatch.ssh.SshService;
import dev.dispatch.ssh.SshSession;
import dev.dispatch.ssh.terminal.TerminalController;
import dev.dispatch.storage.SessionRepository;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the UI and lifecycle of a single SSH session tab.
 *
 * <p>Builds the tab content (terminal + reconnect overlay), records connection history via {@link
 * SessionRepository}, and handles the LOST → reconnect flow. Create one instance per SSH tab;
 * dispose it when the tab is closed.
 */
public class SshTabController {

  private static final Logger log = LoggerFactory.getLogger(SshTabController.class);

  private final SshService sshService;
  private final SessionRepository sessionRepo;
  // Retained so the same credentials can be reused for reconnect attempts
  private final SshCredentials credentials;

  private SshSession session;
  private TerminalController terminalController;
  private StackPane contentPane;
  private VBox reconnectOverlay;
  private Label overlayMessage;
  private Session dbSession;

  private Consumer<SshSession> onReconnected;

  /**
   * Creates a controller for a newly connected session.
   *
   * @param session the active SSH session
   * @param credentials credentials used — retained for reconnect
   * @param sshService SSH service that manages session lifecycle
   * @param sessionRepo repository for recording connection history
   */
  public SshTabController(
      SshSession session,
      SshCredentials credentials,
      SshService sshService,
      SessionRepository sessionRepo) {
    this.session = session;
    this.credentials = credentials;
    this.sshService = sshService;
    this.sessionRepo = sessionRepo;
  }

  /**
   * Callback invoked on the FX thread when a reconnect succeeds. The new {@link SshSession} is
   * passed so the caller can re-attach the Docker panel for this tab.
   */
  public void setOnReconnected(Consumer<SshSession> onReconnected) {
    this.onReconnected = onReconnected;
  }

  /**
   * Builds and returns the StackPane that serves as the tab's content node. Opens a session record
   * in the database. Must be called on the FX thread.
   */
  public Node createNode() {
    dbSession = sessionRepo.openSession(session.getHost().getId());

    terminalController = new TerminalController(session);
    Node terminalNode = terminalController.createNode();

    reconnectOverlay = buildReconnectOverlay();
    reconnectOverlay.setVisible(false);

    contentPane = new StackPane(terminalNode, reconnectOverlay);
    return contentPane;
  }

  /**
   * Switches the tab to the LOST state: shows the reconnect overlay and closes the in-progress
   * session record. Safe to call from any thread.
   */
  public void onSessionLost() {
    Platform.runLater(
        () -> {
          closeDbSession();
          overlayMessage.setText("Connection lost");
          reconnectOverlay.setVisible(true);
          log.warn("Session lost for {}", session.getHost().getName());
        });
  }

  /**
   * Disposes the terminal and closes any open session record. Must be called when the tab is
   * closed.
   */
  public void dispose() {
    closeDbSession();
    if (terminalController != null) {
      terminalController.dispose();
    }
    log.info("SshTabController disposed for {}", session.getHost().getName());
  }

  // ── Private helpers ──────────────────────────────────────────────────────────

  private VBox buildReconnectOverlay() {
    Label icon = new Label("⚠");
    icon.getStyleClass().add("reconnect-icon");

    overlayMessage = new Label("Connection lost");
    overlayMessage.getStyleClass().add("reconnect-message");

    Button btn = new Button("Reconnect");
    btn.getStyleClass().add("reconnect-btn");
    btn.setOnAction(e -> onReconnectRequested());

    VBox box = new VBox(12, icon, overlayMessage, btn);
    box.setAlignment(Pos.CENTER);
    box.getStyleClass().add("reconnect-overlay");
    // Absorb all input so the terminal below is unreachable while disconnected
    box.setOnMouseClicked(Event::consume);
    box.setOnKeyPressed(Event::consume);
    box.setPickOnBounds(true);
    return box;
  }

  private void onReconnectRequested() {
    overlayMessage.setText("Connecting…");

    Thread.ofVirtual()
        .name("ssh-reconnect-" + session.getHost().getName())
        .start(
            () -> {
              try {
                SshSession newSession = sshService.connect(session.getHost(), credentials);
                newSession.setOnLost(lost -> onSessionLost());
                session = newSession;
                Platform.runLater(
                    () -> {
                      dbSession = sessionRepo.openSession(session.getHost().getId());
                      replaceTerminal(newSession);
                      reconnectOverlay.setVisible(false);
                      if (onReconnected != null) {
                        onReconnected.accept(newSession);
                      }
                      log.info("Reconnected to {}", newSession.getHost().getName());
                    });
              } catch (SshException e) {
                log.error(
                    "Reconnect failed to {}: {}", session.getHost().getName(), e.getMessage(), e);
                Platform.runLater(() -> overlayMessage.setText("Failed: " + e.getMessage()));
              }
            });
  }

  private void replaceTerminal(SshSession newSession) {
    terminalController.dispose();
    terminalController = new TerminalController(newSession);
    // Index 0 is always the terminal node; index 1 is the overlay — replace terminal in-place
    contentPane.getChildren().set(0, terminalController.createNode());
  }

  private void closeDbSession() {
    if (dbSession != null && !dbSession.isClosed()) {
      sessionRepo.closeSession(dbSession.getId());
      dbSession.setDisconnectedAt(LocalDateTime.now());
    }
  }
}
