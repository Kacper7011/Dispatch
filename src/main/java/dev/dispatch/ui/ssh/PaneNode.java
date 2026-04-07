package dev.dispatch.ui.ssh;

/**
 * Sealed marker interface for nodes in the tmux-style split-pane layout tree.
 *
 * <p>The tree is a binary structure: internal nodes are {@link SplitNode}s that hold two children
 * and an orientation; leaf nodes are {@link TerminalNode}s that hold a terminal or a pending
 * host-picker. {@link PaneLayoutManager} owns the root and rebuilds the JavaFX view from this tree
 * after every mutation.
 */
public sealed interface PaneNode permits SplitNode, TerminalNode {}
