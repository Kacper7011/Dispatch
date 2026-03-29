package dev.dispatch.ui.host;

import dev.dispatch.core.model.Host;
import dev.dispatch.ssh.SessionState;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Custom list cell rendering a host as: [status dot] [name] / [hostname:port].
 * Built programmatically — no FXML needed for a cell this simple.
 */
public class HostCell extends ListCell<Host> {

  private static final Color COLOR_DISCONNECTED = Color.web("#565575");
  private static final Color COLOR_CONNECTED = Color.web("#95ffa4");
  private static final Color COLOR_LOST = Color.web("#ff8080");

  private final HBox container;
  private final Circle statusDot;
  private final Label nameLabel;
  private final Label addressLabel;

  public HostCell() {
    statusDot = new Circle(5);

    nameLabel = new Label();
    nameLabel.getStyleClass().add("host-cell-name");

    addressLabel = new Label();
    addressLabel.getStyleClass().add("host-cell-address");

    VBox textBox = new VBox(2, nameLabel, addressLabel);
    container = new HBox(10, statusDot, textBox);
    container.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
    container.getStyleClass().add("host-cell-container");

    setPadding(new javafx.geometry.Insets(6, 8, 6, 8));
  }

  @Override
  protected void updateItem(Host host, boolean empty) {
    super.updateItem(host, empty);
    if (empty || host == null) {
      setGraphic(null);
      return;
    }
    nameLabel.setText(host.getName());
    addressLabel.setText(host.getHostname() + ":" + host.getPort());
    statusDot.setFill(COLOR_DISCONNECTED);
    setGraphic(container);
  }

  /** Updates the status dot colour to reflect the current session state. */
  public void updateState(SessionState state) {
    Color color =
        switch (state) {
          case CONNECTED -> COLOR_CONNECTED;
          case LOST -> COLOR_LOST;
          default -> COLOR_DISCONNECTED;
        };
    statusDot.setFill(color);
  }
}
