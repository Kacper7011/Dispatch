package dev.dispatch.ui.host;

import dev.dispatch.core.model.Host;
import dev.dispatch.ssh.SessionState;
import dev.dispatch.ssh.SshService;
import dev.dispatch.storage.HostRepository;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ListView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the sidebar host list: load, add, edit, delete. Connect is wired up by MainController.
 */
public class HostListController {

  private static final Logger log = LoggerFactory.getLogger(HostListController.class);

  @FXML private ListView<Host> hostListView;
  @FXML private Button connectButton;
  @FXML private Button editButton;
  @FXML private Button deleteButton;

  private HostRepository hostRepository;
  private SshService sshService;
  private final ObservableList<Host> hosts = FXCollections.observableArrayList();
  private final Map<Long, SessionState> sessionStates = new ConcurrentHashMap<>();

  /** Called by MainController after FXML injection. */
  public void init(HostRepository hostRepository, SshService sshService) {
    this.hostRepository = hostRepository;
    this.sshService = sshService;
    hostListView.setItems(hosts);
    hostListView.setCellFactory(
        lv ->
            new HostCell(
                id -> sessionStates.getOrDefault(id, SessionState.DISCONNECTED)));
    hostListView
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((obs, old, selected) -> onSelectionChanged(selected));
    loadHosts();
  }

  /**
   * Updates the session state for a host and refreshes the list so the status dot repaints.
   * Safe to call from any thread.
   */
  public void updateHostState(long hostId, SessionState state) {
    sessionStates.put(hostId, state);
    Platform.runLater(hostListView::refresh);
  }

  /** Allows MainController to set a connect action on the Connect button. */
  public void setOnConnectAction(javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
    connectButton.setOnAction(handler);
  }

  /** Returns the currently selected host, or null. */
  public Host getSelectedHost() {
    return hostListView.getSelectionModel().getSelectedItem();
  }

  /** Reloads the host list from the database. */
  public void refresh() {
    loadHosts();
  }

  // -------------------------------------------------------------------------
  // FXML handlers
  // -------------------------------------------------------------------------

  @FXML
  private void onAdd() {
    openForm(null);
  }

  @FXML
  private void onEdit() {
    Host selected = hostListView.getSelectionModel().getSelectedItem();
    if (selected != null) {
      openForm(selected);
    }
  }

  @FXML
  private void onDelete() {
    Host selected = hostListView.getSelectionModel().getSelectedItem();
    if (selected == null) {
      return;
    }
    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
    confirm.setTitle("Delete host");
    confirm.setHeaderText("Delete \"" + selected.getName() + "\"?");
    confirm.setContentText("This cannot be undone.");
    confirm
        .showAndWait()
        .filter(btn -> btn == ButtonType.OK)
        .ifPresent(btn -> deleteHost(selected));
  }

  @FXML
  private void onConnect() {
    // Wired by MainController via setOnConnectAction() — this handler is a fallback
    log.debug("Connect clicked — no handler wired");
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void loadHosts() {
    Thread.ofVirtual()
        .start(
            () -> {
              log.debug("Loading hosts from database");
              List<Host> loaded = hostRepository.findAll();
              Platform.runLater(
                  () -> {
                    hosts.setAll(loaded);
                    log.debug("Host list refreshed: {} hosts", loaded.size());
                  });
            });
  }

  private void deleteHost(Host host) {
    Thread.ofVirtual()
        .start(
            () -> {
              hostRepository.delete(host.getId());
              log.info("Host deleted: {}", host.getName());
              Platform.runLater(this::loadHosts);
            });
  }

  private void onSelectionChanged(Host selected) {
    boolean hasSelection = selected != null;
    editButton.setDisable(!hasSelection);
    deleteButton.setDisable(!hasSelection);
    connectButton.setDisable(!hasSelection);
  }

  private void openForm(Host existingHost) {
    try {
      FXMLLoader loader =
          new FXMLLoader(getClass().getResource("/dev/dispatch/fxml/host-form.fxml"));
      Stage formStage = new Stage();
      formStage.setTitle(existingHost == null ? "Add host" : "Edit host");
      formStage.initModality(Modality.APPLICATION_MODAL);
      formStage.initOwner(getOwnerWindow());

      Scene scene = new Scene(loader.load());
      scene.getStylesheets().add(getClass().getResource("/css/dispatch-dark.css").toExternalForm());
      formStage.setScene(scene);
      formStage.setResizable(false);

      HostFormController formCtrl = loader.getController();
      formCtrl.init(hostRepository, formStage, existingHost);

      formStage.showAndWait();
      loadHosts();
    } catch (IOException e) {
      log.error("Failed to open host form: {}", e.getMessage(), e);
    }
  }

  private Window getOwnerWindow() {
    if (hostListView.getScene() != null) {
      return hostListView.getScene().getWindow();
    }
    return null;
  }
}
