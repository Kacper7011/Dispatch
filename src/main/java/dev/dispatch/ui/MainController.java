package dev.dispatch.ui;

import dev.dispatch.core.config.AppConfig;
import dev.dispatch.core.model.Host;
import dev.dispatch.docker.DockerDetector;
import dev.dispatch.docker.DockerException;
import dev.dispatch.docker.DockerPresence;
import dev.dispatch.docker.DockerService;
import dev.dispatch.docker.model.ContainerInfo;
import dev.dispatch.ssh.SessionState;
import dev.dispatch.ssh.SshCredentials;
import dev.dispatch.ssh.SshException;
import dev.dispatch.ssh.SshService;
import dev.dispatch.ssh.SshSession;
import dev.dispatch.ssh.TunnelService;
import dev.dispatch.storage.DatabaseManager;
import dev.dispatch.storage.HostRepository;
import dev.dispatch.storage.SessionRepository;
import dev.dispatch.ui.docker.ContainerLogsController;
import dev.dispatch.ui.docker.DockerExecController;
import dev.dispatch.ui.docker.DockerPanelController;
import dev.dispatch.ui.host.HostListController;
import dev.dispatch.ui.ssh.SshTabController;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root controller for the main window.
 *
 * <p>Manages the three-panel SplitPane layout, the custom title bar (drag, resize, window
 * controls), and the Docker panel lifecycle.
 */
public class MainController {

  private static final Logger log = LoggerFactory.getLogger(MainController.class);

  // ── FXML injected ───────────────────────────────────────────────────────────
  @FXML private HostListController hostListController;
  @FXML private HBox titleBar;
  @FXML private HBox trafficLights;
  @FXML private HBox winControls;
  @FXML private Button winMinBtn;
  @FXML private Button winMaxBtn;
  @FXML private Button winCloseBtn;
  private final Button dockerToggleBtn = new Button();
  @FXML private SplitPane mainSplitPane;
  @FXML private StackPane terminalArea;
  @FXML private TabPane sessionTabPane;
  @FXML private Label emptyStateLabel;

  // ── Services ─────────────────────────────────────────────────────────────────
  private Stage stage;
  private SshService sshService;
  private TunnelService tunnelService;
  private HostRepository hostRepository;
  private SessionRepository sessionRepository;
  private final Map<Long, DockerService> dockerServices = new ConcurrentHashMap<>();

  // ── Docker panel state ───────────────────────────────────────────────────────
  private final Map<Tab, Parent> dockerPanels = new HashMap<>();
  private final StackPane dockerPanelContainer = new StackPane();

  /** Whether the user has the Docker panel toggled on. Persists across tab switches. */
  private boolean dockerPanelEnabled = true;

  // ── Window drag / resize state ───────────────────────────────────────────────
  private static final int RESIZE_BORDER = 6;
  private static final int TITLE_BAR_HEIGHT = 38;
  private double dragOffsetX, dragOffsetY;
  private double resizeStartX, resizeStartY;
  private double resizeStartW, resizeStartH, resizeStartSX, resizeStartSY;
  private Cursor activeCursor = Cursor.DEFAULT;
  private boolean resizing = false;

  // ── Init ─────────────────────────────────────────────────────────────────────

  /** Called by {@link dev.dispatch.App} after FXML is loaded and the scene is attached. */
  public void init(
      DatabaseManager dbManager, SshService sshService, TunnelService tunnelService, Stage stage) {
    this.stage = stage;
    this.sshService = sshService;
    this.tunnelService = tunnelService;

    this.hostRepository = new HostRepository(dbManager);
    this.sessionRepository = new SessionRepository(dbManager);
    hostListController.init(hostRepository, sshService);
    hostListController.setOnConnectAction(e -> onConnectRequested());

    configureWindowControls();
    configureSplitPane();
    // macOS: native title bar handles dragging and resizing — skip our custom implementations
    if (!isMac()) {
      setupTitleBarDrag();
      setupWindowResize();
    }

    // Keep maximize button icon in sync with window state
    stage
        .maximizedProperty()
        .addListener((obs, old, maximized) -> winMaxBtn.setText(maximized ? "\u2750" : "\u25A1"));

    sessionTabPane
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((obs, old, selected) -> onTabSelected(selected));

    updateEmptyState();
    injectDockerToggleIntoTabBar();
    log.debug("MainController initialised");
  }

