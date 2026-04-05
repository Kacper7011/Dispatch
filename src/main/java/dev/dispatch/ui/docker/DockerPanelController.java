package dev.dispatch.ui.docker;

import dev.dispatch.docker.DockerException;
import dev.dispatch.docker.DockerService;
import dev.dispatch.docker.model.ContainerInfo;
import dev.dispatch.docker.model.ContainerStatus;
import java.util.List;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Docker panel — shows all containers as cards and provides per-container
 * actions (start / stop / remove / logs).
 *
 * <p>All Docker operations run on virtual threads; UI updates are posted via {@code
 * Platform.runLater()}.
 */
public class DockerPanelController {

  private static final Logger log = LoggerFactory.getLogger(DockerPanelController.class);

  @FXML private ListView<ContainerInfo> containerList;
  @FXML private Label statRunningCount;
  @FXML private Label statStoppedCount;
  @FXML private Label statImagesCount;
  @FXML private Label statusLabel;
  @FXML private Button closePanelBtn;

  private DockerService dockerService;
  private Runnable onCloseCallback;
  private Consumer<ContainerInfo> onOpenLogs;
  private Consumer<ContainerInfo> onOpenExec;

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

  /** Injects the connected {@link DockerService} and loads the initial container list. */
  public void init(DockerService dockerService) {
    this.dockerService = dockerService;
    containerList.setCellFactory(lv -> new ContainerCard(this));
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
  // Package-private actions — called by ContainerCard
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

  /** Opens a live log tab for the given container in the main layout. */
  void streamLogs(ContainerInfo c) {
    if (onOpenLogs != null) {
      onOpenLogs.accept(c);
    } else {
      log.warn("No logs callback registered for {}", c.getName());
    }
  }

  /** Opens an interactive exec terminal tab for the given container in the main layout. */
  void execContainer(ContainerInfo c) {
    if (onOpenExec != null) {
      onOpenExec.accept(c);
    } else {
      log.warn("No exec callback registered for {}", c.getName());
    }
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void refresh() {
    setStatus("Loading…");
    Thread.ofVirtual()
        .name("docker-list-containers")
        .start(
            () -> {
              try {
                List<ContainerInfo> containers = dockerService.listContainers();
                int imageCount = fetchImageCount();
                Platform.runLater(
                    () -> {
                      containerList.getItems().setAll(containers);
                      updateStats(containers, imageCount);
                      setStatus(containers.size() + " container(s)");
                      log.debug("Container list refreshed: {} items", containers.size());
                    });
              } catch (DockerException e) {
                log.error("Failed to list containers: {}", e.getMessage(), e);
                Platform.runLater(() -> setStatus("Error: " + e.getMessage()));
              }
            });
  }

  private int fetchImageCount() {
    try {
      return dockerService.listImages().size();
    } catch (DockerException e) {
      log.warn("Could not fetch image count: {}", e.getMessage());
      return -1;
    }
  }

  private void updateStats(List<ContainerInfo> containers, int imageCount) {
    long running =
        containers.stream().filter(c -> c.getStatus() == ContainerStatus.RUNNING).count();
    long stopped =
        containers.stream().filter(c -> c.getStatus() != ContainerStatus.RUNNING).count();
    statRunningCount.setText(String.valueOf(running));
    statStoppedCount.setText(String.valueOf(stopped));
    statImagesCount.setText(imageCount >= 0 ? String.valueOf(imageCount) : "—");
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
