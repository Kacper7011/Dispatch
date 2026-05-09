package dev.dispatch.ui.filemanager;

import dev.dispatch.sftp.TransferTask.TransferProgress;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
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
 * All public methods must be called on the FX Application Thread.
 */
public class TransferProgressDialog {

  private final Stage stage;
  private final Label countLabel;
  private final ProgressBar overallBar;
  private final Label filenameLabel;
  private final Label bytesLabel;
  private final ProgressBar fileBar;
  private Runnable cancelAction;

  public TransferProgressDialog(Window owner) {
    stage = new Stage();
    if (owner != null) stage.initOwner(owner);
    stage.initModality(Modality.NONE);
    stage.setTitle("Transferring");
    stage.setResizable(false);

    countLabel = new Label("0 / 0 items");
    countLabel.getStyleClass().add("transfer-dialog-count");

    overallBar = new ProgressBar(0);
    overallBar.setMaxWidth(Double.MAX_VALUE);
    overallBar.getStyleClass().add("transfer-dialog-bar");

    Separator sep = new Separator();
    sep.getStyleClass().add("transfer-dialog-sep");

    filenameLabel = new Label();
    filenameLabel.getStyleClass().add("transfer-dialog-filename");
    filenameLabel.setMaxWidth(360);

    bytesLabel = new Label();
    bytesLabel.getStyleClass().add("transfer-dialog-bytes");

    fileBar = new ProgressBar(0);
    fileBar.setMaxWidth(Double.MAX_VALUE);
    fileBar.getStyleClass().add("transfer-dialog-bar");

    Button cancelBtn = new Button("Cancel");
    cancelBtn.getStyleClass().add("transfer-cancel-btn");
    cancelBtn.setOnAction(e -> { if (cancelAction != null) cancelAction.run(); });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox btnRow = new HBox(spacer, cancelBtn);
    btnRow.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(10,
        countLabel, overallBar,
        sep,
        filenameLabel, bytesLabel, fileBar,
        btnRow);
    root.setPadding(new Insets(20, 24, 16, 24));
    root.setMinWidth(400);
    root.getStyleClass().add("transfer-dialog-root");

    Scene scene = new Scene(root);
    scene.setFill(Color.web("#161616"));
    scene.getStylesheets().add(
        getClass().getResource("/css/dispatch-dark.css").toExternalForm());
    stage.setScene(scene);
  }

  /** Shows the dialog and resets all widgets. Must be called on FX thread. */
  public void show(int total, Runnable onCancel) {
    cancelAction = onCancel;
    countLabel.setText("0 / " + total + " items");
    overallBar.setProgress(0);
    filenameLabel.setText("Starting…");
    bytesLabel.setText("");
    fileBar.setProgress(0);
    if (!stage.isShowing()) stage.show();
  }

  /**
   * Updates the per-file progress bar and byte counters.
   * Called frequently during transfer — must be on FX thread.
   */
  public void update(TransferProgress p, int done, int total) {
    countLabel.setText(done + " / " + total + " items");
    overallBar.setProgress(total > 0 ? (double) done / total : 0);
    filenameLabel.setText(p.filename());
    double fileFraction = p.totalBytes() > 0
        ? p.fraction() : ProgressBar.INDETERMINATE_PROGRESS;
    fileBar.setProgress(fileFraction);
    String xferred = formatBytes(p.transferredBytes());
    String total_s = p.totalBytes() > 0 ? " / " + formatBytes(p.totalBytes()) : "";
    bytesLabel.setText(xferred + total_s);
  }

  /** Updates only the item count label (called after each item completes). */
  public void updateCount(int done, int total) {
    countLabel.setText(done + " / " + total + " items");
    overallBar.setProgress(total > 0 ? (double) done / total : 0);
  }

  public void hide() {
    stage.hide();
  }

  public void close() {
    stage.close();
  }

  private static String formatBytes(long bytes) {
    if (bytes < 0) return "?";
    if (bytes < 1_024) return bytes + " B";
    if (bytes < 1_024 * 1_024) return String.format("%.1f KB", bytes / 1_024.0);
    if (bytes < 1_024L * 1_024 * 1_024) return String.format("%.1f MB", bytes / (1_024.0 * 1_024));
    return String.format("%.2f GB", bytes / (1_024.0 * 1_024 * 1_024));
  }
}
