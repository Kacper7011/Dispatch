package dev.dispatch.ui.docker;

import dev.dispatch.docker.DockerService;
import dev.dispatch.docker.model.VolumeInfo;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** Compact row widget for a Docker volume — icon, name, driver, and hover remove button. */
class VolumeRow extends HBox {

  VolumeRow(VolumeInfo volume, DockerService service, DockerPanelController panel) {
    getStyleClass().add("docker-item-row");
    setAlignment(Pos.CENTER_LEFT);
    setPadding(new Insets(5, 12, 5, 14));
    setSpacing(8);
    setMaxWidth(Double.MAX_VALUE);

    Label icon = new Label("⊟");
    icon.getStyleClass().add("docker-item-icon");

    Label name = new Label(volume.getName());
    name.getStyleClass().add("docker-item-name");
    name.setMinWidth(0);
    name.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(name, Priority.ALWAYS);

    Label driver = new Label(volume.getDriver());
    driver.getStyleClass().add("docker-item-meta");

    Button removeBtn = new Button("✕");
    removeBtn.getStyleClass().addAll("docker-action-btn", "docker-action-remove");
    removeBtn.setVisible(false);
    removeBtn.setManaged(false);
    removeBtn.setOnAction(e -> panel.removeVolume(volume, service));

    getChildren().addAll(icon, name, driver, removeBtn);

    setOnMouseEntered(
        e -> {
          removeBtn.setVisible(true);
          removeBtn.setManaged(true);
          driver.setVisible(false);
          driver.setManaged(false);
        });
    setOnMouseExited(
        e -> {
          removeBtn.setVisible(false);
          removeBtn.setManaged(false);
          driver.setVisible(true);
          driver.setManaged(true);
        });
    setOnMouseClicked(e -> panel.selectRow(this));
  }
}
