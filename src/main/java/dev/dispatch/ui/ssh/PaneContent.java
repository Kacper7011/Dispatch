package dev.dispatch.ui.ssh;

import javafx.scene.Node;

/**
 * Common interface for content that occupies a leaf slot in the pane layout tree.
 *
 * <p>Implemented by {@link TerminalPane} (live SSH terminal) and {@link PendingTerminalPane}
 * (host-picker card). {@link PaneLayoutManager} calls {@link #getNode()} to embed the content in
 * the JavaFX hierarchy and {@link #showCloseButton(boolean)} to manage header visibility.
 */
public interface PaneContent {

  /** Returns the root JavaFX node for this content. */
  Node getNode();

  /**
   * Shows or hides the header bar (split + close buttons). Called by {@link PaneLayoutManager}
   * after every tree mutation — visible when more than one leaf exists.
   */
  void showCloseButton(boolean show);

  /** Releases any underlying resources (e.g. SSH shell channel). */
  void dispose();
}
