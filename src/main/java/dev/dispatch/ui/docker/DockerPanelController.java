package dev.dispatch.ui.docker;

import dev.dispatch.docker.DockerException;
import dev.dispatch.docker.DockerService;
import dev.dispatch.docker.model.ContainerInfo;
import dev.dispatch.docker.model.ContainerStatus;
import dev.dispatch.docker.model.ImageInfo;
import dev.dispatch.docker.model.NetworkInfo;
import dev.dispatch.docker.model.VolumeInfo;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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

  // Snapshot of the last-loaded container list — used for proactive in-use checks.
  private List<ContainerInfo> currentContainers = List.of();

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
    containersSection.setPruneAction(this::pruneContainers);
    imagesSection.setPruneAction(this::pruneImages);
    networksSection.setPruneAction(this::pruneNetworks);
    volumesSection.setPruneAction(this::pruneVolumes);
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
    if (!confirm("Remove container", "Remove container \"" + c.getName() + "\"?\nThis cannot be undone.")) return;
    runContainerOp(() -> dockerService.removeContainer(c.getId()), "Removing " + c.getName(), c);
  }

  void removeImage(ImageInfo img) {
    String label = img.getPrimaryTag().equals("<none>") ? img.getShortId() : img.getPrimaryTag();
    String usingContainer = findContainerUsingImage(img);
    if (usingContainer != null) {
      showInUse("image \"" + label + "\"", "container \"" + usingContainer + "\"");
      return;
    }
    if (!confirm("Remove image", "Remove image \"" + label + "\"?\nThis cannot be undone.")) return;
    runDockerOp(() -> dockerService.removeImage(img.getId()), "Removing image " + label);
  }

  void removeNetwork(NetworkInfo n) {
    if (!confirm("Remove network", "Remove network \"" + n.getName() + "\"?\nThis cannot be undone.")) return;
    runDockerOp(() -> dockerService.removeNetwork(n.getId()), "Removing network " + n.getName());
  }

  void removeVolume(VolumeInfo v) {
    if (!confirm("Remove volume", "Remove volume \"" + v.getName() + "\"?\nThis cannot be undone.")) return;
    runDockerOp(() -> dockerService.removeVolume(v.getName()), "Removing volume " + v.getName());
  }

  // -------------------------------------------------------------------------
  // Package-private prune actions — called by DockerSection prune buttons
  // -------------------------------------------------------------------------

  private void pruneContainers() {
    if (!confirm("Prune containers", "Remove all stopped containers?\nThis cannot be undone.")) return;
    runDockerOpWithResult(dockerService::pruneContainers, "Pruning containers");
  }

  private void pruneImages() {
    if (!confirm("Prune images", "Remove all unused images (not referenced by any container)?\nThis cannot be undone.")) return;
    runDockerOpWithResult(dockerService::pruneImages, "Pruning images");
  }

  private void pruneNetworks() {
    if (!confirm("Prune networks", "Remove all unused networks?\nThis cannot be undone.")) return;
    runDockerOpWithResult(dockerService::pruneNetworks, "Pruning networks");
  }

  private void pruneVolumes() {
    if (!confirm("Prune volumes", "Remove all unused volumes?\nData will be permanently deleted.")) return;
    runDockerOpWithResult(dockerService::pruneVolumes, "Pruning volumes");
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
                List<NetworkInfo> networks = dockerService.listNetworks();
                List<VolumeInfo> volumes = dockerService.listVolumes();
                Platform.runLater(
                    () -> {
                      populateContainers(containers);
                      populateImages(images);
                      populateNetworks(networks);
                      populateVolumes(volumes);
                      setStatus(containers.size() + " containers · " + images.size() + " images");
                      log.debug(
                          "Docker panel refreshed: {} containers, {} images, {} networks, {} volumes",
                          containers.size(),
                          images.size(),
                          networks.size(),
                          volumes.size());
                    });
              } catch (DockerException e) {
                log.error("Docker panel refresh failed: {}", e.getMessage(), e);
                Platform.runLater(() -> setStatus("Error: " + e.getMessage()));
              }
            });
  }

  private void populateContainers(List<ContainerInfo> containers) {
    currentContainers = containers;
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

  private void populateNetworks(List<NetworkInfo> networks) {
    networksSection.setCount(networks.size());
    VBox items = networksSection.getItemsBox();
    items.getChildren().clear();
    networks.forEach(n -> items.getChildren().add(new NetworkRow(n, this)));
  }

  private void populateVolumes(List<VolumeInfo> volumes) {
    volumesSection.setCount(volumes.size());
    VBox items = volumesSection.getItemsBox();
    items.getChildren().clear();
    volumes.forEach(v -> items.getChildren().add(new VolumeRow(v, this)));
  }

  /**
   * Returns the name of the first container that references the given image, or {@code null} if
   * none. Matches by primary tag (e.g. {@code "nginx:latest"}).
   */
  private String findContainerUsingImage(ImageInfo img) {
    return currentContainers.stream()
        .filter(c -> c.getImage().equals(img.getPrimaryTag()))
        .map(ContainerInfo::getName)
        .findFirst()
        .orElse(null);
  }

  /**
   * Shows a blocking info dialog explaining that a resource cannot be removed because it is in use.
   * Must be called from the FX Application Thread.
   */
  private void showInUse(String resource, String usedBy) {
    Alert alert = new Alert(Alert.AlertType.INFORMATION);
    alert.setTitle("Cannot remove");
    alert.setHeaderText(null);
    alert.setContentText("Cannot remove " + resource + " — it is currently in use by " + usedBy + ".");
    alert.showAndWait();
  }

  /**
   * Shows a confirmation dialog on the FX thread and returns {@code true} if the user clicked OK.
   * Must be called from the FX Application Thread.
   */
  private boolean confirm(String title, String body) {
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.setContentText(body);
    Optional<ButtonType> result = alert.showAndWait();
    return result.isPresent() && result.get() == ButtonType.OK;
  }

  /**
   * Shows an error dialog explaining why the operation could not be completed.
   * Must be called from the FX Application Thread.
   */
  private void showError(String title, String message) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.setContentText(message);
    alert.showAndWait();
  }

  /**
   * Runs a generic Docker operation (image / network / volume) on a virtual thread and refreshes
   * the panel when done. Does not require a {@link ContainerInfo} — use for non-container ops.
   */
  /**
   * Runs a Docker operation that returns a summary string (used for prune commands). Refreshes the
   * panel and shows the summary in the status bar when done.
   */
  private void runDockerOpWithResult(java.util.function.Supplier<String> op, String description) {
    setStatus(description + "…");
    Thread.ofVirtual()
        .name("docker-op")
        .start(
            () -> {
              try {
                String result = op.get();
                log.info("{} — {}", description, result);
                Platform.runLater(() -> {
                  refresh();
                  setStatus(result);
                });
              } catch (Exception e) {
                log.error("{} failed: {}", description, e.getMessage(), e);
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                Platform.runLater(
                    () -> {
                      setStatus("Error: " + reason);
                      showError("Cannot complete operation", reason);
                    });
              }
            });
  }

  private void runDockerOp(Runnable op, String description) {
    setStatus(description + "…");
    Thread.ofVirtual()
        .name("docker-op")
        .start(
            () -> {
              try {
                op.run();
                log.info("{} — done", description);
                Platform.runLater(this::refresh);
              } catch (DockerException e) {
                log.error("{} failed: {}", description, e.getMessage(), e);
                String reason = extractReason(e);
                Platform.runLater(
                    () -> {
                      setStatus("Error: " + reason);
                      showError("Cannot complete operation", reason);
                    });
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
                String reason = extractReason(e);
                Platform.runLater(
                    () -> {
                      setStatus("Error: " + reason);
                      showError("Cannot complete operation", reason);
                    });
              }
            });
  }

  /**
   * Extracts a concise, user-readable reason from a {@link DockerException}.
   *
   * <p>docker-java wraps the Docker daemon's error as the cause exception whose message is the
   * plain-text daemon response (e.g. "remove nginx_volume: volume is in use - [abc123]"). We
   * prefer the cause message; if absent we fall back to the outer exception message.
   */
  private static String extractReason(DockerException e) {
    Throwable cause = e.getCause();
    if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
      return cause.getMessage();
    }
    String msg = e.getMessage();
    return msg != null ? msg : "Unknown error";
  }

  private void setStatus(String text) {
    statusLabel.setText(text);
  }
}
