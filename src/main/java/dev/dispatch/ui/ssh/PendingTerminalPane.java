package dev.dispatch.ui.ssh;

import java.util.function.Consumer;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * A placeholder pane displayed in a pending (not yet connected) split slot.
 *
 * <p>Wraps the host-picker {@link BorderPane} built by {@link SshTabController} and adds the same
 * header bar as {@link TerminalPane} (split buttons always visible, close button hidden until a
 * second pane exists). Implements {@link PaneContent} via composition — no terminal is opened.
 */
public class PendingTerminalPane implements PaneContent {

  private final BorderPane root;
  private final Button closeBtn;

  /**
   * Creates a pending pane that wraps the provided host-picker content.
   *
   * @param pickerContent the host-picker UI built by {@link SshTabController}
   * @param onClose callback invoked when the user clicks the close button
   * @param onSplit callback invoked with the desired {@link Orientation} when a split button is
   *     clicked
   */
  public PendingTerminalPane(
      BorderPane pickerContent, Runnable onClose, Consumer<Orientation> onSplit) {
    closeBtn = new Button("×");
    closeBtn.getStyleClass().addAll("pane-header-btn", "pane-close-btn");
    closeBtn.setOnAction(e -> onClose.run());
    closeBtn.setVisible(false);
    closeBtn.setManaged(false);

    HBox header = buildHeader(onSplit, closeBtn);
    pickerContent.setTop(header);
    root = pickerContent;
  }

  /** {@inheritDoc} */
  @Override
  public Node getNode() {
    return root;
  }

  /** {@inheritDoc} Shows or hides only the close button; split buttons remain always visible. */
  @Override
  public void showCloseButton(boolean show) {
    closeBtn.setVisible(show);
    closeBtn.setManaged(show);
  }

  /** No-op — pending panes have no underlying terminal to dispose. */
  @Override
  public void dispose() {}

  // ── Header ────────────────────────────────────────────────────────────────

  private HBox buildHeader(Consumer<Orientation> onSplit, Button close) {
    Button splitH = new Button("↔");
    splitH.getStyleClass().addAll("pane-header-btn", "pane-split-btn");
    splitH.setOnAction(e -> onSplit.accept(Orientation.HORIZONTAL));

    Button splitV = new Button("↕");
    splitV.getStyleClass().addAll("pane-header-btn", "pane-split-btn");
    splitV.setOnAction(e -> onSplit.accept(Orientation.VERTICAL));

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);

    HBox bar = new HBox(4, splitH, splitV, spacer, close);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("pane-header");
    return bar;
  }
}
