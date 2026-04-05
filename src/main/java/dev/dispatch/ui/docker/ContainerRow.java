package dev.dispatch.ui.docker;

import dev.dispatch.docker.model.ContainerInfo;
import dev.dispatch.docker.model.ContainerStatus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Compact row widget for a single Docker container — status dot, name, and uptime.
 *
 * <p>On hover the uptime label is replaced by three action buttons: logs, exec, and stop/start.
 * Single click selects the row in the panel.
 */
class ContainerRow extends HBox {

  ContainerRow(ContainerInfo container, DockerPanelController panel) {
    getStyleClass().add("docker-item-row");
    setAlignment(Pos.CENTER_LEFT);
    setPadding(new Insets(5, 10, 5, 14));
    setSpacing(8);
    setMaxWidth(Double.MAX_VALUE);

    Region dot = buildDot(container.getStatus());

    Label name = new Label(container.getName());
    name.getStyleClass().add("docker-item-name");
    if (container.getStatus() != ContainerStatus.RUNNING) {
      name.getStyleClass().add("docker-item-name-dim");
    }
    name.setMinWidth(0);
    name.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(name, Priority.ALWAYS);

    Label uptime = new Label(formatUptime(container));
    uptime.getStyleClass().add("docker-item-meta");

    HBox actions = buildActions(container, panel);
    actions.setVisible(false);
    actions.setManaged(false);

    getChildren().addAll(dot, name, uptime, actions);

    setOnMouseEntered(
        e -> {
          uptime.setVisible(false);
          uptime.setManaged(false);
          actions.setVisible(true);
          actions.setManaged(true);
        });

    setOnMouseExited(
        e -> {
          uptime.setVisible(true);
          uptime.setManaged(true);
          actions.setVisible(false);
          actions.setManaged(false);
        });

    setOnMouseClicked(e -> panel.selectRow(this));
  }

  private static Region buildDot(ContainerStatus status) {
    Region dot = new Region();
    String colorClass =
        switch (status) {
          case RUNNING -> "docker-dot-running";
          case PAUSED, RESTARTING -> "docker-dot-warning";
          default -> "docker-dot-stopped";
        };
    dot.getStyleClass().addAll("docker-item-dot", colorClass);
    return dot;
  }

  private static HBox buildActions(ContainerInfo container, DockerPanelController panel) {
    boolean running = container.getStatus() == ContainerStatus.RUNNING;

    Button logsBtn = new Button("≡");
    logsBtn.getStyleClass().addAll("docker-action-btn", "docker-action-logs");
    logsBtn.setOnAction(e -> panel.streamLogs(container));

    Button execBtn = new Button(">_");
    execBtn.getStyleClass().addAll("docker-action-btn", "docker-action-exec");
    execBtn.setDisable(!running);
    execBtn.setOnAction(e -> panel.execContainer(container));

    Button toggleBtn = buildToggleButton(container, panel, running);

    Button removeBtn = new Button("✕");
    removeBtn.getStyleClass().addAll("docker-action-btn", "docker-action-remove");
    // Removing a running container is destructive — disable it; user must stop first.
    removeBtn.setDisable(running);
    removeBtn.setOnAction(e -> panel.removeContainer(container));

    HBox box = new HBox(4, logsBtn, execBtn, toggleBtn, removeBtn);
    box.setAlignment(Pos.CENTER_RIGHT);
    return box;
  }

  private static Button buildToggleButton(
      ContainerInfo container, DockerPanelController panel, boolean running) {
    Button btn = new Button(running ? "■" : "▶");
    btn.getStyleClass()
        .addAll("docker-action-btn", running ? "docker-action-stop" : "docker-action-start");
    btn.setOnAction(
        e -> {
          if (running) panel.stopContainer(container);
          else panel.startContainer(container);
        });
    return btn;
  }

  private static String formatUptime(ContainerInfo c) {
    if (c.getStatus() != ContainerStatus.RUNNING) return "stopped";
    String raw = c.getStatusText();
    if (raw == null || raw.isBlank()) return "—";
    return raw.replace("Up ", "")
        .replaceAll(" hours?", "h")
        .replaceAll(" minutes?", "m")
        .replaceAll(" seconds?", "s")
        .replaceAll(" days?", "d");
  }
}
