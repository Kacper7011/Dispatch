package dev.dispatch.ui.ssh;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the tmux-style pane layout tree and keeps the JavaFX view in sync with it.
 *
 * <p>The tree is a recursive structure of {@link SplitNode}s (internal) and {@link TerminalNode}s
 * (leaves). After every mutation the manager calls {@link #rebuildView()} which reconstructs the
 * JavaFX node hierarchy from scratch — simple, correct, and easy to reason about.
 *
 * <p>Public API consumed by {@link SshTabController}:
 *
 * <ul>
 *   <li>{@link #getRootView()} — the StackPane to embed in the tab layout
 *   <li>{@link #splitLeaf(TerminalNode, Orientation, TerminalNode)} — replace a leaf with a split
 *   <li>{@link #removeLeaf(TerminalNode)} — remove a leaf, collapsing the parent split
 *   <li>{@link #replaceLeaf(TerminalNode, TerminalNode)} — swap one leaf for another in-place
 *   <li>{@link #collectLeaves()} — walk the full tree and return all leaves in order
 * </ul>
 */
public class PaneLayoutManager {

  private static final Logger log = LoggerFactory.getLogger(PaneLayoutManager.class);

  private PaneNode root;

  /** The StackPane that always wraps the current root JavaFX node. */
  private final StackPane rootView;

  /**
   * Creates the manager with an initial single leaf.
   *
   * @param initialLeaf the first terminal node (already connected or pending)
   */
  public PaneLayoutManager(TerminalNode initialLeaf) {
    this.root = initialLeaf;
    this.rootView = new StackPane();
    rebuildView();
  }

  /** Returns the stable StackPane container to embed in the tab. */
  public StackPane getRootView() {
    return rootView;
  }

  // ── Tree mutations ────────────────────────────────────────────────────────

  /**
   * Replaces {@code leaf} in the tree with a new {@link SplitNode} whose first child is {@code
   * leaf} itself and second child is {@code newLeaf} — mirroring tmux behaviour where the new pane
   * opens next to the current one.
   *
   * @param leaf existing leaf to split
   * @param orientation direction of the new split
   * @param newLeaf the freshly created leaf that will occupy the new pane
   */
  public void splitLeaf(TerminalNode leaf, Orientation orientation, TerminalNode newLeaf) {
    SplitNode split = new SplitNode(orientation, leaf, newLeaf, 0.5);
    replaceInTree(root, null, false, leaf, split);
    rebuildView();
    log.debug("splitLeaf orientation={} totalLeaves={}", orientation, collectLeaves().size());
  }

  /**
   * Removes {@code leaf} from the tree. The sibling takes over the parent split's space. If the
   * root itself is the leaf this call is a no-op (cannot remove the last pane).
   *
   * @param leaf the leaf to remove
   */
  public void removeLeaf(TerminalNode leaf) {
    if (root == leaf) {
      log.debug("Ignoring removeLeaf — leaf is root (last pane)");
      return;
    }
    removeFromTree(root, null, false, leaf);
    rebuildView();
    log.debug("removeLeaf — totalLeaves={}", collectLeaves().size());
  }

  /**
   * Swaps {@code oldLeaf} for {@code newLeaf} at the same position in the tree. Used when
   * activating a pending leaf or replacing the primary terminal after reconnect.
   *
   * @param oldLeaf leaf to replace
   * @param newLeaf replacement (may be the same object — triggers a view rebuild in place)
   */
  public void replaceLeaf(TerminalNode oldLeaf, TerminalNode newLeaf) {
    if (root == oldLeaf) {
      root = newLeaf;
    } else {
      replaceInTree(root, null, false, oldLeaf, newLeaf);
    }
    rebuildView();
  }

  /** Walks the tree and returns all {@link TerminalNode} leaves in depth-first order. */
  public List<TerminalNode> collectLeaves() {
    List<TerminalNode> leaves = new ArrayList<>();
    collectLeavesRecursive(root, leaves);
    return leaves;
  }

  /**
   * Swaps the {@link PaneContent} between two leaves in-place. Neither leaf moves in the tree; only
   * their content references are exchanged. Used by {@link PaneDragHandler} after a drop.
   *
   * @param a first leaf
   * @param b second leaf
   */
  public void swapLeafContent(TerminalNode a, TerminalNode b) {
    PaneContent contentA = a.getContent();
    boolean connectedA = a.isConnected();
    PaneContent contentB = b.getContent();
    boolean connectedB = b.isConnected();

    // Swap raw content — bypass the connected-flag logic in setContent() because
    // TerminalPane ↔ PendingTerminalPane swaps must also transfer the connected state.
    a.setRawContent(contentB, connectedB);
    b.setRawContent(contentA, connectedA);

    rebuildView();
    log.debug("swapLeafContent — leaves swapped");
  }

  /**
   * Searches the tree for the leaf whose current content is {@code target}. Returns {@code null} if
   * not found. Used by {@link PaneDragHandler} to resolve the owning leaf at drag time, which
   * remains correct even after {@link #swapLeafContent} has rearranged contents.
   *
   * @param target the content instance to look up
   * @return the leaf currently holding {@code target}, or {@code null}
   */
  public TerminalNode findLeafByContent(PaneContent target) {
    return collectLeaves().stream()
        .filter(leaf -> leaf.getContent() == target)
        .findFirst()
        .orElse(null);
  }

  // ── View reconstruction ───────────────────────────────────────────────────

  /**
   * Rebuilds the full JavaFX node hierarchy from the current tree and swaps it into {@link
   * #rootView}. Called after every tree mutation. Must be invoked on the FX thread.
   */
  public void rebuildView() {
    Node view = buildView(root);
    rootView.getChildren().setAll(view);
    updateCloseButtonVisibility();
  }

  /**
   * Recursively constructs the JavaFX node for a subtree.
   *
   * <ul>
   *   <li>A {@link SplitNode} becomes a {@link SplitPane} with two children and its saved ratio.
   *   <li>A {@link TerminalNode} delegates to its {@link PaneContent#getNode()}.
   * </ul>
   */
  private Node buildView(PaneNode node) {
    return switch (node) {
      case SplitNode split -> buildSplitView(split);
      case TerminalNode leaf -> buildLeafView(leaf);
    };
  }

  private Node buildSplitView(SplitNode split) {
    SplitPane sp = new SplitPane();
    sp.setOrientation(split.getOrientation());
    sp.getItems().addAll(buildView(split.getFirst()), buildView(split.getSecond()));
    sp.setDividerPositions(split.getRatio());

    // Persist ratio changes back to the model so they survive a rebuildView()
    sp.getDividers()
        .get(0)
        .positionProperty()
        .addListener((obs, oldVal, newVal) -> split.setRatio(newVal.doubleValue()));

    return sp;
  }

  private Node buildLeafView(TerminalNode leaf) {
    if (leaf.getContent() == null) {
      // Safety fallback — content should always be set before rebuildView() is called
      javafx.scene.layout.BorderPane empty = new javafx.scene.layout.BorderPane();
      empty.getStyleClass().add("host-picker");
      return empty;
    }
    return leaf.getContent().getNode();
  }

  // ── Close-button visibility ───────────────────────────────────────────────

  /**
   * Shows close/split buttons on all panes when more than one leaf exists; hides them when only one
   * pane remains.
   */
  private void updateCloseButtonVisibility() {
    List<TerminalNode> leaves = collectLeaves();
    boolean show = leaves.size() > 1;
    leaves.forEach(
        leaf -> {
          if (leaf.getContent() != null) leaf.getContent().showCloseButton(show);
        });
  }

  // ── Tree traversal helpers ────────────────────────────────────────────────

  private void collectLeavesRecursive(PaneNode node, List<TerminalNode> acc) {
    switch (node) {
      case SplitNode split -> {
        collectLeavesRecursive(split.getFirst(), acc);
        collectLeavesRecursive(split.getSecond(), acc);
      }
      case TerminalNode leaf -> acc.add(leaf);
    }
  }

  /**
   * Replaces {@code target} anywhere in the subtree rooted at {@code node} with {@code
   * replacement}. The parent reference is updated directly on the {@link SplitNode}.
   */
  private void replaceInTree(
      PaneNode node, SplitNode parent, boolean isFirst, PaneNode target, PaneNode replacement) {
    if (node == target) {
      if (parent == null) {
        root = replacement;
      } else if (isFirst) {
        parent.setFirst(replacement);
      } else {
        parent.setSecond(replacement);
      }
      return;
    }
    if (node instanceof SplitNode split) {
      replaceInTree(split.getFirst(), split, true, target, replacement);
      replaceInTree(split.getSecond(), split, false, target, replacement);
    }
  }

  /**
   * Removes {@code target} from the subtree. The parent split is replaced by the surviving sibling.
   */
  private void removeFromTree(
      PaneNode node, SplitNode grandparent, boolean grandparentIsFirst, PaneNode target) {
    if (!(node instanceof SplitNode split)) return;

    if (split.getFirst() == target) {
      substituteNode(split, grandparent, grandparentIsFirst, split.getSecond());
    } else if (split.getSecond() == target) {
      substituteNode(split, grandparent, grandparentIsFirst, split.getFirst());
    } else {
      removeFromTree(split.getFirst(), split, true, target);
      removeFromTree(split.getSecond(), split, false, target);
    }
  }

  private void substituteNode(
      SplitNode toReplace, SplitNode parent, boolean isFirst, PaneNode substitute) {
    if (parent == null) {
      root = substitute;
    } else if (isFirst) {
      parent.setFirst(substitute);
    } else {
      parent.setSecond(substitute);
    }
  }
}
