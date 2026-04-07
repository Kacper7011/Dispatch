package dev.dispatch.ui.ssh;

import java.util.List;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements tmux-style drag-and-drop reordering for pane content within a {@link
 * PaneLayoutManager}.
 *
 * <h2>Mechanism</h2>
 *
 * <p>JavaFX's built-in {@code Dragboard} API is unusable here because {@link
 * javafx.scene.web.WebView} (used by each terminal) swallows mouse events before they can start a
 * drag gesture. Instead we use raw mouse events:
 *
 * <ol>
 *   <li>{@code MOUSE_PRESSED} on a drag handle — records the dragged {@link PaneContent}.
 *   <li>{@code MOUSE_DRAGGED} on the scene (via filter, bypassing WebView) — highlights the pane
 *       currently under the cursor as the drop target.
 *   <li>{@code MOUSE_RELEASED} on the scene — swaps content between the dragged pane and the target
 *       pane, then clears all highlights.
 * </ol>
 *
 * <h2>Drop resolution</h2>
 *
 * <p>At drop time we walk all leaves and hit-test each pane's root node against the scene
 * coordinates. The first leaf whose bounds contain the cursor becomes the target. If the target is
 * the same as the source, the drag is a no-op.
 *
 * <h2>Content vs. node identity</h2>
 *
 * <p>After a {@link PaneLayoutManager#rebuildView()} the JavaFX nodes are rebuilt from scratch, so
 * node identity cannot be used to track "which pane is being dragged". We track the {@link
 * PaneContent} object instead — it survives rebuilds — and use {@link
 * PaneLayoutManager#findLeafByContent} to resolve its owning {@link TerminalNode} at drop time.
 */
public class PaneDragHandler {

  private static final Logger log = LoggerFactory.getLogger(PaneDragHandler.class);

  /** Pseudo-class name added to the root node of the current drop-target pane. */
  private static final String DROP_TARGET_CLASS = "pane-drop-target";

  private final PaneLayoutManager layoutManager;

  /**
   * The content that the user started dragging. {@code null} when no drag is in progress. We hold
   * the content (not the leaf or its node) because both can change after a rebuildView().
   */
  private PaneContent draggedContent;

  /** Root node of the pane currently highlighted as drop target. May be {@code null}. */
  private Node currentHighlight;

  /**
   * Creates a drag handler and attaches all required event filters to {@code sceneRoot}. The
   * handler uses scene-level filters so that WebView child nodes cannot swallow the events.
   *
   * @param layoutManager the layout manager whose leaves this handler may reorder
   * @param sceneRoot the root node on which scene-level mouse filters are installed; typically the
   *     {@link StackPane} returned by {@link PaneLayoutManager#getRootView()}
   */
  public PaneDragHandler(PaneLayoutManager layoutManager, StackPane sceneRoot) {
    this.layoutManager = layoutManager;
    attachHandlersToAllLeaves();

    // Scene-level filters reach us before WebView can consume the events.
    sceneRoot.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onMouseDragged);
    sceneRoot.addEventFilter(MouseEvent.MOUSE_RELEASED, this::onMouseReleased);
  }

  /**
   * Installs a MOUSE_PRESSED handler on the drag-handle node of every current leaf. Must be called
   * again via {@link #refresh()} after a rebuildView() adds new leaves, because new {@link
   * PaneContent} objects are created for new panes.
   */
  public void refresh() {
    attachHandlersToAllLeaves();
  }

  // ── Event handlers ────────────────────────────────────────────────────────

  private void attachHandlersToAllLeaves() {
    for (TerminalNode leaf : layoutManager.collectLeaves()) {
      PaneContent content = leaf.getContent();
      if (content == null) continue;
      Node handle = content.getDragHandle();
      // Replace any previously installed handler to avoid duplicate registrations.
      handle.setOnMousePressed(e -> onHandlePressed(e, content));
    }
  }

  private void onHandlePressed(MouseEvent e, PaneContent content) {
    if (e.getButton() != MouseButton.PRIMARY) return;
    draggedContent = content;
    log.debug("Drag started on {}", content.getClass().getSimpleName());
    e.consume();
  }

  private void onMouseDragged(MouseEvent e) {
    if (draggedContent == null) return;

    Node target = resolveTargetNode(e.getSceneX(), e.getSceneY());
    if (target == currentHighlight) return;

    clearHighlight();
    if (target != null) {
      target.getStyleClass().add(DROP_TARGET_CLASS);
      currentHighlight = target;
    }
  }

  private void onMouseReleased(MouseEvent e) {
    if (draggedContent == null) return;

    Node targetNode = resolveTargetNode(e.getSceneX(), e.getSceneY());
    clearHighlight();

    if (targetNode != null) {
      PaneContent targetContent = contentForNode(targetNode);
      if (targetContent != null && targetContent != draggedContent) {
        TerminalNode srcLeaf = layoutManager.findLeafByContent(draggedContent);
        TerminalNode dstLeaf = layoutManager.findLeafByContent(targetContent);
        if (srcLeaf != null && dstLeaf != null) {
          layoutManager.swapLeafContent(srcLeaf, dstLeaf);
          // swapLeafContent calls rebuildView() — re-attach handle listeners for new leaves
          refresh();
          log.debug("Swap complete — src={} dst={}", srcLeaf, dstLeaf);
        }
      }
    }

    draggedContent = null;
  }

  // ── Hit-testing helpers ───────────────────────────────────────────────────

  /**
   * Returns the root {@link Node} of the leaf pane whose bounds contain the given scene
   * coordinates, or {@code null} if none match.
   */
  private Node resolveTargetNode(double sceneX, double sceneY) {
    List<TerminalNode> leaves = layoutManager.collectLeaves();
    for (TerminalNode leaf : leaves) {
      if (leaf.getContent() == null) continue;
      Node node = leaf.getContent().getNode();
      Point2D local = node.sceneToLocal(sceneX, sceneY);
      if (node.getBoundsInLocal().contains(local)) {
        return node;
      }
    }
    return null;
  }

  /**
   * Returns the {@link PaneContent} whose root node is {@code node}, or {@code null} if none
   * matches.
   */
  private PaneContent contentForNode(Node node) {
    return layoutManager.collectLeaves().stream()
        .map(TerminalNode::getContent)
        .filter(c -> c != null && c.getNode() == node)
        .findFirst()
        .orElse(null);
  }

  private void clearHighlight() {
    if (currentHighlight != null) {
      currentHighlight.getStyleClass().remove(DROP_TARGET_CLASS);
      currentHighlight = null;
    }
  }
}
