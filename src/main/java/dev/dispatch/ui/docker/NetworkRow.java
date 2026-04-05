package dev.dispatch.ui.docker;

import dev.dispatch.docker.model.NetworkInfo;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** Compact row widget for a Docker network — icon, name, and driver. */
class NetworkRow extends HBox {

  NetworkRow(NetworkInfo network, DockerPanelController panel) {
    getStyleClass().add("docker-item-row");
    setAlignment(Pos.CENTER_LEFT);
    setPadding(new Insets(5, 12, 5, 14));
    setSpacing(8);
    setMaxWidth(Double.MAX_VALUE);

    Label icon = new Label("⊕");
    icon.getStyleClass().add("docker-item-icon");

    Label name = new Label(network.getName());
    name.getStyleClass().add("docker-item-name");
    name.setMinWidth(0);
    name.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(name, Priority.ALWAYS);

    Label driver = new Label(network.getDriver());
    driver.getStyleClass().add("docker-item-meta");

    getChildren().addAll(icon, name, driver);

    setOnMouseClicked(e -> panel.selectRow(this));
  }
}
