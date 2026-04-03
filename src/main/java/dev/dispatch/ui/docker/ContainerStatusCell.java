package dev.dispatch.ui.docker;

import dev.dispatch.docker.model.ContainerInfo;
import dev.dispatch.docker.model.ContainerStatus;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;

/**
 * Table cell that renders a coloured status badge for a container row.
 *
 * <p>Uses Challenger Deep CSS classes: {@code badge-success} for running containers, {@code
 * badge-warning} for paused/restarting, {@code badge-error} for all other states.
 */
class ContainerStatusCell extends TableCell<ContainerInfo, ContainerInfo> {

  private final Label badge = new Label();

  ContainerStatusCell() {
    setStyle("-fx-alignment: CENTER_LEFT; -fx-padding: 4 0 4 0;");
  }

  @Override
  protected void updateItem(ContainerInfo item, boolean empty) {
    super.updateItem(item, empty);
    if (empty || item == null) {
      setGraphic(null);
      return;
    }
    badge.setText(item.getStatusText());
    badge.getStyleClass().setAll(resolveBadgeClass(item.getStatus()));
    setGraphic(badge);
    setText(null);
  }

  private static String resolveBadgeClass(ContainerStatus status) {
    return switch (status) {
      case RUNNING -> "badge-success";
      case PAUSED, RESTARTING -> "badge-warning";
      default -> "badge-error";
    };
  }
}