  // ── Docker toggle ─────────────────────────────────────────────────────────────

  /**
   * Overlays the Docker toggle button on the top-right corner of the terminal area StackPane. The
   * button visually sits inside the tab header strip without fighting TabPane's internal clip,
   * which JavaFX reapplies on every layout pass and previously hid the button.
   */
  private void injectDockerToggleIntoTabBar() {
    dockerToggleBtn.getStyleClass().add("tab-toolbar-btn");
    dockerToggleBtn.setOnAction(e -> onDockerToggle());
    loadDockerIcon();
    StackPane.setAlignment(dockerToggleBtn, Pos.TOP_RIGHT);
    StackPane.setMargin(dockerToggleBtn, new Insets(6, 6, 0, 0));
    terminalArea.getChildren().add(dockerToggleBtn);
    log.info("Docker toggle injected into terminalArea");
  }

  private void loadDockerIcon() {
    try (InputStream is = getClass().getResourceAsStream("/img/docker.png")) {
      if (is == null) {
        log.warn("Docker icon not found at /img/docker.png");
        return;
      }
      ImageView iv = new ImageView(new Image(is, 20, 20, true, true));
      dockerToggleBtn.setGraphic(iv);
    } catch (Exception e) {
      log.warn("Failed to load Docker icon: {}", e.getMessage());
    }
  }

  @FXML
  private void onDockerToggle() {
    dockerPanelEnabled = !dockerPanelEnabled;
    if (dockerPanelEnabled) {
      Tab selected = sessionTabPane.getSelectionModel().getSelectedItem();
      Parent panel = (selected != null) ? dockerPanels.get(selected) : null;
      if (panel != null) showDockerPanel(panel);
    } else {
      hideDockerPanel();
    }
  }

  // ── Title-bar FXML handlers ───────────────────────────────────────────────────

  @FXML
  private void onClose() {
    stage.close();
  }

  @FXML
  private void onMinimize() {
    stage.setIconified(true);
  }

  @FXML
  private void onMaximize() {
    stage.setMaximized(!stage.isMaximized());
  }

  // ── Window controls (OS-specific) ────────────────────────────────────────────

  private static boolean isMac() {
    return System.getProperty("os.name", "").toLowerCase().contains("mac");
  }

  private void configureWindowControls() {
    if (isMac()) {
      // macOS: native DECORATED title bar provides traffic lights — hide our entire custom bar
      titleBar.setVisible(false);
      titleBar.setManaged(false);
    } else {
      // Windows/Linux: custom title bar active, traffic light placeholders hidden
      trafficLights.setVisible(false);
      trafficLights.setManaged(false);
      winControls.setVisible(true);
      winControls.setManaged(true);
    }
  }

  // ── Split-pane layout ─────────────────────────────────────────────────────────

  private void configureSplitPane() {
    Node sidebarNode = mainSplitPane.getItems().get(0);
    SplitPane.setResizableWithParent(sidebarNode, Boolean.FALSE);
    sidebarNode.minWidth(160);
    sidebarNode.maxWidth(360);

    dockerPanelContainer.setMinWidth(220);
    dockerPanelContainer.setMaxWidth(520);
    dockerPanelContainer.getStyleClass().add("docker-panel-wrapper");
    SplitPane.setResizableWithParent(dockerPanelContainer, Boolean.FALSE);
  }

  private void showDockerPanel(Parent panel) {
    if (!dockerPanelEnabled) return;
    dockerPanelContainer.getChildren().setAll(panel);
    if (!mainSplitPane.getItems().contains(dockerPanelContainer)) {
      mainSplitPane.getItems().add(dockerPanelContainer);
      mainSplitPane.setDividerPosition(1, 0.78);
    }
  }

  private void hideDockerPanel() {
    mainSplitPane.getItems().remove(dockerPanelContainer);
    dockerPanelContainer.getChildren().clear();
  }

  // ── Window drag (title bar) ────────────────────────────────────────────────────

