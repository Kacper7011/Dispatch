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
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;
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

  private final FileSession leftDefault;
  private final FileSession rightDefault;
  private final Supplier<List<NamedSession>> sessionsSupplier;
  private FilePanelController activePanel;
  private Node rootNode;

  // Transfer state shared across transferSelected() and DnD callbacks
  private final AtomicReference<TransferTask> currentTask = new AtomicReference<>();
  private final AtomicBoolean transferCancelled = new AtomicBoolean(false);
  // Stored by onDndBatchStart (FX thread) before the VT reads it in onDndProgress.
  private volatile long dndGrandTotal;

  // volatile so VT can safely call currentTotal() / setGrandTotal() after FX-thread creation.
  private volatile TransferProgressDialog progressDialog;

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
      rootNode = loader.load();
      leftPanelController.init(leftDefault, () -> setActive(leftPanelController));
      rightPanelController.init(rightDefault, () -> setActive(rightPanelController));
      leftPanelController.setAvailableSessions(sessionsSupplier);
      rightPanelController.setAvailableSessions(sessionsSupplier);
      leftPanelController.installContextMenu(
          this::onCopy, this::onMove, this::onMkdir, this::onDelete, this::onRename);
      rightPanelController.installContextMenu(
          this::onCopy, this::onMove, this::onMkdir, this::onDelete, this::onRename);
      leftPanelController.installDragAndDrop(
          this::onDndBatchStart, this::onDndItemStart,
          this::onDndProgress, this::onDndItemDone,
          this::onDndBatchDone, this::onDndGrandTotalUpdate);
      rightPanelController.installDragAndDrop(
          this::onDndBatchStart, this::onDndItemStart,
          this::onDndProgress, this::onDndItemDone,
          this::onDndBatchDone, this::onDndGrandTotalUpdate);
      setActive(leftPanelController);
      rootNode.setOnKeyPressed(this::handleKey);
      return rootNode;
    } catch (IOException e) {
      throw new RuntimeException("Failed to load file-manager.fxml", e);
    }
  }

  /** Releases sessions and closes the progress dialog; called when the tab is closed. */
  public void dispose() {
    if (progressDialog != null) progressDialog.close();
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

    // Quick estimate from FileEntry sizes (correct for files, 0 for directories).
    long roughTotal = sel.stream().mapToLong(FileEntry::getSize).sum();
    transferCancelled.set(false);

    TransferProgressDialog dialog = getDialog();
    dialog.show(sel.get(0).getName(), roughTotal, this::cancelTransfer);

    Thread.ofVirtual().start(() -> {
      // Scan accurate total when directories are present (roughTotal would be 0).
      boolean hasDir = sel.stream().anyMatch(FileEntry::isDirectory);
      final long grandTotal;
      if (hasDir) {
        grandTotal = TransferSizeScanner.scan(src, sel);
        dialog.setGrandTotal(grandTotal);
      } else {
        grandTotal = roughTotal;
      }

      long transferredBefore = 0;
      for (FileEntry entry : sel) {
        if (transferCancelled.get()) break;
        String destPath = destDir + "/" + entry.getName();
        TransferTask task = new TransferTask(src, entry.getPath(), dest, destPath);
        currentTask.set(task);
        final long before = transferredBefore;
        Throwable[] error = {null};
        task.start().subscribe(
            p -> dialog.updateAsync(p, before, grandTotal),
            e -> { log.error("Transfer failed: {}", entry.getPath(), e); error[0] = e; },
            () -> {});
        if (error[0] != null) {
          final Throwable err = error[0];
          Platform.runLater(() -> { dialog.hide(); showError("Transfer failed", err.getMessage()); });
          return;
        }
        if (move && !transferCancelled.get()) deleteQuietly(src, entry);
        // Use the dialog's running total as the base for the next item — correct even for
        // directories whose FileEntry.getSize() is 0.
        transferredBefore = dialog.currentTotal();
      }
      Platform.runLater(() -> { dialog.hide(); srcPanel.refresh(); target.refresh(); });
    });
  }

  private void deleteAll(List<FileEntry> entries) {
    FileSession sess = sessionFor(activePanel);
    Thread.ofVirtual().start(() -> {
      entries.forEach(e -> deleteQuietly(sess, e));
      Platform.runLater(activePanel::refresh);
    });
  }

  // ── DnD progress callbacks ────────────────────────────────────────────────────

  /**
   * Called on the FX thread immediately when a DnD drop is accepted. Shows the dialog in
   * indeterminate state; the real total arrives via {@link #onDndGrandTotalUpdate}.
   */
  private void onDndBatchStart(long ignored) {
    dndGrandTotal = 0;
    transferCancelled.set(false);
    getDialog().show("Transferring…", 0, this::cancelTransfer);
  }

  /** Called from VT after the size scan completes. Updates the bar to deterministic. */
  private void onDndGrandTotalUpdate(long total) {
    dndGrandTotal = total;
    getDialog().setGrandTotal(total);
  }

  /** Called from VT when a new DnD item starts. */
  private void onDndItemStart(TransferTask task) {
    currentTask.set(task);
  }

  /** Called from VT for each progress tick. */
  private void onDndProgress(TransferProgress p, Long transferredBefore) {
    getDialog().updateAsync(p, transferredBefore, dndGrandTotal);
  }

  /**
   * Called from VT after each DnD item completes. Returns the dialog's running total so the
   * caller can use it as {@code transferredBefore} for the next item (correct for directories
   * whose {@link FileEntry#getSize()} is 0).
   */
  private long onDndItemDone() {
    return getDialog().currentTotal();
  }

  /** Called from VT when the entire DnD batch completes. */
  private void onDndBatchDone() {
    Platform.runLater(getDialog()::hide);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private void cancelTransfer() {
    TransferTask task = currentTask.get();
    if (task != null) task.cancel();
    transferCancelled.set(true);
  }

  private TransferProgressDialog getDialog() {
    if (progressDialog == null) {
      Window owner = rootNode != null && rootNode.getScene() != null
          ? rootNode.getScene().getWindow() : null;
      progressDialog = new TransferProgressDialog(owner);
    }
    return progressDialog;
  }

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
