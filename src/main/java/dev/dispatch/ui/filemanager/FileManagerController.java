package dev.dispatch.ui.filemanager;

import dev.dispatch.sftp.FileEntry;
import dev.dispatch.sftp.FileSession;
import dev.dispatch.sftp.SftpException;
import dev.dispatch.sftp.TransferTask;
import dev.dispatch.sftp.TransferTask.TransferProgress;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root controller for the Midnight Commander-style dual-panel file manager.
 * Manages two {@link FilePanelController} instances and handles all file operations.
 */
public class FileManagerController {

  private static final Logger log = LoggerFactory.getLogger(FileManagerController.class);

  @FXML private FilePanelController leftPanelController;
  @FXML private FilePanelController rightPanelController;
  @FXML private HBox transferProgressBox;
  @FXML private ProgressBar transferProgressBar;
  @FXML private Label transferFilenameLabel;
  @FXML private Label transferBytesLabel;

  private final FileSession leftDefault;
  private final FileSession rightDefault;
  private final Supplier<List<NamedSession>> sessionsSupplier;
  private FilePanelController activePanel;

  private final AtomicReference<TransferTask> currentTask = new AtomicReference<>();
  private final AtomicBoolean transferCancelled = new AtomicBoolean(false);

  public FileManagerController(
      FileSession left, FileSession right, Supplier<List<NamedSession>> sessions) {
    this.leftDefault = left;
    this.rightDefault = right;
    this.sessionsSupplier = sessions;
  }

  /**
   * Loads the FXML, initialises both panels, wires keyboard shortcuts.
   * Must be called from the FX Application Thread.
   */
  public Node createNode() {
    try {
      FXMLLoader loader = new FXMLLoader(
          getClass().getResource("/dev/dispatch/fxml/file-manager.fxml"));
      loader.setController(this);
      Node root = loader.load();
      leftPanelController.init(leftDefault, () -> setActive(leftPanelController));
      rightPanelController.init(rightDefault, () -> setActive(rightPanelController));
      leftPanelController.setAvailableSessions(sessionsSupplier);
      rightPanelController.setAvailableSessions(sessionsSupplier);
      leftPanelController.installContextMenu(
          this::onCopy, this::onMove, this::onMkdir, this::onDelete, this::onRename);
      rightPanelController.installContextMenu(
          this::onCopy, this::onMove, this::onMkdir, this::onDelete, this::onRename);
      leftPanelController.installDragAndDrop(this::onDragTransferStart, this::onDragTransferProgress, this::onDragTransferDone);
      rightPanelController.installDragAndDrop(this::onDragTransferStart, this::onDragTransferProgress, this::onDragTransferDone);
      setActive(leftPanelController);
      root.setOnKeyPressed(this::handleKey);
      return root;
    } catch (IOException e) {
      throw new RuntimeException("Failed to load file-manager.fxml", e);
    }
  }

  /** Releases the sessions currently active in both panels; called when the tab is closed. */
  public void dispose() {
    if (leftPanelController != null) closeQuietly(leftPanelController.getCurrentSession());
    if (rightPanelController != null) closeQuietly(rightPanelController.getCurrentSession());
  }

  // ── Key handler ──────────────────────────────────────────────────────────────

  private void handleKey(KeyEvent event) {
    switch (event.getCode()) {
      case TAB    -> { setActive(other()); event.consume(); }
      case F5     -> { onCopy();   event.consume(); }
      case F6     -> { onMove();   event.consume(); }
      case F7     -> { onMkdir();  event.consume(); }
      case F8     -> { onDelete(); event.consume(); }
      case F9     -> { onRename(); event.consume(); }
      default     -> {}
    }
  }

  // ── FXML action handlers ─────────────────────────────────────────────────────

  @FXML private void onCopy() { transferSelected(false); }
  @FXML private void onMove() { transferSelected(true); }

  @FXML
  private void onCancelTransfer() {
    TransferTask task = currentTask.get();
    if (task != null) task.cancel();
    transferCancelled.set(true);
  }

  @FXML
  private void onMkdir() {
    TextInputDialog dlg = new TextInputDialog();
    dlg.setTitle("New directory");
    dlg.setHeaderText("Directory name:");
    dlg.showAndWait().map(String::strip).filter(n -> !n.isEmpty()).ifPresent(name -> {
      String path = activePanel.getCurrentPath() + "/" + name;
      Thread.ofVirtual().start(() -> {
        try {
          sessionFor(activePanel).mkdir(path);
          Platform.runLater(activePanel::refresh);
        } catch (SftpException e) {
          log.error("mkdir {}", path, e);
          Platform.runLater(() -> showError("Create directory failed", e.getMessage()));
        }
      });
    });
  }

