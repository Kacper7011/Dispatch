package dev.dispatch.ui.docker;

import dev.dispatch.docker.model.ContainerInfo;
import dev.dispatch.docker.model.ContainerStatus;
import javafx.scene.control.Button;
import javafx.scene.control.TableCell;
import javafx.scene.layout.HBox;

/**
 * Table cell that renders Start / Stop / Remove action buttons for a container row.
 *
 * <p>Start is disabled when the container is already running; Stop is disabled when it is not.
 * Delegates all operations to {@link DockerPanelController} which runs them on a virtual thread.
 */
class ContainerRowController extends TableCell<ContainerInfo, ContainerInfo> {

  private final Button startBtn = new Button("Start");
  private final Button stopBtn = new Button("Stop");
  private final Button removeBtn = new Button("Remove");
  private final HBox buttons = new HBox(6, startBtn, stopBtn, removeBtn);

  ContainerRowController(DockerPanelController panel) {
    startBtn.setOnAction(e -> panel.startContainer(getItem()));
    stopBtn.setOnAction(e -> panel.stopContainer(getItem()));
    removeBtn.setOnAction(e -> panel.removeContainer(getItem()));
    removeBtn.getStyleClass().add("button-danger");
    buttons.setStyle("-fx-alignment: CENTER_LEFT; -fx-padding: 2 0 2 0;");
  }

  @Override
  protected void updateItem(ContainerInfo item, boolean empty) {
    super.updateItem(item, empty);
    if (empty || item == null) {
      setGraphic(null);
      return;
    }
    boolean running = item.getStatus() == ContainerStatus.RUNNING;
    startBtn.setDisable(running);
    stopBtn.setDisable(!running);
    setGraphic(buttons);
    setText(null);
  }
}
