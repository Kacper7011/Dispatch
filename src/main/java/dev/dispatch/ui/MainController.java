package dev.dispatch.ui;

import dev.dispatch.core.model.Host;
import dev.dispatch.ssh.SshCredentials;
import dev.dispatch.ssh.SshException;
import dev.dispatch.ssh.SshService;
import dev.dispatch.ssh.SshSession;
import dev.dispatch.ssh.terminal.TerminalController;
import dev.dispatch.storage.DatabaseManager;
import dev.dispatch.storage.HostRepository;
import dev.dispatch.ui.host.HostListController;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root controller for the main window.
 *
 * <p>Owns application-level services (database, SSH) and wires them into child controllers. Handles
 * the Connect action: prompts for credentials, opens the SSH session on a virtual thread, and
 * creates a terminal tab on success.
 */
public class MainController {

  private static final Logger log = LoggerFactory.getLogger(MainController.class);

  @javafx.fxml.FXML private HostListController hostListController;
  @javafx.fxml.FXML private TabPane sessionTabPane;
  @javafx.fxml.FXML private Label emptyStateLabel;

  private SshService sshService;

  /** Injects services and initialises child controllers. Called by App after FXML is loaded. */
  public void init(DatabaseManager dbManager, SshService sshService) {
    this.sshService = sshService;
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
    if (host == null) {
      return;
    }
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
                Platform.runLater(() -> openTerminalTab(loadingTab, session));
              } catch (SshException e) {
                log.error("Connection failed to {}: {}", host.getName(), e.getMessage(), e);
                Platform.runLater(() -> showConnectionError(loadingTab, host, e));
              }
            });
  }

  private void openTerminalTab(Tab tab, SshSession session) {
    TerminalController terminal = new TerminalController(session);
    tab.setText(session.getHost().getName());
    tab.setContent(terminal.createNode());
    tab.setOnClosed(
        e -> {
          terminal.dispose();
          sshService.disconnect(session.getHost().getId());
          updateEmptyState();
        });
    log.info("Terminal tab opened for {}", session.getHost().getName());
  }

  private void onSessionLost(SshSession session) {
    // Mark the tab header to indicate the connection dropped
    sessionTabPane.getTabs().stream()
        .filter(t -> t.getText().equals(session.getHost().getName()))
        .findFirst()
        .ifPresent(t -> t.setText("⚠ " + session.getHost().getName()));
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
