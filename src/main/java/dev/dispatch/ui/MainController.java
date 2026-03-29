package dev.dispatch.ui;

import dev.dispatch.ssh.SshService;
import dev.dispatch.storage.DatabaseManager;
import dev.dispatch.storage.HostRepository;
import dev.dispatch.ui.host.HostListController;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root controller for the main window.
 *
 * <p>Owns the application-level services (database, SSH) and wires them into child controllers
 * via {@link #init(DatabaseManager, SshService)}.
 */
public class MainController {

  private static final Logger log = LoggerFactory.getLogger(MainController.class);

  @FXML private HostListController hostListController;
  @FXML private TabPane sessionTabPane;
  @FXML private Label emptyStateLabel;

  /**
   * Injects services and initialises child controllers. Called by App after the FXML is loaded.
   */
  public void init(DatabaseManager dbManager, SshService sshService) {
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
    // SSH terminal integration is implemented in feat/ssh-terminal.
    // For now, show a placeholder tab so the UX is visible.
    var host = hostListController.getSelectedHost();
    if (host == null) {
      return;
    }
    log.info("Connect requested for host: {}", host.getName());
    var tab = new javafx.scene.control.Tab(host.getName());
    tab.setContent(
        new Label("Terminal for " + host.getName() + " — SSH terminal module not yet implemented"));
    sessionTabPane.getTabs().add(tab);
    sessionTabPane.getSelectionModel().select(tab);
    updateEmptyState();
  }

  private void updateEmptyState() {
    boolean hasTabs = !sessionTabPane.getTabs().isEmpty();
    emptyStateLabel.setVisible(!hasTabs);
    emptyStateLabel.setManaged(!hasTabs);
  }
}
