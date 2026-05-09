package dev.dispatch.ui.filemanager;

import dev.dispatch.sftp.FileEntry;
import dev.dispatch.sftp.FileSession;
import dev.dispatch.sftp.SftpException;
import dev.dispatch.sftp.TransferTask;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.util.List;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyEvent;
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

  private final FileSession leftSession;
  private final FileSession rightSession;
  private final List<NamedSession> availableSessions;
  private FilePanelController activePanel;

  public FileManagerController(FileSession left, FileSession right, List<NamedSession> available) {
    this.leftSession = left;
    this.rightSession = right;
    this.availableSessions = available;
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
      leftPanelController.init(leftSession, () -> setActive(leftPanelController));
      rightPanelController.init(rightSession, () -> setActive(rightPanelController));
      leftPanelController.setAvailableSessions(availableSessions);
      rightPanelController.setAvailableSessions(availableSessions);
      leftPanelController.installContextMenu(
          this::onCopy, this::onMove, this::onMkdir, this::onDelete, this::onRename);
      rightPanelController.installContextMenu(
          this::onCopy, this::onMove, this::onMkdir, this::onDelete, this::onRename);
      leftPanelController.installDragAndDrop();
      rightPanelController.installDragAndDrop();
      setActive(leftPanelController);
      root.setOnKeyPressed(this::handleKey);
      return root;
    } catch (IOException e) {
      throw new RuntimeException("Failed to load file-manager.fxml", e);
    }
  }

  /** Releases both sessions; called when the tab is closed. */
  public void dispose() {
    closeQuietly(leftSession);
    closeQuietly(rightSession);
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

  @FXML private void onCopy()   { transferSelected(false); }
  @FXML private void onMove()   { transferSelected(true); }

  @FXML
  private void onMkdir() {
    TextInputDialog dlg = new TextInputDialog();
    dlg.setTitle("Utwórz katalog");
    dlg.setHeaderText("Nazwa nowego katalogu:");
    dlg.showAndWait().map(String::strip).filter(n -> !n.isEmpty()).ifPresent(name -> {
      String path = activePanel.getCurrentPath() + "/" + name;
      Thread.ofVirtual().start(() -> {
        try {
          sessionFor(activePanel).mkdir(path);
          Platform.runLater(activePanel::refresh);
        } catch (SftpException e) {
          log.error("mkdir {}", path, e);
          Platform.runLater(() -> showError("Błąd tworzenia katalogu", e.getMessage()));
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
    if (sel.size() > 4) preview += "\n  … i " + (sel.size() - 4) + " więcej";
    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
        "Usunąć " + sel.size() + " element(ów)?" + preview, ButtonType.OK, ButtonType.CANCEL);
    confirm.setTitle("Usuń");
    confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> deleteAll(sel));
  }

  @FXML
  private void onRename() {
    List<FileEntry> sel = activePanel.getSelectedEntries();
    if (sel.size() != 1) return;
    FileEntry entry = sel.get(0);
    TextInputDialog dlg = new TextInputDialog(entry.getName());
    dlg.setTitle("Zmień nazwę");
    dlg.setHeaderText("Nowa nazwa dla: " + entry.getName());
    dlg.showAndWait().map(String::strip).filter(n -> !n.isEmpty()).ifPresent(newName -> {
      String newPath = activePanel.getCurrentPath() + "/" + newName;
      Thread.ofVirtual().start(() -> {
        try {
          sessionFor(activePanel).rename(entry.getPath(), newPath);
          Platform.runLater(activePanel::refresh);
        } catch (SftpException e) {
          log.error("rename {} → {}", entry.getPath(), newPath, e);
          Platform.runLater(() -> showError("Błąd zmiany nazwy", e.getMessage()));
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

    for (FileEntry entry : sel) {
      String destPath = destDir + "/" + entry.getName();
      new TransferTask(src, entry.getPath(), dest, destPath)
          .start()
          .subscribeOn(Schedulers.io())
          .subscribe(
              p -> {},
              e -> {
                log.error("Transfer failed: {}", entry.getPath(), e);
                Platform.runLater(() -> showError("Transfer nieudany", e.getMessage()));
              },
              () -> {
                if (move) deleteQuietly(src, entry);
                Platform.runLater(() -> { activePanel.refresh(); target.refresh(); });
              });
    }
  }

  private void deleteAll(List<FileEntry> entries) {
    FileSession sess = sessionFor(activePanel);
    Thread.ofVirtual().start(() -> {
      entries.forEach(e -> deleteQuietly(sess, e));
      Platform.runLater(activePanel::refresh);
    });
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
    return panel == leftPanelController ? leftSession : rightSession;
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
