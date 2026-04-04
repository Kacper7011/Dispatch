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
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.MouseButton;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the sidebar host list: load, add, edit, delete.
 *
 * <p>Connect is triggered by double-clicking a host row. Edit and Delete are available via the
 * right-click context menu. The "+" Add Host button at the bottom opens the host form.
 */
public class HostListController {

  private static final Logger log = LoggerFactory.getLogger(HostListController.class);

  @FXML private ListView<Host> hostListView;
  @FXML private Label hostCountLabel;

  private HostRepository hostRepository;
  private EventHandler<ActionEvent> connectHandler;
  private final ObservableList<Host> hosts = FXCollections.observableArrayList();
  private final Map<Long, SessionState> sessionStates = new ConcurrentHashMap<>();

  /** Called by MainController after FXML injection. */
  public void init(HostRepository hostRepository, SshService sshService) {
    this.hostRepository = hostRepository;
    hostListView.setItems(hosts);
    hostListView.setCellFactory(
        lv -> new HostCell(id -> sessionStates.getOrDefault(id, SessionState.DISCONNECTED)));
    setupDoubleClickConnect();
    setupContextMenu();
    loadHosts();
  }

  /**
   * Updates the session state for a host and refreshes the list so the status dot repaints. Safe to
   * call from any thread.
   */
  public void updateHostState(long hostId, SessionState state) {
    sessionStates.put(hostId, state);
    Platform.runLater(hostListView::refresh);
  }

  /** Allows MainController to attach a connect action triggered by double-clicking a host. */
  public void setOnConnectAction(EventHandler<ActionEvent> handler) {
    this.connectHandler = handler;
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

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void setupDoubleClickConnect() {
    hostListView.setOnMouseClicked(
        event -> {
          if (event.getButton() == MouseButton.PRIMARY
              && event.getClickCount() == 2
              && getSelectedHost() != null) {
            fireConnect();
          }
        });
  }

  private void setupContextMenu() {
    MenuItem editItem = new MenuItem("Edit");
    MenuItem deleteItem = new MenuItem("Delete");
    MenuItem connectItem = new MenuItem("Connect");

    connectItem.setOnAction(e -> fireConnect());
    editItem.setOnAction(
        e -> {
          Host selected = getSelectedHost();
          if (selected != null) openForm(selected);
        });
    deleteItem.setOnAction(e -> confirmAndDelete());

    ContextMenu menu = new ContextMenu(connectItem, new SeparatorMenuItem(), editItem, deleteItem);
    hostListView.setContextMenu(menu);
  }

  private void fireConnect() {
    if (connectHandler != null) {
      connectHandler.handle(new ActionEvent());
    }
  }

  private void confirmAndDelete() {
    Host selected = getSelectedHost();
    if (selected == null) return;
    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
    confirm.setTitle("Delete host");
    confirm.setHeaderText("Delete \"" + selected.getName() + "\"?");
    confirm.setContentText("This cannot be undone.");
    confirm
        .showAndWait()
        .filter(btn -> btn == ButtonType.OK)
        .ifPresent(btn -> deleteHost(selected));
  }

  private void loadHosts() {
    Thread.ofVirtual()
        .start(
            () -> {
              log.debug("Loading hosts from database");
              List<Host> loaded = hostRepository.findAll();
              Platform.runLater(
                  () -> {
                    hosts.setAll(loaded);
                    if (hostCountLabel != null) {
                      hostCountLabel.setText(String.valueOf(loaded.size()));
                    }
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

  private void openForm(Host existingHost) {
    try {
      FXMLLoader loader =
          new FXMLLoader(getClass().getResource("/dev/dispatch/fxml/host-form.fxml"));
      Stage formStage = new Stage();
      formStage.setTitle(existingHost == null ? "Add host" : "Edit host");
      formStage.initModality(Modality.APPLICATION_MODAL);
      formStage.initOwner(getOwnerWindow());

      Scene scene = new Scene(loader.load());
      scene.setFill(javafx.scene.paint.Color.web("#161616"));
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
