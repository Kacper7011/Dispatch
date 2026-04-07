package dev.dispatch.ui.ssh;

import javafx.geometry.Orientation;

/**
 * A leaf node in the pane layout tree representing a single terminal slot.
 *
 * <p>A leaf can be in one of two states:
 *
 * <ul>
 *   <li><b>Connected</b> — {@code content} is a {@link TerminalPane} with an active SSH session.
 *   <li><b>Pending</b> — {@code content} is a {@link PendingTerminalPane} showing a host-picker
 *       card until the user connects.
 * </ul>
 *
 * <p>The {@code splitHandler} is invoked by the per-pane split buttons so that {@link
 * PaneLayoutManager} can replace this leaf with a {@link SplitNode} containing two children.
 */
public final class TerminalNode implements PaneNode {

  private PaneContent content;
  private boolean connected;

  /**
   * Called when the user clicks a split button on this pane. The layout manager registers itself as
   * the handler so it can restructure the tree.
   */
  private SplitRequestHandler splitHandler;

  /** Called when the user clicks the close button on this pane. */
  private Runnable closeHandler;

  /**
   * Creates a connected terminal leaf.
   *
   * @param content the {@link TerminalPane} wrapping the active SSH session
   */
  public TerminalNode(PaneContent content) {
    this.content = content;
    this.connected = true;
  }

  /** Creates a pending (host-picker) leaf. */
  public TerminalNode() {
    this.content = null;
    this.connected = false;
  }

  /** Returns {@code true} when this leaf holds a live terminal. */
  public boolean isConnected() {
    return connected;
  }

  public PaneContent getContent() {
    return content;
  }

  /**
   * Sets the pane content and marks the leaf as connected. Called by {@link SshTabController} when
   * activating a pending leaf after a successful SSH connection.
   */
  public void setContent(PaneContent content) {
    this.content = content;
    this.connected = (content instanceof TerminalPane);
  }

  /**
   * Replaces the content without changing connection state. Used when setting initial pending
   * content ({@link PendingTerminalPane}) before a connection is made.
   */
  public void setPendingContent(PendingTerminalPane pendingPane) {
    this.content = pendingPane;
    this.connected = false;
  }

  public SplitRequestHandler getSplitHandler() {
    return splitHandler;
  }

  public void setSplitHandler(SplitRequestHandler splitHandler) {
    this.splitHandler = splitHandler;
  }

  public Runnable getCloseHandler() {
    return closeHandler;
  }

  public void setCloseHandler(Runnable closeHandler) {
    this.closeHandler = closeHandler;
  }

  /**
   * Functional interface for split requests originating from within a pane. The layout manager
   * registers itself as the handler so it can restructure the tree.
   */
  @FunctionalInterface
  public interface SplitRequestHandler {
    /**
     * @param leaf the leaf node requesting the split
     * @param orientation the direction of the new split
     */
    void onSplitRequested(TerminalNode leaf, Orientation orientation);
  }
}
