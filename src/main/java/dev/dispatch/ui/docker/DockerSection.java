package dev.dispatch.ui.docker;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Collapsible section widget used in the Docker panel tree.
 *
 * <p>Each section renders a clickable header row (arrow + title + count badge) above a content
 * VBox. Clicking the header toggles the collapsed state.
 */
class DockerSection extends VBox {

  private final Label arrow = new Label("▾");
  private final Label countBadge = new Label("—");
  private final VBox itemsBox = new VBox();
  private boolean collapsed = false;

  DockerSection(String title) {
    getStyleClass().add("docker-section");

    arrow.getStyleClass().add("docker-section-arrow");

    Label titleLabel = new Label(title);
    titleLabel.getStyleClass().add("docker-section-title");

    countBadge.getStyleClass().add("docker-section-badge");

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);

    HBox header = new HBox(6, arrow, titleLabel, spacer, countBadge);
    header.getStyleClass().add("docker-section-header");
    header.setAlignment(Pos.CENTER_LEFT);
    header.setPadding(new Insets(7, 10, 7, 10));
    header.setOnMouseClicked(e -> toggleCollapse());

    itemsBox.getStyleClass().add("docker-section-items");

    getChildren().addAll(header, itemsBox);
  }

  /** Returns the VBox into which item rows should be added. */
  VBox getItemsBox() {
    return itemsBox;
  }

  /** Updates the count badge with a plain number. */
  void setCount(int count) {
    countBadge.setText(String.valueOf(count));
  }

  /** Updates the count badge with an arbitrary string (e.g. "9 running"). */
  void setBadgeText(String text) {
    countBadge.setText(text);
  }

  private void toggleCollapse() {
    collapsed = !collapsed;
    itemsBox.setVisible(!collapsed);
    itemsBox.setManaged(!collapsed);
    arrow.setText(collapsed ? "▸" : "▾");
  }
}
