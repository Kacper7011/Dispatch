package dev.dispatch.ui.docker;

import dev.dispatch.docker.model.ContainerInfo;
import dev.dispatch.docker.model.ContainerStatus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Custom list cell rendering a Docker container as a card with status badge, uptime,
 * thin CPU/MEM progress bars, and action buttons.
 *
 * <p>Built programmatically to keep full control over layout and update logic.
 */
class ContainerCard extends ListCell<ContainerInfo> {

  private final Label nameLabel = new Label();
  private final Label statusBadge = new Label();
  private final Label uptimeLabel = new Label();
  private final Label imageLabel = new Label();
  private final ProgressBar cpuBar = new ProgressBar(0);
  private final Label cpuValueLabel = new Label("0.0%");
  private final ProgressBar memBar = new ProgressBar(0);
  private final Label memValueLabel = new Label("—");
  private final Button primaryBtn = new Button("stop");
  private final Button logsBtn = new Button("logs");
  private final Button execBtn = new Button("exec");
  private final HBox cpuRow;
  private final HBox memRow;
  private final VBox card;

  ContainerCard(DockerPanelController panel) {
    nameLabel.getStyleClass().add("container-card-name");
    uptimeLabel.getStyleClass().add("container-card-meta");
    imageLabel.getStyleClass().add("container-card-meta");
    cpuValueLabel.getStyleClass().add("container-bar-label");
    memValueLabel.getStyleClass().add("container-bar-label");
    cpuBar.getStyleClass().add("container-cpu-bar");
    memBar.getStyleClass().add("container-mem-bar");
    logsBtn.getStyleClass().addAll("button", "button-logs");
    execBtn.getStyleClass().add("button");
    execBtn.setDisable(true);

    // Bars grow up to a cap so they stay compact and don't fill the whole panel
    cpuBar.setMaxWidth(110);
    memBar.setMaxWidth(110);
    HBox.setHgrow(cpuBar, Priority.ALWAYS);
    HBox.setHgrow(memBar, Priority.ALWAYS);

    primaryBtn.setOnAction(e -> {
      ContainerInfo item = getItem();
      if (item == null) return;
      if (item.getStatus() == ContainerStatus.RUNNING) {
        panel.stopContainer(item);
      } else {
        panel.startContainer(item);
      }
    });
    logsBtn.setOnAction(e -> {
      if (getItem() != null) panel.streamLogs(getItem());
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox nameRow = new HBox(8, nameLabel, spacer, statusBadge);
    nameRow.setAlignment(Pos.CENTER_LEFT);

    // imageLabel shrinks/truncates when panel is narrow
    imageLabel.setMinWidth(0);
    imageLabel.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(imageLabel, Priority.ALWAYS);

    HBox metaRow = new HBox(10, uptimeLabel, imageLabel);
    metaRow.setAlignment(Pos.CENTER_LEFT);

    cpuRow = buildBarRow("CPU", cpuBar, cpuValueLabel);
    memRow = buildBarRow("MEM", memBar, memValueLabel);

    HBox actionRow = new HBox(5, primaryBtn, logsBtn, execBtn);
    actionRow.setAlignment(Pos.CENTER_LEFT);
    actionRow.getStyleClass().add("container-action-row");

    card = new VBox(6, nameRow, metaRow, cpuRow, memRow, actionRow);
    card.getStyleClass().add("container-card");
    card.setPadding(new Insets(10, 14, 10, 14));

    setPadding(Insets.EMPTY);
    // Bind card width to list cell so content reflows when the panel is resized
    card.prefWidthProperty().bind(widthProperty().subtract(2));
  }

  @Override
  protected void updateItem(ContainerInfo item, boolean empty) {
    super.updateItem(item, empty);
    if (empty || item == null) {
      setGraphic(null);
      setText(null);
      return;
    }

    nameLabel.setText(item.getName());
    uptimeLabel.setText(item.getStatusText());
    imageLabel.setText("img " + item.getImage());

    boolean running = item.getStatus() == ContainerStatus.RUNNING;

    statusBadge.getStyleClass().setAll(resolveBadgeClass(item.getStatus()));
    statusBadge.setText(resolveStatusText(item.getStatus()));

    if (running) {
      primaryBtn.setText("stop");
      primaryBtn.getStyleClass().setAll("button-danger");
    } else {
      primaryBtn.setText("start");
      primaryBtn.getStyleClass().setAll("button-start");
    }

    // CPU/MEM bars are only meaningful for running containers
    setCpuMemVisible(running);

    setGraphic(card);
    setText(null);
  }

  private void setCpuMemVisible(boolean visible) {
    cpuRow.setVisible(visible);
    cpuRow.setManaged(visible);
    memRow.setVisible(visible);
    memRow.setManaged(visible);
  }

  private static HBox buildBarRow(String typeText, ProgressBar bar, Label valueLabel) {
    Label typeLabel = new Label(typeText);
    typeLabel.getStyleClass().add("container-bar-type");
    typeLabel.setMinWidth(28);
    HBox row = new HBox(6, typeLabel, bar, valueLabel);
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }

  private static String resolveBadgeClass(ContainerStatus status) {
    return switch (status) {
      case RUNNING -> "badge-success";
      case PAUSED, RESTARTING -> "badge-warning";
      default -> "badge-error";
    };
  }

  private static String resolveStatusText(ContainerStatus status) {
    return switch (status) {
      case RUNNING -> "running";
      case EXITED -> "stopped";
      case PAUSED -> "paused";
      case RESTARTING -> "restarting";
      case CREATED -> "created";
      case DEAD -> "dead";
      default -> "unknown";
    };
  }
}
