package dev.dispatch.ui.docker;

import dev.dispatch.docker.model.ImageInfo;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** Compact row widget for a Docker image — icon, repository name, and tag. */
class ImageRow extends HBox {

  ImageRow(ImageInfo image, DockerPanelController panel) {
    getStyleClass().add("docker-item-row");
    setAlignment(Pos.CENTER_LEFT);
    setPadding(new Insets(5, 12, 5, 14));
    setSpacing(8);

    Label icon = new Label("▤");
    icon.getStyleClass().add("docker-item-icon");

    String[] parts = splitTag(image.getPrimaryTag());

    Label name = new Label(parts[0]);
    name.getStyleClass().add("docker-item-name");
    name.setMinWidth(0);
    name.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(name, Priority.ALWAYS);

    Label tag = new Label(parts[1]);
    tag.getStyleClass().add("docker-item-meta");

    getChildren().addAll(icon, name, tag);

    setOnMouseClicked(e -> panel.selectRow(this));
  }

  /**
   * Splits "repo/name:tag" into ["repo/name", "tag"]. Returns ["&lt;none&gt;", ""] for blank input.
   */
  private static String[] splitTag(String primaryTag) {
    if (primaryTag == null || primaryTag.isBlank()) {
      return new String[] {"<none>", ""};
    }
    int colon = primaryTag.lastIndexOf(':');
    if (colon > 0) {
      return new String[] {primaryTag.substring(0, colon), primaryTag.substring(colon + 1)};
    }
    return new String[] {primaryTag, "latest"};
  }
}
