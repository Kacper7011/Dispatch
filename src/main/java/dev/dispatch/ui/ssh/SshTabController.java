package dev.dispatch.ui.ssh;

import dev.dispatch.core.model.Host;
import dev.dispatch.core.model.Session;
import dev.dispatch.ssh.SshCredentials;
import dev.dispatch.ssh.SshException;
import dev.dispatch.ssh.SshService;
import dev.dispatch.ssh.SshSession;
import dev.dispatch.storage.HostRepository;
import dev.dispatch.storage.SessionRepository;
import dev.dispatch.ui.CredentialDialog;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the UI and lifecycle of a single SSH session tab.
 *
 * <p>Panes are arranged in a tmux-style binary tree managed by {@link PaneLayoutManager}. Each leaf
 * is a {@link TerminalNode} — either a live {@link TerminalPane} or a pending {@link
 * PendingTerminalPane}. Internal nodes are {@link SplitNode}s. The tree is fully recursive, so any
 * pane can be split independently in any direction at any depth.
 */
public class SshTabController {

  private static final Logger log = LoggerFactory.getLogger(SshTabController.class);

  private final SshService sshService;
  private final SessionRepository sessionRepo;
  private final HostRepository hostRepository;
  private final SshCredentials primaryCredentials;

  private SshSession primarySession;
  private TerminalNode primaryLeaf;
  private PaneLayoutManager layoutManager;
  private StackPane contentPane;
  private VBox reconnectOverlay;
  private Label overlayMessage;
  private Session dbSession;

  private Consumer<SshSession> onReconnected;
  private Consumer<SshSession> onSlotConnected;

  /**
   * Creates a controller for a newly connected session.
   *
   * @param primarySession the active SSH session for the first pane
   * @param primaryCredentials credentials retained for reconnect of the primary session
   * @param sshService SSH service managing session lifecycle
   * @param sessionRepo repository for recording connection history
   * @param hostRepository repository used to populate the host picker in new split panes
   */
  public SshTabController(
      SshSession primarySession,
      SshCredentials primaryCredentials,
      SshService sshService,
      SessionRepository sessionRepo,
      HostRepository hostRepository) {
    this.primarySession = primarySession;
    this.primaryCredentials = primaryCredentials;
    this.sshService = sshService;
    this.sessionRepo = sessionRepo;
    this.hostRepository = hostRepository;
  }

  /**
   * Callback invoked on the FX thread when the primary session reconnects. The new {@link
   * SshSession} is passed so the caller can re-attach the Docker panel.
   */
  public void setOnReconnected(Consumer<SshSession> onReconnected) {
    this.onReconnected = onReconnected;
  }

  /**
   * Registers a callback invoked on the FX thread whenever a secondary split-pane slot connects to
   * a new host. Used by {@code MainController} to trigger Docker detection for that session.
   */
  public void setOnSlotConnected(Consumer<SshSession> onSlotConnected) {
    this.onSlotConnected = onSlotConnected;
  }

  /**
   * Builds and returns the StackPane that serves as the tab's content node. Opens a session record
   * in the database. Must be called on the FX thread.
   */
  public Node createNode() {
    dbSession = sessionRepo.openSession(primarySession.getHost().getId());

    primaryLeaf = createConnectedLeaf(primarySession);
    layoutManager = new PaneLayoutManager(primaryLeaf);

    VBox layout = new VBox(layoutManager.getRootView());
    VBox.setVgrow(layoutManager.getRootView(), Priority.ALWAYS);
    layout.getStyleClass().add("ssh-tab-layout");

    reconnectOverlay = buildReconnectOverlay();
    reconnectOverlay.setVisible(false);

    contentPane = new StackPane(layout, reconnectOverlay);
    return contentPane;
  }

  /**
   * Switches the tab to the LOST state: shows the reconnect overlay and closes the session record.
   * Safe to call from any thread.
   */
  public void onSessionLost() {
    Platform.runLater(
        () -> {
          closeDbSession();
          overlayMessage.setText("Connection lost");
          reconnectOverlay.setVisible(true);
          log.warn("Primary session lost for {}", primarySession.getHost().getName());
        });
  }

  /**
   * Disposes all terminal panes and closes any open session record. Must be called when the tab is
   * closed.
   */
  public void dispose() {
    closeDbSession();
    layoutManager
        .collectLeaves()
        .forEach(
            leaf -> {
              if (leaf.getContent() != null) leaf.getContent().dispose();
            });
    log.info("SshTabController disposed for {}", primarySession.getHost().getName());
  }

  // ── Leaf factory helpers ──────────────────────────────────────────────────

