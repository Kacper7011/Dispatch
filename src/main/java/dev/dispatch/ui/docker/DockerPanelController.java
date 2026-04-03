package dev.dispatch.ui.docker;

import dev.dispatch.docker.DockerException;
import dev.dispatch.docker.DockerService;
import dev.dispatch.docker.model.ContainerInfo;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Docker panel — shows all containers on the remote host and provides Start /
 * Stop / Remove actions per row.
 *
 * <p>All Docker operations run on virtual threads; UI updates are posted via {@code
 * Platform.runLater()}.
 */
public class DockerPanelController {

  private static final Logger log = LoggerFactory.getLogger(DockerPanelController.class);

  @FXML private TableView<ContainerInfo> containerTable;
  @FXML private TableColumn<ContainerInfo, String> colName;
  @FXML private TableColumn<ContainerInfo, String> colImage;
  @FXML private TableColumn<ContainerInfo, ContainerInfo> colStatus;
  @FXML private TableColumn<ContainerInfo, ContainerInfo> colActions;
  @FXML private Label statusLabel;

  private DockerService dockerService;

  /** Injects the connected {@link DockerService} and loads the initial container list. */
  public void init(DockerService dockerService) {
    this.dockerService = dockerService;
    containerTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    setupColumns();
    refresh();
  }

  // -------------------------------------------------------------------------
  // FXML handlers
  // -------------------------------------------------------------------------

  @FXML
  private void onRefresh() {
    refresh();
  }

  // -------------------------------------------------------------------------
  // Package-private actions — called by ContainerRowController
  // -------------------------------------------------------------------------

  void startContainer(ContainerInfo c) {
    runContainerOp(() -> dockerService.startContainer(c.getId()), "Starting " + c.getName(), c);
  }

  void stopContainer(ContainerInfo c) {
    runContainerOp(() -> dockerService.stopContainer(c.getId()), "Stopping " + c.getName(), c);
  }

  void removeContainer(ContainerInfo c) {
    runContainerOp(() -> dockerService.removeContainer(c.getId()), "Removing " + c.getName(), c);
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void setupColumns() {
    colName.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getName()));

    colImage.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getImage()));

    // Status and Actions columns receive the full ContainerInfo so their cells
    // can access both the status enum (for badge colour) and the id (for actions).
    colStatus.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));
    colStatus.setCellFactory(col -> new ContainerStatusCell());

    colActions.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));
    colActions.setCellFactory(col -> new ContainerRowController(this));
  }

  private void refresh() {
    setStatus("Loading…");
    Thread.ofVirtual()
        .name("docker-list-containers")
        .start(
            () -> {
              try {
                List<ContainerInfo> containers = dockerService.listContainers();
                Platform.runLater(
                    () -> {
                      containerTable.getItems().setAll(containers);
                      setStatus(containers.size() + " container(s)");
                      log.debug("Container list refreshed: {} items", containers.size());
                    });
              } catch (DockerException e) {
                log.error("Failed to list containers: {}", e.getMessage(), e);
                Platform.runLater(() -> setStatus("Error: " + e.getMessage()));
              }
            });
  }

  private void runContainerOp(Runnable op, String description, ContainerInfo container) {
    setStatus(description + "…");
    Thread.ofVirtual()
        .name("docker-op-" + container.getShortId())
        .start(
            () -> {
              try {
                op.run();
                log.info("{} — done", description);
                Platform.runLater(this::refresh);
              } catch (DockerException e) {
                log.error("{} failed: {}", description, e.getMessage(), e);
                Platform.runLater(() -> setStatus("Error: " + e.getMessage()));
              }
            });
  }

  private void setStatus(String text) {
    statusLabel.setText(text);
  }
}
