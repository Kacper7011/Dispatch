package dev.dispatch.ui.docker;

import dev.dispatch.docker.DockerException;
import dev.dispatch.docker.DockerService;
import dev.dispatch.docker.model.ContainerInfo;
import dev.dispatch.docker.model.ContainerStatus;
import dev.dispatch.docker.model.ImageInfo;
import java.util.List;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Docker side-panel. Displays containers, images, networks, and volumes as
 * collapsible sections. All Docker operations run on virtual threads.
 */
public class DockerPanelController {

  private static final Logger log = LoggerFactory.getLogger(DockerPanelController.class);

  @FXML private VBox contentBox;
  @FXML private Label statusLabel;
  @FXML private Button closePanelBtn;

  private final DockerSection containersSection = new DockerSection("CONTAINERS");
  private final DockerSection imagesSection = new DockerSection("IMAGES");
  private final DockerSection networksSection = new DockerSection("NETWORKS");
  private final DockerSection volumesSection = new DockerSection("VOLUMES");

  private DockerService dockerService;
  private Runnable onCloseCallback;
  private Consumer<ContainerInfo> onOpenLogs;
  private Consumer<ContainerInfo> onOpenExec;
  private Node selectedRow;

  /** Wires the callback that opens a new log tab in the main layout. */
  public void setOnOpenLogs(Consumer<ContainerInfo> callback) {
    this.onOpenLogs = callback;
  }

  /** Wires the callback that opens a new exec terminal tab in the main layout. */
  public void setOnOpenExec(Consumer<ContainerInfo> callback) {
    this.onOpenExec = callback;
  }

  /** Called by MainController to wire the panel's close button back to the main layout. */
  public void setOnClose(Runnable callback) {
    this.onCloseCallback = callback;
    closePanelBtn.setOnAction(
        e -> {
          if (onCloseCallback != null) onCloseCallback.run();
        });
  }

  /** Injects the connected {@link DockerService} and loads the initial data. */
  public void init(DockerService dockerService) {
    this.dockerService = dockerService;
    contentBox
        .getChildren()
        .addAll(containersSection, imagesSection, networksSection, volumesSection);
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
  // Selection — called by row widgets
  // -------------------------------------------------------------------------

  /** Transfers the visual selection highlight to the given row node. */
  void selectRow(Node row) {
    if (selectedRow != null) selectedRow.getStyleClass().remove("docker-item-selected");
    selectedRow = row;
    row.getStyleClass().add("docker-item-selected");
  }

  // -------------------------------------------------------------------------
  // Package-private actions — called by ContainerRow
  // -------------------------------------------------------------------------

  void streamLogs(ContainerInfo c) {
    if (onOpenLogs != null) onOpenLogs.accept(c);
    else log.warn("No logs callback registered for {}", c.getName());
  }

  void execContainer(ContainerInfo c) {
    if (onOpenExec != null) onOpenExec.accept(c);
    else log.warn("No exec callback registered for {}", c.getName());
  }

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

  private void refresh() {
    setStatus("Loading…");
    Thread.ofVirtual()
        .name("docker-refresh")
        .start(
            () -> {
              try {
                List<ContainerInfo> containers = dockerService.listContainers();
                List<ImageInfo> images = dockerService.listImages();
                Platform.runLater(
                    () -> {
                      populateContainers(containers);
                      populateImages(images);
                      setStatus(
                          containers.size() + " container(s) · " + images.size() + " image(s)");
                      log.debug(
                          "Docker panel refreshed: {} containers, {} images",
                          containers.size(),
                          images.size());
                    });
              } catch (DockerException e) {
                log.error("Docker panel refresh failed: {}", e.getMessage(), e);
                Platform.runLater(() -> setStatus("Error: " + e.getMessage()));
              }
            });
  }

  private void populateContainers(List<ContainerInfo> containers) {
    long running =
        containers.stream().filter(c -> c.getStatus() == ContainerStatus.RUNNING).count();
    containersSection.setBadgeText(running + " running");

    VBox items = containersSection.getItemsBox();
    items.getChildren().clear();
    containers.forEach(c -> items.getChildren().add(new ContainerRow(c, this)));
  }

  private void populateImages(List<ImageInfo> images) {
    imagesSection.setCount(images.size());

    VBox items = imagesSection.getItemsBox();
    items.getChildren().clear();
    images.forEach(img -> items.getChildren().add(new ImageRow(img, this)));
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