  /**
   * Creates a {@link TerminalNode} backed by a live {@link TerminalPane} and wires up its split and
   * close handlers.
   */
  private TerminalNode createConnectedLeaf(SshSession session) {
    TerminalNode leaf = new TerminalNode();

    TerminalPane pane =
        new TerminalPane(
            session, () -> removeLeaf(leaf), orientation -> splitLeaf(leaf, orientation));

    leaf.setContent(pane);
    leaf.setSplitHandler((l, orientation) -> splitLeaf(l, orientation));
    leaf.setCloseHandler(() -> removeLeaf(leaf));
    return leaf;
  }

  /**
   * Creates a {@link TerminalNode} in the pending state with a host-picker {@link
   * PendingTerminalPane} as its content.
   */
  private TerminalNode createPendingLeaf() {
    TerminalNode leaf = new TerminalNode();

    BorderPane pickerContent = buildHostPickerPane(leaf);
    PendingTerminalPane pending =
        new PendingTerminalPane(
            pickerContent, () -> removeLeaf(leaf), orientation -> splitLeaf(leaf, orientation));

    leaf.setPendingContent(pending);
    leaf.setSplitHandler((l, orientation) -> splitLeaf(l, orientation));
    leaf.setCloseHandler(() -> removeLeaf(leaf));
    return leaf;
  }

  // ── Layout tree operations ────────────────────────────────────────────────

  /** Splits {@code leaf} by inserting a new pending pane next to it in {@code orientation}. */
  private void splitLeaf(TerminalNode leaf, Orientation orientation) {
    TerminalNode newLeaf = createPendingLeaf();
    layoutManager.splitLeaf(leaf, orientation, newLeaf);
    log.debug("Split {} — totalLeaves={}", orientation, layoutManager.collectLeaves().size());
  }

  /** Removes {@code leaf} from the tree and disposes its content. */
  private void removeLeaf(TerminalNode leaf) {
    if (leaf.getContent() != null) leaf.getContent().dispose();
    layoutManager.removeLeaf(leaf);
    log.debug("Leaf removed — totalLeaves={}", layoutManager.collectLeaves().size());
  }

  /**
   * Activates a pending leaf by replacing its {@link PendingTerminalPane} with a live {@link
   * TerminalPane}.
   */
  private void activateLeaf(TerminalNode leaf, SshSession session) {
    TerminalPane pane =
        new TerminalPane(
            session, () -> removeLeaf(leaf), orientation -> splitLeaf(leaf, orientation));

    leaf.setContent(pane);
    leaf.setSplitHandler((l, orientation) -> splitLeaf(l, orientation));
    leaf.setCloseHandler(() -> removeLeaf(leaf));

    layoutManager.replaceLeaf(leaf, leaf);
    log.info("Leaf activated — connected to {}", session.getHost().getName());
  }

  // ── Host picker UI ────────────────────────────────────────────────────────

  /**
   * Builds the host-picker card for a pending leaf. On successful connection the leaf is activated
   * in place.
   */
  private BorderPane buildHostPickerPane(TerminalNode leaf) {
    List<Host> hosts = hostRepository.findAll();

    ComboBox<Host> hostCombo = new ComboBox<>();
    hostCombo.getItems().addAll(hosts);
    hostCombo.setPromptText("Select host…");
    hostCombo.setCellFactory(
        lv ->
            new ListCell<>() {
              @Override
              protected void updateItem(Host h, boolean empty) {
                super.updateItem(h, empty);
                setText(empty || h == null ? null : h.getName() + " (" + h.getHostname() + ")");
              }
            });
    hostCombo.setButtonCell(hostCombo.getCellFactory().call(null));

    Label statusLabel = new Label();
    statusLabel.getStyleClass().add("reconnect-message");

    Button connectBtn = new Button("Connect");
    connectBtn.getStyleClass().add("picker-connect-btn");
    connectBtn.setOnAction(e -> onPickerConnectClicked(leaf, hostCombo, connectBtn, statusLabel));

    Label title = new Label("Connect to host");
    title.getStyleClass().add("host-picker-title");

    hostCombo.setPrefWidth(160);
    connectBtn.setPrefWidth(160);

    VBox card = new VBox(12, title, hostCombo, connectBtn, statusLabel);
    card.getStyleClass().add("host-picker-card");
    card.setAlignment(Pos.CENTER);

    Region topSpacer = new Region();
    Region bottomSpacer = new Region();
    VBox.setVgrow(topSpacer, Priority.ALWAYS);
    VBox.setVgrow(bottomSpacer, Priority.ALWAYS);

    VBox center = new VBox(topSpacer, card, bottomSpacer);
    center.setAlignment(Pos.CENTER);
    center.setFillWidth(false);

    BorderPane pane = new BorderPane(center);
    pane.getStyleClass().add("host-picker");
    return pane;
  }

