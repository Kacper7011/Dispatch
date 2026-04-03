package dev.dispatch.ui;

import dev.dispatch.core.model.Host;
import dev.dispatch.docker.DockerDetector;
import dev.dispatch.docker.DockerException;
import dev.dispatch.docker.DockerPresence;
import dev.dispatch.docker.DockerService;
import dev.dispatch.ssh.SessionState;
import dev.dispatch.ssh.SshCredentials;
import dev.dispatch.ssh.SshException;
import dev.dispatch.ssh.SshService;
import dev.dispatch.ssh.SshSession;
import dev.dispatch.ssh.TunnelService;
import dev.dispatch.ssh.terminal.TerminalController;
import dev.dispatch.storage.DatabaseManager;
import dev.dispatch.storage.HostRepository;
import dev.dispatch.ui.docker.DockerPanelController;
import dev.dispatch.ui.host.HostListController;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root controller for the main window.
 *
 * <p>Owns application-level services and wires them into child controllers. After a successful SSH
 * connection, detects Docker in the background and adds a Docker sub-tab when found.
 */
public class MainController {

  private static final Logger log = LoggerFactory.getLogger(MainController.class);

  @javafx.fxml.FXML private HostListController hostListController;
  @javafx.fxml.FXML private TabPane sessionTabPane;
  @javafx.fxml.FXML private Label emptyStateLabel;

  private SshService sshService;
  private TunnelService tunnelService;
  private final Map<Long, DockerService> dockerServices = new ConcurrentHashMap<>();

  /** Injects services and initialises child controllers. Called by App after FXML is loaded. */
  public void init(DatabaseManager dbManager, SshService sshService, TunnelService tunnelService) {
    this.sshService = sshService;
    this.tunnelService = tunnelService;
    HostRepository hostRepository = new HostRepository(dbManager);
    hostListController.init(hostRepository, sshService);
    hostListController.setOnConnectAction(e -> onConnectRequested());
    updateEmptyState();
    log.debug("MainController initialised");
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void onConnectRequested() {
    Host host = hostListController.getSelectedHost();
    if (host == null) return;
    CredentialDialog.prompt(host).ifPresent(credentials -> connectAsync(host, credentials));
  }

  private void connectAsync(Host host, SshCredentials credentials) {
    Tab loadingTab = createLoadingTab(host);
    sessionTabPane.getTabs().add(loadingTab);
    sessionTabPane.getSelectionModel().select(loadingTab);
    updateEmptyState();

    Thread.ofVirtual()
        .name("ssh-connect-" + host.getName())
        .start(
            () -> {
              try {
                SshSession session = sshService.connect(host, credentials);
                session.setOnLost(lost -> Platform.runLater(() -> onSessionLost(lost)));
                Platform.runLater(
                  () -> {
                    hostListController.updateHostState(
                        host.getId(), SessionState.CONNECTED);
                    openSessionTab(loadingTab, session);
                  });
              } catch (SshException e) {
                log.error("Connection failed to {}: {}", host.getName(), e.getMessage(), e);
                Platform.runLater(() -> showConnectionError(loadingTab, host, e));
              }
            });
  }

  private void openSessionTab(Tab tab, SshSession session) {
    TerminalController terminal = new TerminalController(session);

    TabPane innerTabs = new TabPane();
    innerTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    innerTabs.getTabs().add(new Tab("Terminal", terminal.createNode()));

    tab.setText(session.getHost().getName());
    tab.setContent(innerTabs);
    tab.setOnClosed(
        e -> {
          terminal.dispose();
          closeDockerForSession(session);
          sshService.disconnect(session.getHost().getId());
          hostListController.updateHostState(
              session.getHost().getId(), SessionState.DISCONNECTED);
          updateEmptyState();
        });

    detectDockerAsync(session, innerTabs);
    log.info("Session tab opened for {}", session.getHost().getName());
  }

  private void detectDockerAsync(SshSession session, TabPane innerTabs) {
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
              connectDockerAndAddTab(session, socketPath, innerTabs);
            });
  }

  private void connectDockerAndAddTab(SshSession session, String socketPath, TabPane innerTabs) {
    DockerService dockerService = new DockerService(tunnelService);
    try {
      dockerService.connect(session, socketPath);
      dockerServices.put(session.getHost().getId(), dockerService);
      Platform.runLater(() -> addDockerTab(innerTabs, dockerService));
    } catch (DockerException e) {
      log.warn("Docker connect failed for {}: {}", session.getHost().getName(), e.getMessage());
      dockerService.close();
    }
  }

  private void addDockerTab(TabPane innerTabs, DockerService dockerService) {
    try {
      FXMLLoader loader =
          new FXMLLoader(getClass().getResource("/dev/dispatch/fxml/docker-panel.fxml"));
      Parent panel = loader.load();
      DockerPanelController ctrl = loader.getController();
      ctrl.init(dockerService);
      innerTabs.getTabs().add(new Tab("Docker", panel));
      log.info("Docker tab added");
    } catch (IOException e) {
      log.error("Failed to load Docker panel FXML: {}", e.getMessage(), e);
    }
  }

  private void closeDockerForSession(SshSession session) {
    DockerService ds = dockerServices.remove(session.getHost().getId());
    if (ds != null) ds.close();
  }

  private void onSessionLost(SshSession session) {
    sessionTabPane.getTabs().stream()
        .filter(t -> t.getText().equals(session.getHost().getName()))
        .findFirst()
        .ifPresent(t -> t.setText("⚠ " + session.getHost().getName()));
    hostListController.updateHostState(session.getHost().getId(), SessionState.LOST);
    log.warn("Session lost: {}", session.getHost().getName());
  }

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
