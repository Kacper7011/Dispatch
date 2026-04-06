package dev.dispatch.ui.ssh;

import dev.dispatch.ssh.SshSession;
import dev.dispatch.ssh.terminal.TerminalController;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

/**
 * A single connected terminal pane inside a split SSH tab.
 *
 * <p>Wraps a {@link TerminalController} with an optional close-button header. The header is hidden
 * when this is the only pane in the tab and shown as soon as a second pane exists. Must be created
 * and used on the FX Application Thread.
 */
public class TerminalPane {

  private final TerminalController terminalController;
  private final BorderPane root;
  private final HBox header;

  /**
   * Creates the pane and immediately opens the terminal node.
   *
   * @param session the active SSH session — a new shell channel is opened for this pane
   * @param onClose callback invoked when the user clicks the close button
   */
  public TerminalPane(SshSession session, Runnable onClose) {
    terminalController = new TerminalController(session);
    Node terminalNode = terminalController.createNode();

    Button closeBtn = new Button("×");
    closeBtn.getStyleClass().add("pane-close-btn");
    closeBtn.setOnAction(e -> onClose.run());

    header = new HBox(closeBtn);
    header.setAlignment(Pos.CENTER_RIGHT);
    header.getStyleClass().add("pane-header");
    // Hidden by default — SshTabController shows it when a second pane is added
    header.setVisible(false);
    header.setManaged(false);

    root = new BorderPane(terminalNode);
    root.setTop(header);
  }

  /** Returns the root node to be placed inside a {@link javafx.scene.control.SplitPane}. */
  public Node getNode() {
    return root;
  }

  /** Shows or hides the close button header. Called by {@link SshTabController}. */
  public void showCloseButton(boolean show) {
    header.setVisible(show);
    header.setManaged(show);
  }

  /** Disposes the underlying terminal (closes the shell channel). */
  public void dispose() {
    terminalController.dispose();
  }
}