  private void onPickerConnectClicked(
      TerminalNode leaf, ComboBox<Host> hostCombo, Button connectBtn, Label statusLabel) {
    Host host = hostCombo.getValue();
    if (host == null) {
      statusLabel.setText("Please select a host first.");
      return;
    }
    if (host.getAuthType() == dev.dispatch.core.model.AuthType.KEY && host.isKeyNoPassphrase()) {
      connectLeafAsync(leaf, host, SshCredentials.keyNoPassphrase(), connectBtn, statusLabel);
      return;
    }
    CredentialDialog.prompt(host)
        .ifPresentOrElse(
            credentials -> connectLeafAsync(leaf, host, credentials, connectBtn, statusLabel),
            () -> statusLabel.setText("Cancelled."));
  }

  private void connectLeafAsync(
      TerminalNode leaf,
      Host host,
      SshCredentials credentials,
      Button connectBtn,
      Label statusLabel) {
    connectBtn.setDisable(true);
    statusLabel.setText("Connecting…");

    Thread.ofVirtual()
        .name("ssh-split-connect-" + host.getName())
        .start(
            () -> {
              try {
                SshSession newSession = sshService.connect(host, credentials);
                if (!credentials.hasPassphrase()
                    && credentials.getPassword() == null
                    && !host.isKeyNoPassphrase()) {
                  hostRepository.markKeyNoPassphrase(host.getId());
                }
                Platform.runLater(
                    () -> {
                      activateLeaf(leaf, newSession);
                      if (onSlotConnected != null) onSlotConnected.accept(newSession);
                    });
                log.info("Split pane leaf connected to {}", host.getName());
              } catch (SshException e) {
                log.error("Split connect failed to {}: {}", host.getName(), e.getMessage(), e);
                Platform.runLater(
                    () -> {
                      statusLabel.setText("Failed: " + e.getMessage());
                      connectBtn.setDisable(false);
                    });
              }
            });
  }

  // ── Reconnect overlay (primary session) ──────────────────────────────────

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
    box.setOnMouseClicked(Event::consume);
    box.setOnKeyPressed(Event::consume);
    box.setPickOnBounds(true);
    return box;
  }

  private void onReconnectRequested() {
    overlayMessage.setText("Connecting…");

    Thread.ofVirtual()
        .name("ssh-reconnect-" + primarySession.getHost().getName())
        .start(
            () -> {
              try {
                SshSession newSession =
                    sshService.connect(primarySession.getHost(), primaryCredentials);
                newSession.setOnLost(lost -> onSessionLost());
                primarySession = newSession;
                Platform.runLater(
                    () -> {
                      dbSession = sessionRepo.openSession(primarySession.getHost().getId());
                      replacePrimaryLeaf(newSession);
                      reconnectOverlay.setVisible(false);
                      if (onReconnected != null) onReconnected.accept(newSession);
                      log.info("Primary session reconnected to {}", newSession.getHost().getName());
                    });
              } catch (SshException e) {
                log.error(
                    "Reconnect failed to {}: {}",
                    primarySession.getHost().getName(),
                    e.getMessage(),
                    e);
                Platform.runLater(() -> overlayMessage.setText("Failed: " + e.getMessage()));
              }
            });
  }

  /**
   * Replaces only the primary leaf terminal with a fresh one after reconnect. Secondary panes are
   * kept as-is — they each have their own independent SSH connections.
   */
  private void replacePrimaryLeaf(SshSession newSession) {
    if (primaryLeaf.getContent() != null) primaryLeaf.getContent().dispose();

    TerminalPane fresh =
        new TerminalPane(
            newSession,
            () -> removeLeaf(primaryLeaf),
            orientation -> splitLeaf(primaryLeaf, orientation));

    primaryLeaf.setContent(fresh);
    primaryLeaf.setSplitHandler((l, orientation) -> splitLeaf(l, orientation));
    primaryLeaf.setCloseHandler(() -> removeLeaf(primaryLeaf));

    layoutManager.replaceLeaf(primaryLeaf, primaryLeaf);
  }

  // ── DB helpers ────────────────────────────────────────────────────────────

  private void closeDbSession() {
    if (dbSession != null && !dbSession.isClosed()) {
      sessionRepo.closeSession(dbSession.getId());
      dbSession.setDisconnectedAt(LocalDateTime.now());
    }
  }
}
