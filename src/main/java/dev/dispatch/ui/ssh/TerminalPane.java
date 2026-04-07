package dev.dispatch.ui.ssh;

import dev.dispatch.ssh.SshSession;
import dev.dispatch.ssh.terminal.TerminalController;
import java.util.function.Consumer;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * A single connected terminal pane inside a split SSH tab.
 *
 * <p>Wraps a {@link TerminalController} with a header bar that contains a drag handle, per-pane
 * split buttons (↔ ↕), and a close button (×). The header is always visible. The close button is
 * hidden when this is the sole pane and shown once a second pane exists. Must be created and used
 * on the FX Application Thread.
 */
public class TerminalPane implements PaneContent {

  private final TerminalController terminalController;
  private final BorderPane root;
  private final Button closeBtn;
  private final Label dragHandle;

  /**
   * Creates the pane and immediately opens the terminal node.
   *
   * @param session the active SSH session — a new shell channel is opened for this pane
   * @param onClose callback invoked when the user clicks the close button
   * @param onSplit callback invoked with the desired {@link Orientation} when a split button is
   *     clicked; the layout manager responds by inserting a new pane next to this one
   */
  public TerminalPane(SshSession session, Runnable onClose, Consumer<Orientation> onSplit) {
    terminalController = new TerminalController(session);
    Node terminalNode = terminalController.createNode();

    dragHandle = new Label("⠿");
    dragHandle.getStyleClass().add("pane-drag-handle");

    closeBtn = new Button("×");
    closeBtn.getStyleClass().addAll("pane-header-btn", "pane-close-btn");
    closeBtn.setOnAction(e -> onClose.run());
    // Hidden until a second pane exists — no point closing the only pane.
    closeBtn.setVisible(false);
    closeBtn.setManaged(false);

    HBox header = buildHeader(dragHandle, onSplit, closeBtn);

    root = new BorderPane(terminalNode);
    root.setTop(header);
  }

  /** {@inheritDoc} */
  @Override
  public Node getNode() {
    return root;
  }

  /**
   * {@inheritDoc} Returns the drag-handle label in the header. Installing mouse handlers on this
   * node is safe across {@link PaneLayoutManager#rebuildView()} calls because the node object never
   * changes.
   */
  @Override
  public Node getDragHandle() {
    return dragHandle;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Shows or hides only the close button. The split buttons and drag handle remain visible at
   * all times.
   */
  @Override
  public void showCloseButton(boolean show) {
    closeBtn.setVisible(show);
    closeBtn.setManaged(show);
  }

  /** {@inheritDoc} Closes the underlying SSH shell channel. */
  @Override
  public void dispose() {
    terminalController.dispose();
  }

  // ── Header construction ───────────────────────────────────────────────────

  private HBox buildHeader(Label handle, Consumer<Orientation> onSplit, Button close) {
    Button splitH = new Button("↔");
    splitH.getStyleClass().addAll("pane-header-btn", "pane-split-btn");
    splitH.setOnAction(e -> onSplit.accept(Orientation.HORIZONTAL));

    Button splitV = new Button("↕");
    splitV.getStyleClass().addAll("pane-header-btn", "pane-split-btn");
    splitV.setOnAction(e -> onSplit.accept(Orientation.VERTICAL));

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);

    HBox bar = new HBox(4, handle, splitH, splitV, spacer, close);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("pane-header");
    return bar;
  }
}
