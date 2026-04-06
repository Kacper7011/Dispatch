package dev.dispatch.ui.docker;

import dev.dispatch.docker.DockerService;
import dev.dispatch.docker.model.ImageInfo;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** Compact row widget for a Docker image — icon, repository name, tag, and hover remove button. */
class ImageRow extends HBox {

  ImageRow(ImageInfo image, DockerService service, DockerPanelController panel) {
    getStyleClass().add("docker-item-row");
    setAlignment(Pos.CENTER_LEFT);
    setPadding(new Insets(5, 12, 5, 14));
    setSpacing(8);
    setMaxWidth(Double.MAX_VALUE);

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

    Button removeBtn = new Button("✕");
    removeBtn.getStyleClass().addAll("docker-action-btn", "docker-action-remove");
    removeBtn.setVisible(false);
    removeBtn.setManaged(false);
    removeBtn.setOnAction(e -> panel.removeImage(image, service));

    getChildren().addAll(icon, name, tag, removeBtn);

    setOnMouseEntered(
        e -> {
          removeBtn.setVisible(true);
          removeBtn.setManaged(true);
          tag.setVisible(false);
          tag.setManaged(false);
        });
    setOnMouseExited(
        e -> {
          removeBtn.setVisible(false);
          removeBtn.setManaged(false);
          tag.setVisible(true);
          tag.setManaged(true);
        });
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
