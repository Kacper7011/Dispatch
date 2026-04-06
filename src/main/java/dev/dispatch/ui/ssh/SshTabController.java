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
import java.util.ArrayList;
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
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the UI and lifecycle of a single SSH session tab.
 *
 * <p>The tab holds a {@link SplitPane} with one or more slots. The first slot is the primary
 * connected terminal. Each additional slot (created via split buttons) starts as a host-picker UI
 * that lets the user connect to any saved host independently — so left and right panes can talk to
 * different servers simultaneously.
 */
public class SshTabController {

  private static final Logger log = LoggerFactory.getLogger(SshTabController.class);

  private final SshService sshService;
  private final SessionRepository sessionRepo;
  private final HostRepository hostRepository;
  private final SshCredentials primaryCredentials;

  /** Each slot is either a {@link TerminalPane} (connected) or a {@link BorderPane} (pending). */
  private final List<Object> slots = new ArrayList<>();

  private SshSession primarySession;
  private SplitPane splitPane;
  private StackPane contentPane;
  private VBox reconnectOverlay;
  private Label overlayMessage;
  private Session dbSession;

  private Consumer<SshSession> onReconnected;

  /** Fired on the FX thread each time a secondary slot successfully connects to a new host. */
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

    splitPane = new SplitPane();
    splitPane.setOrientation(Orientation.HORIZONTAL);
    VBox.setVgrow(splitPane, Priority.ALWAYS);

    addConnectedPane(primarySession);

