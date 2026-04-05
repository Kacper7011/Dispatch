package dev.dispatch.ui.docker;

import dev.dispatch.docker.model.ContainerInfo;
import dev.dispatch.docker.model.ContainerStatus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Compact row widget for a single Docker container — status dot, name, and uptime.
 *
 * <p>Single click selects the row; double-click opens the log tab via the panel callback.
 */
class ContainerRow extends HBox {

  ContainerRow(ContainerInfo container, DockerPanelController panel) {
    getStyleClass().add("docker-item-row");
    setAlignment(Pos.CENTER_LEFT);
    setPadding(new Insets(5, 12, 5, 14));
    setSpacing(8);

    Region dot = new Region();
    dot.getStyleClass().addAll("docker-item-dot", dotClass(container.getStatus()));

    Label name = new Label(container.getName());
    name.getStyleClass().add("docker-item-name");
    if (container.getStatus() != ContainerStatus.RUNNING) {
      name.getStyleClass().add("docker-item-name-dim");
    }
    HBox.setHgrow(name, Priority.ALWAYS);
    name.setMaxWidth(Double.MAX_VALUE);

    Label uptime = new Label(formatUptime(container));
    uptime.getStyleClass().add("docker-item-meta");

    getChildren().addAll(dot, name, uptime);

    setOnMouseClicked(
        e -> {
          panel.selectRow(this);
          if (e.getClickCount() == 2 && container.getStatus() == ContainerStatus.RUNNING) {
            panel.streamLogs(container);
          }
        });
  }

  private static String dotClass(ContainerStatus status) {
    return switch (status) {
      case RUNNING -> "docker-dot-running";
      case PAUSED, RESTARTING -> "docker-dot-warning";
      default -> "docker-dot-stopped";
    };
  }

  private static String formatUptime(ContainerInfo c) {
    if (c.getStatus() != ContainerStatus.RUNNING) return "stopped";
    String raw = c.getStatusText();
    if (raw == null || raw.isBlank()) return "—";
    // "Up 22 hours" → "22h", "Up 2 minutes" → "2m", "Up 3 days" → "3d"
    return raw.replace("Up ", "")
        .replaceAll(" hours?", "h")
        .replaceAll(" minutes?", "m")
        .replaceAll(" seconds?", "s")
        .replaceAll(" days?", "d");
  }
}
