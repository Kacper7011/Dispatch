package dev.dispatch.ui.filemanager;

import dev.dispatch.sftp.TransferTask.TransferProgress;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Non-modal floating progress window shown during file transfers.
 *
 * <p>The bar fills deterministically from left to right using total bytes transferred across the
 * entire batch. When the grand total is unknown (directory transfers before a size scan completes),
 * the bar shows an indeterminate animation until {@link #setGrandTotal} is called with the real
 * value. An {@link AnimationTimer} polls volatile fields at ~60 fps for smooth real-time updates.
 *
 * <p>{@link #show} and {@link #hide} must be called on the FX thread. {@link #updateAsync} and
 * {@link #setGrandTotal} are safe from any thread.
 */
public class TransferProgressDialog {

  private final Stage stage;
  private final Label filenameLabel;
  private final Label bytesLabel;
  private final ProgressBar bar;
  private Runnable cancelAction;

  // Written by VT, read by AnimationTimer on FX thread — volatile for cross-thread visibility.
  private volatile String latestFilename = "Starting…";
  // Monotonically increasing: never decreases to avoid backward jumps on directory sub-files.
  private volatile long latestCurrentTotal;
  private volatile long latestGrandTotal;
  private volatile boolean dirty;

  private final AnimationTimer renderTimer;

  public TransferProgressDialog(Window owner) {
    stage = new Stage();
    if (owner != null) stage.initOwner(owner);
    stage.initModality(Modality.NONE);
    stage.setTitle("Transferring");
    stage.setResizable(false);

    filenameLabel = new Label("Starting…");
    filenameLabel.getStyleClass().add("transfer-dialog-filename");
    filenameLabel.setMaxWidth(360);

    bytesLabel = new Label();
    bytesLabel.getStyleClass().add("transfer-dialog-bytes");

    bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    bar.setMaxWidth(Double.MAX_VALUE);
    bar.getStyleClass().add("transfer-dialog-bar");

    Button cancelBtn = new Button("Cancel");
    cancelBtn.getStyleClass().add("transfer-cancel-btn");
    cancelBtn.setOnAction(
        e -> {
          if (cancelAction != null) cancelAction.run();
        });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox btnRow = new HBox(spacer, cancelBtn);
    btnRow.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(8, filenameLabel, bytesLabel, bar, btnRow);
    root.setPadding(new Insets(20, 24, 16, 24));
    root.setMinWidth(420);
    root.getStyleClass().add("transfer-dialog-root");

    Scene scene = new Scene(root);
    scene.setFill(Color.web("#161616"));
    scene.getStylesheets().add(getClass().getResource("/css/dispatch-dark.css").toExternalForm());
    stage.setScene(scene);

    renderTimer =
        new AnimationTimer() {
          @Override
          public void handle(long now) {
            if (dirty) {
              dirty = false;
              render();
            }
          }
        };
  }

  /**
   * Shows the dialog. Must be called on the FX thread.
   *
   * @param filename name of the first entry being transferred (shown immediately)
   * @param grandTotal total bytes to transfer; if {@code > 0} the bar starts at 0% deterministic,
   *     otherwise shows indeterminate until {@link #setGrandTotal} is called
   * @param onCancel callback invoked on the FX thread when the user clicks Cancel
   */
  public void show(String filename, long grandTotal, Runnable onCancel) {
    cancelAction = onCancel;
    latestFilename = filename;
    latestCurrentTotal = 0;
    latestGrandTotal = grandTotal;
    dirty = false;
    filenameLabel.setText(filename);
    if (grandTotal > 0) {
      bar.setProgress(0.0);
      bytesLabel.setText(formatTransferBytes(0, grandTotal));
    } else {
      bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
      bytesLabel.setText("Scanning…");
    }
    renderTimer.start();
    if (!stage.isShowing()) stage.show();
  }

  /**
   * Updates the grand total after an asynchronous directory size scan. Safe to call from any
   * thread. The bar switches from indeterminate to 0% deterministic on the next animation frame.
   */
  public void setGrandTotal(long total) {
    latestGrandTotal = total;
    dirty = true;
  }

  /**
   * Returns the current accumulated bytes transferred across the batch. Safe to read from any
   * thread. Use as the {@code transferredBefore} base for the next item in a transfer loop.
   */
  public long currentTotal() {
    return latestCurrentTotal;
  }

  /** Hides the dialog. Must be called on FX thread. */
  public void hide() {
    renderTimer.stop();
    dirty = false;
    stage.hide();
  }

  /** Closes and disposes the Stage entirely. Must be called on FX thread. */
  public void close() {
    renderTimer.stop();
    stage.close();
  }

  /**
   * Records per-file progress into the total-transfer counters. {@code transferredBefore} is the
   * sum of bytes from all items completed before this one. The running total is clamped so it never
   * decreases (handles directory sub-file resets). Safe to call from any thread.
   */
  public void updateAsync(TransferProgress p, long transferredBefore, long grandTotal) {
    long newTotal = transferredBefore + p.transferredBytes();
    if (newTotal > latestCurrentTotal) latestCurrentTotal = newTotal;
    latestGrandTotal = grandTotal;
    latestFilename = p.filename();
    dirty = true;
  }

  private void render() {
    long current = latestCurrentTotal;
    long grand = latestGrandTotal;
    filenameLabel.setText(latestFilename);
    if (grand > 0) {
      bar.setProgress(Math.min(1.0, (double) current / grand));
      bytesLabel.setText(formatTransferBytes(current, grand));
    } else {
      bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
      bytesLabel.setText(current > 0 ? "Scanning… " + formatBytes(current) : "Scanning…");
    }
  }

  private static String formatTransferBytes(long current, long grand) {
    if (grand >= 1_024L * 1_024 * 1_024) {
      double f = 1_024.0 * 1_024 * 1_024;
      return String.format("%.1f / %.1f GB", current / f, grand / f);
    }
    if (grand >= 1_024 * 1_024) {
      double f = 1_024.0 * 1_024;
      return String.format("%.1f / %.1f MB", current / f, grand / f);
    }
    if (grand >= 1_024) {
      return String.format("%.0f / %.0f KB", current / 1_024.0, grand / 1_024.0);
    }
    return current + " / " + grand + " B";
  }

  private static String formatBytes(long bytes) {
    if (bytes < 0) return "?";
    if (bytes < 1_024) return bytes + " B";
    if (bytes < 1_024 * 1_024) return String.format("%.1f KB", bytes / 1_024.0);
    if (bytes < 1_024L * 1_024 * 1_024) return String.format("%.1f MB", bytes / (1_024.0 * 1_024));
    return String.format("%.2f GB", bytes / (1_024.0 * 1_024 * 1_024));
  }
}
