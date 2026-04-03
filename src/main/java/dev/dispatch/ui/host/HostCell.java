package dev.dispatch.ui.host;

import dev.dispatch.core.model.Host;
import dev.dispatch.ssh.SessionState;
import java.util.function.Function;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Custom list cell rendering a host as: [status dot] [name] / [hostname:port]. Built
 * programmatically — no FXML needed for a cell this simple.
 */
public class HostCell extends ListCell<Host> {

  private static final Color COLOR_DISCONNECTED = Color.web("#565575");
  private static final Color COLOR_CONNECTED = Color.web("#95ffa4");
  private static final Color COLOR_LOST = Color.web("#ff8080");

  private final Function<Long, SessionState> stateProvider;
  private final HBox container;
  private final Circle statusDot;
  private final Label nameLabel;
  private final Label addressLabel;

  /** @param stateProvider maps host ID → current session state, queried on every cell refresh */
  public HostCell(Function<Long, SessionState> stateProvider) {
    this.stateProvider = stateProvider;

    statusDot = new Circle(5);

    nameLabel = new Label();
    nameLabel.getStyleClass().add("host-cell-name");

    addressLabel = new Label();
    addressLabel.getStyleClass().add("host-cell-address");

    HBox.setMargin(statusDot, new javafx.geometry.Insets(0, 0, 0, 6));

    VBox textBox = new VBox(2, nameLabel, addressLabel);
    container = new HBox(10, statusDot, textBox);
    container.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
    container.getStyleClass().add("host-cell-container");

    setPadding(new javafx.geometry.Insets(6, 8, 6, 16));
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
    statusDot.setFill(stateToColor(stateProvider.apply(host.getId())));
    setGraphic(container);
  }

  private Color stateToColor(SessionState state) {
    return switch (state) {
      case CONNECTED -> COLOR_CONNECTED;
      case LOST -> COLOR_LOST;
      default -> COLOR_DISCONNECTED;
    };
  }
}