  private void setupTitleBarDrag() {
    titleBar.setOnMousePressed(
        e -> {
          dragOffsetX = e.getScreenX() - stage.getX();
          dragOffsetY = e.getScreenY() - stage.getY();
        });

    titleBar.setOnMouseDragged(
        e -> {
          if (!stage.isMaximized()) {
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
          }
        });

    // Double-click title bar → toggle maximise
    titleBar.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2) {
            stage.setMaximized(!stage.isMaximized());
          }
        });
  }

  // ── Window resize (scene edges) ───────────────────────────────────────────────

  private void setupWindowResize() {
    var scene = stage.getScene();

    scene.addEventHandler(MouseEvent.MOUSE_MOVED, this::updateResizeCursor);
    scene.addEventHandler(MouseEvent.MOUSE_PRESSED, this::onResizePressed);
    scene.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onResizeDragged);
    scene.addEventHandler(
        MouseEvent.MOUSE_RELEASED,
        e -> {
          resizing = false;
          scene.setCursor(Cursor.DEFAULT);
          activeCursor = Cursor.DEFAULT;
        });
  }

  private void updateResizeCursor(MouseEvent e) {
    if (stage.isMaximized()) return;
    // Skip the title-bar strip — only detect edges below it
    if (e.getY() > TITLE_BAR_HEIGHT) {
      activeCursor = edgeCursorFor(e.getX(), e.getY());
    } else if (e.getY() < RESIZE_BORDER) {
      // Top edge still usable for NW/NE corners
      activeCursor = edgeCursorFor(e.getX(), e.getY());
    } else {
      activeCursor = Cursor.DEFAULT;
    }
    stage.getScene().setCursor(activeCursor);
  }

  private void onResizePressed(MouseEvent e) {
    if (activeCursor == Cursor.DEFAULT) {
      resizing = false;
      return;
    }
    resizing = true;
    resizeStartX = e.getScreenX();
    resizeStartY = e.getScreenY();
    resizeStartW = stage.getWidth();
    resizeStartH = stage.getHeight();
    resizeStartSX = stage.getX();
    resizeStartSY = stage.getY();
  }

  private void onResizeDragged(MouseEvent e) {
    if (!resizing || stage.isMaximized()) return;
    double dx = e.getScreenX() - resizeStartX;
    double dy = e.getScreenY() - resizeStartY;

    if (activeCursor == Cursor.E_RESIZE
        || activeCursor == Cursor.NE_RESIZE
        || activeCursor == Cursor.SE_RESIZE) {
      stage.setWidth(Math.max(AppConfig.WINDOW_MIN_WIDTH, resizeStartW + dx));
    }
    if (activeCursor == Cursor.W_RESIZE
        || activeCursor == Cursor.NW_RESIZE
        || activeCursor == Cursor.SW_RESIZE) {
      double newW = Math.max(AppConfig.WINDOW_MIN_WIDTH, resizeStartW - dx);
      stage.setX(resizeStartSX + resizeStartW - newW);
      stage.setWidth(newW);
    }
    if (activeCursor == Cursor.S_RESIZE
        || activeCursor == Cursor.SE_RESIZE
        || activeCursor == Cursor.SW_RESIZE) {
      stage.setHeight(Math.max(AppConfig.WINDOW_MIN_HEIGHT, resizeStartH + dy));
    }
    if (activeCursor == Cursor.N_RESIZE
        || activeCursor == Cursor.NE_RESIZE
        || activeCursor == Cursor.NW_RESIZE) {
      double newH = Math.max(AppConfig.WINDOW_MIN_HEIGHT, resizeStartH - dy);
      stage.setY(resizeStartSY + resizeStartH - newH);
      stage.setHeight(newH);
    }
  }

  private Cursor edgeCursorFor(double x, double y) {
    double w = stage.getWidth();
    double h = stage.getHeight();
    boolean left = x < RESIZE_BORDER;
    boolean right = x > w - RESIZE_BORDER;
    boolean top = y < RESIZE_BORDER;
    boolean bottom = y > h - RESIZE_BORDER;
    if (top && left) return Cursor.NW_RESIZE;
    if (top && right) return Cursor.NE_RESIZE;
    if (bottom && left) return Cursor.SW_RESIZE;
    if (bottom && right) return Cursor.SE_RESIZE;
    if (top) return Cursor.N_RESIZE;
    if (bottom) return Cursor.S_RESIZE;
    if (left) return Cursor.W_RESIZE;
    if (right) return Cursor.E_RESIZE;
    return Cursor.DEFAULT;
  }

  // ── Session connection flow ───────────────────────────────────────────────────

  private void onConnectRequested() {
    Host host = hostListController.getSelectedHost();
    if (host == null) return;
    // Skip passphrase dialog if user previously connected with no passphrase
    if (host.getAuthType() == dev.dispatch.core.model.AuthType.KEY && host.isKeyNoPassphrase()) {
      connectAsync(host, SshCredentials.keyNoPassphrase());
      return;
    }
    CredentialDialog.prompt(host).ifPresent(credentials -> connectAsync(host, credentials));
  }

  private void connectAsync(Host host, SshCredentials credentials) {
    Tab loadingTab = createLoadingTab(host);
    sessionTabPane.getTabs().add(loadingTab);
    sessionTabPane.getSelectionModel().select(loadingTab);
    updateEmptyState();

    // Tracks whether the user closed the loading tab before the connection finished
    java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    loadingTab.setOnClosed(
        e -> {
          cancelled.set(true);
          updateEmptyState();
        });

    Thread.ofVirtual()
        .name("ssh-connect-" + host.getName())
        .start(
            () -> {
              try {
                SshSession session = sshService.connect(host, credentials);
                if (cancelled.get()) {
                  // Tab was closed while we were connecting — drop the session immediately
                  sshService.disconnect(host.getId());
                  return;
                }
                // Remember that this key has no passphrase so future connections skip the dialog
                if (!credentials.hasPassphrase()
                    && credentials.getPassword() == null
                    && !host.isKeyNoPassphrase()) {
                  hostRepository.markKeyNoPassphrase(host.getId());
                }
                Platform.runLater(
                    () -> {
                      hostListController.updateHostState(host.getId(), SessionState.CONNECTED);
                      openSessionTab(loadingTab, session, credentials);
                    });
              } catch (SshException e) {
                if (!cancelled.get()) {
                  log.error("Connection failed to {}: {}", host.getName(), e.getMessage(), e);
                  Platform.runLater(() -> showConnectionError(loadingTab, host, e));
                }
              }
            });
  }

  private void openSessionTab(Tab tab, SshSession session, SshCredentials credentials) {
    SshTabController tabCtrl =
        new SshTabController(session, credentials, sshService, sessionRepository, hostRepository);
    tabCtrl.setOnReconnected(
        newSession -> {
          tab.setText(newSession.getHost().getName());
          hostListController.updateHostState(newSession.getHost().getId(), SessionState.CONNECTED);
          // Drop stale Docker panel so detectDockerAsync can attach a fresh one
          dockerPanels.remove(tab);
          detectDockerAsync(newSession, tab);
        });

    session.setOnLost(
        lost -> {
          tabCtrl.onSessionLost();
          Platform.runLater(
              () -> {
                tab.setText("⚠ " + lost.getHost().getName());
                hostListController.updateHostState(lost.getHost().getId(), SessionState.LOST);
              });
        });

    tab.setText(session.getHost().getName());
    tab.setContent(tabCtrl.createNode());
    tab.setOnClosed(
        e -> {
          tabCtrl.dispose();
          closeDockerForSession(session);
          // Disconnect off the FX thread — jschSession.disconnect() can block on network I/O
          Thread.ofVirtual()
              .name("ssh-disconnect-" + session.getHost().getName())
              .start(() -> sshService.disconnect(session.getHost().getId()));
          hostListController.updateHostState(session.getHost().getId(), SessionState.DISCONNECTED);
          dockerPanels.remove(tab);
          updateEmptyState();
        });
    detectDockerAsync(session, tab);
    log.info("Session tab opened for {}", session.getHost().getName());
  }

  // ── Docker panel lifecycle ────────────────────────────────────────────────────

  private void detectDockerAsync(SshSession session, Tab tab) {
    Thread.ofVirtual()
        .name("docker-detect-" + session.getHost().getName())
        .start(
            () -> {
              DockerDetector detector = new DockerDetector();
              DockerPresence presence = detector.detect(session);
              if (!presence.isAvailable()) {
                log.warn("Docker not found on {}", session.getHost().getName());
                return;
              }
              String socketPath =
                  presence.isRootless()
                      ? detector.resolveRootlessSocketPath(session)
                      : presence.getSocketPath();
              connectDockerAndAttachPanel(session, socketPath, tab);
            });
  }

  private void connectDockerAndAttachPanel(SshSession session, String socketPath, Tab tab) {
    DockerService dockerService = new DockerService(tunnelService);
    try {
      dockerService.connect(session, socketPath);
      dockerServices.put(session.getHost().getId(), dockerService);
      Platform.runLater(() -> attachDockerPanel(tab, dockerService));
    } catch (DockerException e) {
      log.warn("Docker connect failed for {}: {}", session.getHost().getName(), e.getMessage());
      dockerService.close();
    }
  }

  private void attachDockerPanel(Tab tab, DockerService dockerService) {
    try {
      FXMLLoader loader =
          new FXMLLoader(getClass().getResource("/dev/dispatch/fxml/docker-panel.fxml"));
      Parent panel = loader.load();
      DockerPanelController ctrl = loader.getController();
      ctrl.init(dockerService);
      ctrl.setOnOpenLogs(c -> openLogsTab(c, dockerService));
      ctrl.setOnOpenExec(c -> openDockerExecTab(c, dockerService));
      ctrl.setOnClose(
          () -> {
            dockerPanelEnabled = false;
            hideDockerPanel();
          });
      dockerPanels.put(tab, panel);
      if (sessionTabPane.getSelectionModel().getSelectedItem() == tab) {
        showDockerPanel(panel);
      }
      log.info("Docker panel attached for tab '{}'", tab.getText());
    } catch (IOException e) {
      log.error("Failed to load Docker panel FXML: {}", e.getMessage(), e);
    }
  }

  private void openDockerExecTab(ContainerInfo container, DockerService dockerService) {
    DockerExecController execCtrl = new DockerExecController(dockerService, container);
    Tab tab = new Tab("exec › " + container.getName());
    tab.setContent(execCtrl.createNode());
    tab.setOnClosed(e -> execCtrl.dispose());
    sessionTabPane.getTabs().add(tab);
    sessionTabPane.getSelectionModel().select(tab);
    updateEmptyState();
    log.info("Exec tab opened for {}", container.getName());
  }

  private void openLogsTab(ContainerInfo container, DockerService dockerService) {
    ContainerLogsController logsCtrl = new ContainerLogsController(dockerService, container);
    Tab tab = new Tab("logs › " + container.getName());
    tab.setContent(logsCtrl.createNode());
    tab.setOnClosed(e -> logsCtrl.dispose());
    sessionTabPane.getTabs().add(tab);
    sessionTabPane.getSelectionModel().select(tab);
    updateEmptyState();
    log.info("Log tab opened for {}", container.getName());
  }

  private void onTabSelected(Tab tab) {
    Parent panel = (tab != null) ? dockerPanels.get(tab) : null;
    if (panel != null) {
      showDockerPanel(panel);
    } else {
      hideDockerPanel();
    }
  }

  private void closeDockerForSession(SshSession session) {
    DockerService ds = dockerServices.remove(session.getHost().getId());
    if (ds != null) ds.close();
  }

  // ── UI helpers ────────────────────────────────────────────────────────────────

  private Tab createLoadingTab(Host host) {
    Tab tab = new Tab("Connecting… " + host.getName());
    tab.setContent(new Label("Connecting to " + host.getHostname() + "…"));
    tab.setOnClosed(e -> updateEmptyState());
    return tab;
  }

  private void showConnectionError(Tab tab, Host host, SshException e) {
    sessionTabPane.getTabs().remove(tab);
    updateEmptyState();
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setTitle("Connection failed");
    alert.setHeaderText("Could not connect to " + host.getName());
    alert.setContentText(e.getMessage());
    alert.showAndWait();
  }

  private void updateEmptyState() {
    boolean hasTabs = !sessionTabPane.getTabs().isEmpty();
    emptyStateLabel.setVisible(!hasTabs);
    emptyStateLabel.setManaged(!hasTabs);
  }
}
