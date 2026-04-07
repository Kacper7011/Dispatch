package dev.dispatch.ui.docker;

import dev.dispatch.docker.DockerException;
import dev.dispatch.docker.DockerService;
import dev.dispatch.docker.model.ContainerInfo;
import dev.dispatch.docker.model.ContainerStatus;
import dev.dispatch.docker.model.ImageInfo;
import dev.dispatch.docker.model.NetworkInfo;
import dev.dispatch.docker.model.VolumeInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Docker side-panel.
 *
 * <p>Manages multiple connected hosts simultaneously. Each section (containers, images, networks,
 * volumes) groups its rows under a collapsible host sub-header so the user can see at a glance
 * which resource belongs to which host. Hosts are added via {@link #addHost} when Docker is
 * detected on a session and removed via {@link #removeHost} when a session is closed.
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

  /**
   * Ordered map of host id → entry. LinkedHashMap preserves insertion order so hosts appear in
   * connection order.
   */
  private final Map<Long, HostEntry> hosts = new LinkedHashMap<>();

  /** Subset of hosts currently visible — set by MainController on tab switch. */
  private java.util.Set<Long> visibleHostIds = java.util.Set.of();

  private Runnable onCloseCallback;
  private BiConsumer<ContainerInfo, DockerService> onOpenLogs;
  private BiConsumer<ContainerInfo, DockerService> onOpenExec;
  private Node selectedRow;

  /** Represents one connected host's metadata and Docker service. */
  private record HostEntry(String name, String hostname, DockerService service) {}

  // ── Init ─────────────────────────────────────────────────────────────────────

  /** Called by FXML loader — wires sections into the scroll pane content. */
  @FXML
  private void initialize() {
    contentBox
        .getChildren()
        .addAll(containersSection, imagesSection, networksSection, volumesSection);
  }

  /** Called by MainController to wire the panel's close button back to the main layout. */
  public void setOnClose(Runnable callback) {
    this.onCloseCallback = callback;
    closePanelBtn.setOnAction(
        e -> {
          if (onCloseCallback != null) onCloseCallback.run();
        });
  }

  /** Wires the callback that opens a new log tab in the main layout. */
  public void setOnOpenLogs(BiConsumer<ContainerInfo, DockerService> callback) {
    this.onOpenLogs = callback;
  }

  /** Wires the callback that opens a new exec terminal tab in the main layout. */
  public void setOnOpenExec(BiConsumer<ContainerInfo, DockerService> callback) {
    this.onOpenExec = callback;
  }

  // ── Host lifecycle ────────────────────────────────────────────────────────────

  /**
   * Registers a host's Docker service. Does not trigger a refresh — call {@link
   * #setVisibleHosts(java.util.Set)} to update the view. Safe to call from any thread.
   *
   * @param hostId unique id of the SSH host
   * @param hostName display name (e.g. "homelab-01")
   * @param hostname address shown in the sub-header (e.g. "192.168.1.10")
   * @param service connected Docker service for this host
   */
  public void addHost(long hostId, String hostName, String hostname, DockerService service) {
    // Must be called on the FX thread — caller (MainController) is already on Platform.runLater
    hosts.put(hostId, new HostEntry(hostName, hostname, service));
    log.info("Docker panel: host registered — {} (id={})", hostName, hostId);
  }

  /**
   * Removes a host's Docker service registration. Safe to call from any thread.
   *
   * @param hostId the host id to remove
   */
  public void removeHost(long hostId) {
    // Must be called on the FX thread — caller (MainController) is already on the FX thread
    HostEntry removed = hosts.remove(hostId);
    if (removed != null) {
      log.info("Docker panel: host removed — {} (id={})", removed.name(), hostId);
    }
  }

  /**
   * Restricts the panel view to the given set of host ids and triggers a refresh. Pass an empty set
   * to show a "no hosts" state. Must be called on the FX thread.
   *
   * <p>Used by {@code MainController} whenever the selected SSH tab changes — so the panel always
   * reflects exactly the hosts visible in the current tab (one host = no grouping, multiple =
   * grouped by host).
   *
   * @param hostIds ids of hosts to display; hosts not in this set are hidden
   */
  public void setVisibleHosts(java.util.Set<Long> hostIds) {
    visibleHostIds = hostIds;
    refresh();
  }

  // ── FXML handlers ─────────────────────────────────────────────────────────────

  @FXML
  private void onRefresh() {
    refresh();
  }

  // ── Selection ─────────────────────────────────────────────────────────────────

  /** Transfers the visual selection highlight to the given row node. */
  void selectRow(Node row) {
    if (selectedRow != null) selectedRow.getStyleClass().remove("docker-item-selected");
    selectedRow = row;
    row.getStyleClass().add("docker-item-selected");
  }

  // ── Package-private actions — called by row widgets ───────────────────────────

  void streamLogs(ContainerInfo c, DockerService service) {
    if (onOpenLogs != null) onOpenLogs.accept(c, service);
    else log.warn("No logs callback registered for {}", c.getName());
  }

  void execContainer(ContainerInfo c, DockerService service) {
    if (onOpenExec != null) onOpenExec.accept(c, service);
    else log.warn("No exec callback registered for {}", c.getName());
  }

  void startContainer(ContainerInfo c, DockerService service) {
    runContainerOp(() -> service.startContainer(c.getId()), "Starting " + c.getName(), c);
  }

  void stopContainer(ContainerInfo c, DockerService service) {
    runContainerOp(() -> service.stopContainer(c.getId()), "Stopping " + c.getName(), c);
  }

  void removeContainer(ContainerInfo c, DockerService service) {
    if (!confirm(
        "Remove container", "Remove container \"" + c.getName() + "\"?\nThis cannot be undone."))
      return;
    runContainerOp(() -> service.removeContainer(c.getId()), "Removing " + c.getName(), c);
  }

  void removeImage(ImageInfo img, DockerService service) {
    String label = img.getPrimaryTag().equals("<none>") ? img.getShortId() : img.getPrimaryTag();
    if (!confirm("Remove image", "Remove image \"" + label + "\"?\nThis cannot be undone.")) return;
    runDockerOp(() -> service.removeImage(img.getId()), "Removing image " + label);
  }

  void removeNetwork(NetworkInfo n, DockerService service) {
    if (!confirm(
        "Remove network", "Remove network \"" + n.getName() + "\"?\nThis cannot be undone."))
      return;
    runDockerOp(() -> service.removeNetwork(n.getId()), "Removing network " + n.getName());
  }

  void removeVolume(VolumeInfo v, DockerService service) {
    if (!confirm("Remove volume", "Remove volume \"" + v.getName() + "\"?\nThis cannot be undone."))
      return;
    runDockerOp(() -> service.removeVolume(v.getName()), "Removing volume " + v.getName());
  }

  // ── Refresh ───────────────────────────────────────────────────────────────────

  private void refresh() {
    setStatus("Loading…");
    // Filter to only the hosts visible in the current tab, then snapshot
    Map<Long, HostEntry> snapshot = new LinkedHashMap<>();
    for (Map.Entry<Long, HostEntry> e : hosts.entrySet()) {
      if (visibleHostIds.contains(e.getKey())) snapshot.put(e.getKey(), e.getValue());
    }
    if (snapshot.isEmpty()) {
      clearAllSections();
      setStatus("No hosts connected");
      return;
    }
    Thread.ofVirtual().name("docker-refresh-all").start(() -> fetchAndPopulate(snapshot));
  }

  private void fetchAndPopulate(Map<Long, HostEntry> snapshot) {
    // Fetch data for all hosts; collect results then update UI once
    Map<Long, HostData> results = new LinkedHashMap<>();
    int totalContainers = 0;
    int totalImages = 0;

    for (Map.Entry<Long, HostEntry> e : snapshot.entrySet()) {
      long hostId = e.getKey();
      HostEntry entry = e.getValue();
      try {
        List<ContainerInfo> containers = entry.service().listContainers();
        List<ImageInfo> images = entry.service().listImages();
        List<NetworkInfo> networks = entry.service().listNetworks();
        List<VolumeInfo> volumes = entry.service().listVolumes();
        results.put(hostId, new HostData(entry, containers, images, networks, volumes));
        totalContainers += containers.size();
        totalImages += images.size();
        log.debug(
            "Fetched Docker data from {}: {} containers, {} images",
            entry.name(),
            containers.size(),
            images.size());
      } catch (DockerException ex) {
        log.error("Docker refresh failed for {}: {}", entry.name(), ex.getMessage(), ex);
        results.put(hostId, new HostData(entry, List.of(), List.of(), List.of(), List.of()));
      }
    }

    int finalContainers = totalContainers;
    int finalImages = totalImages;
    Platform.runLater(
        () -> {
          populateAllSections(results);
          setStatus(finalContainers + " containers · " + finalImages + " images");
        });
  }

  private void populateAllSections(Map<Long, HostData> results) {
    boolean multiHost = results.size() > 1;

    clearAllSections();

    long totalRunning = 0;
    int totalImages = 0;
    int totalNetworks = 0;
    int totalVolumes = 0;

    for (HostData data : results.values()) {
      long running =
          data.containers().stream().filter(c -> c.getStatus() == ContainerStatus.RUNNING).count();
      totalRunning += running;
      totalImages += data.images().size();
      totalNetworks += data.networks().size();
      totalVolumes += data.volumes().size();

      VBox containerItems = containersSection.getItemsBox();
      VBox imageItems = imagesSection.getItemsBox();
      VBox networkItems = networksSection.getItemsBox();
      VBox volumeItems = volumesSection.getItemsBox();

      if (multiHost) {
        HostGroup cg =
            buildCollapsibleHostGroup(data.entry(), () -> pruneContainers(data.entry().service()));
        HostGroup ig =
            buildCollapsibleHostGroup(data.entry(), () -> pruneImages(data.entry().service()));
        HostGroup ng =
            buildCollapsibleHostGroup(data.entry(), () -> pruneNetworks(data.entry().service()));
        HostGroup vg =
            buildCollapsibleHostGroup(data.entry(), () -> pruneVolumes(data.entry().service()));

        containerItems.getChildren().add(cg.group());
        imageItems.getChildren().add(ig.group());
        networkItems.getChildren().add(ng.group());
        volumeItems.getChildren().add(vg.group());

        data.containers()
            .forEach(
                c ->
                    cg.items()
                        .getChildren()
                        .add(new ContainerRow(c, data.entry().service(), this)));
        data.images()
            .forEach(
                img ->
                    ig.items().getChildren().add(new ImageRow(img, data.entry().service(), this)));
        data.networks()
            .forEach(
                n -> ng.items().getChildren().add(new NetworkRow(n, data.entry().service(), this)));
        data.volumes()
            .forEach(
                v -> vg.items().getChildren().add(new VolumeRow(v, data.entry().service(), this)));
      } else {
        data.containers()
            .forEach(
                c ->
                    containerItems
                        .getChildren()
                        .add(new ContainerRow(c, data.entry().service(), this)));
        data.images()
            .forEach(
                img ->
                    imageItems.getChildren().add(new ImageRow(img, data.entry().service(), this)));
        data.networks()
            .forEach(
                n ->
                    networkItems
                        .getChildren()
                        .add(new NetworkRow(n, data.entry().service(), this)));
        data.volumes()
            .forEach(
                v -> volumeItems.getChildren().add(new VolumeRow(v, data.entry().service(), this)));
      }
    }

    // Single-host: keep prune buttons on section headers
    if (!multiHost) {
      results.values().stream()
          .findFirst()
          .ifPresent(
              data -> {
                containersSection.setPruneAction(() -> pruneContainers(data.entry().service()));
                imagesSection.setPruneAction(() -> pruneImages(data.entry().service()));
                networksSection.setPruneAction(() -> pruneNetworks(data.entry().service()));
                volumesSection.setPruneAction(() -> pruneVolumes(data.entry().service()));
              });
    } else {
      containersSection.clearPruneAction();
      imagesSection.clearPruneAction();
      networksSection.clearPruneAction();
      volumesSection.clearPruneAction();
    }

    containersSection.setBadgeText(totalRunning + " running");
    imagesSection.setCount(totalImages);
    networksSection.setCount(totalNetworks);
    volumesSection.setCount(totalVolumes);
  }

  private void clearAllSections() {
    containersSection.getItemsBox().getChildren().clear();
    imagesSection.getItemsBox().getChildren().clear();
    networksSection.getItemsBox().getChildren().clear();
    volumesSection.getItemsBox().getChildren().clear();
  }

  // ── Host sub-header ───────────────────────────────────────────────────────────

  /** Groups the host header row together with its collapsible items VBox. */
  private record HostGroup(VBox group, VBox items) {}

  /**
   * Builds a collapsible host group (header + items VBox). Clicking the header arrow toggles
   * visibility of the items. Shown only when multiple hosts are connected.
   */
  private HostGroup buildCollapsibleHostGroup(HostEntry entry, Runnable pruneAction) {
    Label arrow = new Label("▾");
    arrow.getStyleClass().add("docker-host-arrow");

    Label name = new Label(entry.name());
    name.getStyleClass().add("docker-host-name");

    Label addr = new Label(entry.hostname());
    addr.getStyleClass().add("docker-host-addr");

    Button pruneBtn = new Button("Prune");
    pruneBtn.getStyleClass().add("docker-section-prune-btn");
    pruneBtn.setOnAction(e -> pruneAction.run());

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);

    HBox header = new HBox(6, arrow, name, addr, spacer, pruneBtn);
    header.setAlignment(Pos.CENTER_LEFT);
    header.getStyleClass().add("docker-host-header");
    header.setPadding(new Insets(5, 10, 5, 8));

    VBox items = new VBox();

    // Toggle collapse when clicking the header but not the prune button
    header.setOnMouseClicked(
        e -> {
          if (!pruneBtn.isHover()) {
            boolean nowCollapsed = items.isManaged();
            items.setVisible(!nowCollapsed);
            items.setManaged(!nowCollapsed);
            arrow.setText(nowCollapsed ? "▸" : "▾");
          }
        });

    VBox group = new VBox(header, items);
    return new HostGroup(group, items);
  }

  // ── Prune actions ─────────────────────────────────────────────────────────────

  private void pruneContainers(DockerService service) {
    if (!confirm("Prune containers", "Remove all stopped containers?\nThis cannot be undone."))
      return;
    runDockerOpWithResult(service::pruneContainers, "Pruning containers");
  }

  private void pruneImages(DockerService service) {
    if (!confirm(
        "Prune images",
        "Remove all unused images (not referenced by any container)?\nThis cannot be undone."))
      return;
    runDockerOpWithResult(service::pruneImages, "Pruning images");
  }

  private void pruneNetworks(DockerService service) {
    if (!confirm("Prune networks", "Remove all unused networks?\nThis cannot be undone.")) return;
    runDockerOpWithResult(service::pruneNetworks, "Pruning networks");
  }

  private void pruneVolumes(DockerService service) {
    if (!confirm("Prune volumes", "Remove all unused volumes?\nData will be permanently deleted."))
      return;
    runDockerOpWithResult(service::pruneVolumes, "Pruning volumes");
  }

  // ── Operation helpers ─────────────────────────────────────────────────────────

  private void runDockerOpWithResult(java.util.function.Supplier<String> op, String description) {
    setStatus(description + "…");
    Thread.ofVirtual()
        .name("docker-op")
        .start(
            () -> {
              try {
                String result = op.get();
                log.info("{} — {}", description, result);
                Platform.runLater(
                    () -> {
                      refresh();
                      setStatus(result);
                    });
              } catch (Exception e) {
                log.error("{} failed: {}", description, e.getMessage(), e);
                String reason =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
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

  // ── UI helpers ────────────────────────────────────────────────────────────────

  private boolean confirm(String title, String body) {
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.setContentText(body);
    Optional<ButtonType> result = alert.showAndWait();
    return result.isPresent() && result.get() == ButtonType.OK;
  }

  private void showError(String title, String message) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.setContentText(message);
    alert.showAndWait();
  }

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

  // ── Internal data holder ──────────────────────────────────────────────────────

  private record HostData(
      HostEntry entry,
      List<ContainerInfo> containers,
      List<ImageInfo> images,
      List<NetworkInfo> networks,
      List<VolumeInfo> volumes) {}
}