    VBox layout = new VBox(buildToolbar(), splitPane);
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
    slots.forEach(
        slot -> {
          if (slot instanceof TerminalPane tp) tp.dispose();
        });
    slots.clear();
    log.info("SshTabController disposed for {}", primarySession.getHost().getName());
  }

  // ── Pane management ──────────────────────────────────────────────────────────

  /** Adds a connected terminal pane for the given session. */
  private void addConnectedPane(SshSession session) {
    int index = slots.size();
    TerminalPane pane = new TerminalPane(session, () -> removeSlot(index));
    slots.add(pane);
    splitPane.getItems().add(pane.getNode());
    updateCloseButtons();
  }

  /**
   * Adds an empty host-picker pane. The user selects a host and connects independently of the
   * primary session.
   */
  private void addPendingPane() {
    int index = slots.size();
    BorderPane pending = buildHostPickerPane(index);
    slots.add(pending);
    splitPane.getItems().add(pending);
    updateCloseButtons();
    distributeEqually();
    log.debug("Pending pane added at slot {}", index);
  }

  /** Replaces a pending host-picker slot with a live terminal once the user has connected. */
  private void activateSlot(int index, SshSession session) {
    TerminalPane pane = new TerminalPane(session, () -> removeSlot(index));
    slots.set(index, pane);
    splitPane.getItems().set(index, pane.getNode());
    updateCloseButtons();
    log.info("Slot {} connected to {}", index, session.getHost().getName());
  }

  private void removeSlot(int index) {
    if (slots.size() <= 1) return;
    Object removed = slots.remove(index);
    if (removed instanceof TerminalPane tp) tp.dispose();
    splitPane.getItems().remove(index);
    updateCloseButtons();
    distributeEqually();
    log.debug("Slot {} removed — total slots: {}", index, slots.size());
  }

  private void splitHorizontal() {
    splitPane.setOrientation(Orientation.HORIZONTAL);
    addPendingPane();
  }

  private void splitVertical() {
    splitPane.setOrientation(Orientation.VERTICAL);
    addPendingPane();
  }

  /** Distributes dividers so all panes receive equal space. */
  private void distributeEqually() {
    int count = slots.size();
    if (count < 2) return;
    double[] positions = new double[count - 1];
    for (int i = 0; i < positions.length; i++) {
      positions[i] = (double) (i + 1) / count;
    }
    splitPane.setDividerPositions(positions);
  }

  /** Shows close buttons only when more than one slot is open. */
  private void updateCloseButtons() {
    boolean show = slots.size() > 1;
    slots.forEach(
        slot -> {
          if (slot instanceof TerminalPane tp) tp.showCloseButton(show);
        });
  }

  // ── Host picker UI ────────────────────────────────────────────────────────────

  /**
   * Builds a centered host-picker panel: a combo box of saved hosts and a Connect button. On
   * successful connection the slot is replaced with a live terminal.
   */
  private BorderPane buildHostPickerPane(int slotIndex) {
    List<Host> hosts = hostRepository.findAll();

    ComboBox<Host> hostCombo = new ComboBox<>();
    hostCombo.getItems().addAll(hosts);
    hostCombo.setPromptText("Select host…");
    hostCombo.setPrefWidth(220);
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
    connectBtn.getStyleClass().add("reconnect-btn");
    connectBtn.setOnAction(
        e -> onPickerConnectClicked(slotIndex, hostCombo, connectBtn, statusLabel));

    // Close button for this pending pane
    Button closeBtn = new Button("×");
    closeBtn.getStyleClass().add("pane-close-btn");
    closeBtn.setOnAction(e -> removeSlot(slotIndex));

    HBox header = new HBox(closeBtn);
    header.setAlignment(Pos.CENTER_RIGHT);
    header.getStyleClass().add("pane-header");

    VBox center = new VBox(10, new Label("Connect to host"), hostCombo, connectBtn, statusLabel);
    center.setAlignment(Pos.CENTER);
    center.getStyleClass().add("host-picker");

    BorderPane pane = new BorderPane(center);
    pane.setTop(header);
    return pane;
  }

  private void onPickerConnectClicked(
      int slotIndex, ComboBox<Host> hostCombo, Button connectBtn, Label statusLabel) {
    Host host = hostCombo.getValue();
    if (host == null) {
      statusLabel.setText("Please select a host first.");
      return;
    }
    // Prompt for credentials on the FX thread, then connect on a virtual thread
    CredentialDialog.prompt(host)
        .ifPresentOrElse(
            credentials -> connectSlotAsync(slotIndex, host, credentials, connectBtn, statusLabel),
            () -> statusLabel.setText("Cancelled."));
  }

  private void connectSlotAsync(
      int slotIndex, Host host, SshCredentials credentials, Button connectBtn, Label statusLabel) {
    connectBtn.setDisable(true);
    statusLabel.setText("Connecting…");

    Thread.ofVirtual()
        .name("ssh-split-connect-" + host.getName())
        .start(
            () -> {
              try {
                SshSession newSession = sshService.connect(host, credentials);
                Platform.runLater(
                    () -> {
                      activateSlot(slotIndex, newSession);
                      distributeEqually();
                      if (onSlotConnected != null) onSlotConnected.accept(newSession);
                    });
                log.info("Split pane slot {} connected to {}", slotIndex, host.getName());
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

  // ── Toolbar ───────────────────────────────────────────────────────────────────

  private HBox buildToolbar() {
    Button splitH = new Button("Split ↔");
    splitH.getStyleClass().add("split-btn");
    splitH.setOnAction(e -> splitHorizontal());

    Button splitV = new Button("Split ↕");
    splitV.getStyleClass().add("split-btn");
    splitV.setOnAction(e -> splitVertical());

    HBox toolbar = new HBox(4, splitH, splitV);
    toolbar.getStyleClass().add("pane-toolbar");
    toolbar.setAlignment(Pos.CENTER_LEFT);
    return toolbar;
  }

  // ── Reconnect overlay (primary session) ──────────────────────────────────────

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
                      replacePrimaryTerminal(newSession);
                      reconnectOverlay.setVisible(false);
                      if (onReconnected != null) {
                        onReconnected.accept(newSession);
                      }
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
   * Replaces only the primary (index 0) terminal with a fresh one. Secondary split panes are kept
   * as-is — they have their own independent connections.
   */
  private void replacePrimaryTerminal(SshSession newSession) {
    if (!slots.isEmpty() && slots.get(0) instanceof TerminalPane tp) {
      tp.dispose();
      TerminalPane fresh = new TerminalPane(newSession, () -> removeSlot(0));
      slots.set(0, fresh);
      splitPane.getItems().set(0, fresh.getNode());
      updateCloseButtons();
    }
  }

  // ── DB helpers ────────────────────────────────────────────────────────────────

  private void closeDbSession() {
    if (dbSession != null && !dbSession.isClosed()) {
      sessionRepo.closeSession(dbSession.getId());
      dbSession.setDisconnectedAt(LocalDateTime.now());
    }
  }
}