  @FXML
  private void onDelete() {
    List<FileEntry> sel = activePanel.getSelectedEntries();
    if (sel.isEmpty()) return;
    String preview = sel.stream().map(FileEntry::getName).limit(4)
        .reduce("", (a, b) -> a + "\n  " + b);
    if (sel.size() > 4) preview += "\n  … and " + (sel.size() - 4) + " more";
    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
        "Delete " + sel.size() + " item(s)?" + preview, ButtonType.OK, ButtonType.CANCEL);
    confirm.setTitle("Delete");
    confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> deleteAll(sel));
  }

  @FXML
  private void onRename() {
    List<FileEntry> sel = activePanel.getSelectedEntries();
    if (sel.size() != 1) return;
    FileEntry entry = sel.get(0);
    TextInputDialog dlg = new TextInputDialog(entry.getName());
    dlg.setTitle("Rename");
    dlg.setHeaderText("New name for: " + entry.getName());
    dlg.showAndWait().map(String::strip).filter(n -> !n.isEmpty()).ifPresent(newName -> {
      String newPath = activePanel.getCurrentPath() + "/" + newName;
      Thread.ofVirtual().start(() -> {
        try {
          sessionFor(activePanel).rename(entry.getPath(), newPath);
          Platform.runLater(activePanel::refresh);
        } catch (SftpException e) {
          log.error("rename {} → {}", entry.getPath(), newPath, e);
          Platform.runLater(() -> showError("Rename failed", e.getMessage()));
        }
      });
    });
  }

  // ── Transfer logic ───────────────────────────────────────────────────────────

  private void transferSelected(boolean move) {
    List<FileEntry> sel = activePanel.getSelectedEntries();
    if (sel.isEmpty()) return;
    FilePanelController target = other();
    FileSession src = sessionFor(activePanel);
    FileSession dest = sessionFor(target);
    String destDir = target.getCurrentPath();
    FilePanelController srcPanel = activePanel;

    transferCancelled.set(false);
    Thread.ofVirtual().start(() -> {
      for (FileEntry entry : sel) {
        if (transferCancelled.get()) break;
        String destPath = destDir + "/" + entry.getName();
        TransferTask task = new TransferTask(src, entry.getPath(), dest, destPath);
        currentTask.set(task);
        Platform.runLater(this::showProgressBox);
        Throwable[] error = {null};
        task.start().blockingSubscribe(
            this::updateTransferProgress,
            e -> { log.error("Transfer failed: {}", entry.getPath(), e); error[0] = e; },
            () -> {});
        if (error[0] != null) {
          final Throwable err = error[0];
          endTransfer();
          Platform.runLater(() -> showError("Transfer failed", err.getMessage()));
          return;
        }
        if (move && !transferCancelled.get()) deleteQuietly(src, entry);
      }
      endTransfer();
      Platform.runLater(() -> { srcPanel.refresh(); target.refresh(); });
    });
  }

  private void deleteAll(List<FileEntry> entries) {
    FileSession sess = sessionFor(activePanel);
    Thread.ofVirtual().start(() -> {
      entries.forEach(e -> deleteQuietly(sess, e));
      Platform.runLater(activePanel::refresh);
    });
  }

  // ── DnD progress callbacks (wired into both panels in createNode) ─────────────

  private void onDragTransferStart(TransferTask task) {
    currentTask.set(task);
    Platform.runLater(this::showProgressBox);
  }

  private void onDragTransferProgress(TransferProgress p) {
    updateTransferProgress(p);
  }

  private void onDragTransferDone() {
    endTransfer();
  }

  // ── Progress bar helpers ─────────────────────────────────────────────────────

  private void showProgressBox() {
    transferProgressBox.setVisible(true);
    transferProgressBox.setManaged(true);
    transferProgressBar.setProgress(0);
    transferFilenameLabel.setText("");
    transferBytesLabel.setText("");
  }

  private void updateTransferProgress(TransferProgress p) {
    Platform.runLater(() -> {
      double progress = p.totalBytes() > 0 ? p.fraction() : ProgressBar.INDETERMINATE_PROGRESS;
      transferProgressBar.setProgress(progress);
      transferFilenameLabel.setText(p.filename());
      String bytes = formatBytes(p.transferredBytes());
      String total = p.totalBytes() > 0 ? " / " + formatBytes(p.totalBytes()) : "";
      transferBytesLabel.setText(bytes + total);
    });
  }

  private void endTransfer() {
    currentTask.set(null);
    Platform.runLater(() -> {
      transferProgressBox.setVisible(false);
      transferProgressBox.setManaged(false);
    });
  }

  private static String formatBytes(long bytes) {
    if (bytes < 0) return "?";
    if (bytes < 1_024) return bytes + " B";
    if (bytes < 1_024 * 1_024) return String.format("%.1f KB", bytes / 1_024.0);
    if (bytes < 1_024L * 1_024 * 1_024) return String.format("%.1f MB", bytes / (1_024.0 * 1_024));
    return String.format("%.2f GB", bytes / (1_024.0 * 1_024 * 1_024));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private void setActive(FilePanelController panel) {
    activePanel = panel;
    leftPanelController.setActive(panel == leftPanelController);
    rightPanelController.setActive(panel == rightPanelController);
  }

  private FilePanelController other() {
    return activePanel == leftPanelController ? rightPanelController : leftPanelController;
  }

  private FileSession sessionFor(FilePanelController panel) {
    return panel.getCurrentSession();
  }

  private void deleteQuietly(FileSession sess, FileEntry entry) {
    try { sess.delete(entry.getPath(), true); }
    catch (SftpException e) { log.error("delete failed: {}", entry.getPath(), e); }
  }

  private void closeQuietly(FileSession sess) {
    try { sess.close(); }
    catch (Exception e) { log.warn("Session close error", e); }
  }

  private void showError(String title, String msg) {
    new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
  }
}
