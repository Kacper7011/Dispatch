package dev.dispatch.ui.ssh;

import javafx.geometry.Orientation;

/**
 * An internal node in the pane layout tree that splits its space between two children.
 *
 * <p>{@link Orientation#HORIZONTAL} places children side-by-side; {@link Orientation#VERTICAL}
 * stacks them. The {@code ratio} field (0.0–1.0) is the divider position — 0.5 means equal halves.
 * Both children must be non-null; use {@link TerminalNode} as a leaf placeholder.
 */
public final class SplitNode implements PaneNode {

  private Orientation orientation;
  private PaneNode first;
  private PaneNode second;
  private double ratio;

  /**
   * Creates a split node.
   *
   * @param orientation horizontal (side-by-side) or vertical (stacked)
   * @param first the first (left/top) child
   * @param second the second (right/bottom) child
   * @param ratio initial divider position, 0.0–1.0
   */
  public SplitNode(Orientation orientation, PaneNode first, PaneNode second, double ratio) {
    this.orientation = orientation;
    this.first = first;
    this.second = second;
    this.ratio = ratio;
  }

  public Orientation getOrientation() {
    return orientation;
  }

  public void setOrientation(Orientation orientation) {
    this.orientation = orientation;
  }

  public PaneNode getFirst() {
    return first;
  }

  public void setFirst(PaneNode first) {
    this.first = first;
  }

  public PaneNode getSecond() {
    return second;
  }

  public void setSecond(PaneNode second) {
    this.second = second;
  }

  public double getRatio() {
    return ratio;
  }

  public void setRatio(double ratio) {
    this.ratio = ratio;
  }
}
