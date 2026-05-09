package dev.dispatch.ui.filemanager;

import dev.dispatch.sftp.FileEntry;
import dev.dispatch.sftp.FileSession;
import dev.dispatch.sftp.SftpException;
import dev.dispatch.sftp.TransferTask;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for one file-browser panel (left or right).
 * Handles directory navigation, multi-selection, and status display.
 */
public class FilePanelController {

  private static final Logger log = LoggerFactory.getLogger(FilePanelController.class);

  @FXML private Label titleLabel;
  @FXML private MenuButton sessionBtn;
  @FXML private TextField pathField;
  @FXML private TableView<FileEntryRow> fileTable;
  @FXML private TableColumn<FileEntryRow, String> nameColumn;
  @FXML private TableColumn<FileEntryRow, String> sizeColumn;
  @FXML private TableColumn<FileEntryRow, String> dateColumn;
  @FXML private Label statusLabel;

  private FileSession session;
  private Runnable onActivated;
  private String currentPath;
  private final ObservableList<FileEntryRow> items = FXCollections.observableArrayList();

  @FXML
  private void initialize() {
    nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
    sizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
    dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));
    fileTable.setItems(items);
    fileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    fileTable.setOnMouseClicked(e -> {
      if (onActivated != null) onActivated.run();
      if (e.getClickCount() == 2) activateSelected();
    });
    fileTable.setOnKeyPressed(e -> {
      switch (e.getCode()) {
        case ENTER -> activateSelected();
        case BACK_SPACE -> navigateToParent();
        default -> {}
      }
    });
  }

  /** Wires the session and loads the home directory. Call from {@link FileManagerController}. */
  public void init(FileSession session, Runnable onActivated) {
    this.session = session;
    this.onActivated = onActivated;
    titleLabel.setText(session.displayName());
    sessionBtn.setText(session.displayName());
    navigate(session.home());
  }

  /** Returns the session currently active in this panel. */
  public FileSession getCurrentSession() {
    return session;
  }

  /**
   * Wires the session-switcher {@link MenuButton}. The supplier is called every time the
   * dropdown opens, so newly-connected hosts always appear without restarting the tab.
   */
  public void setAvailableSessions(Supplier<List<NamedSession>> sessionsSupplier) {
    sessionBtn.setOnShowing(e -> {
      sessionBtn.getItems().clear();
      for (NamedSession ns : sessionsSupplier.get()) {
        MenuItem item = new MenuItem(ns.label());
        item.setOnAction(ev -> switchSession(ns));
        sessionBtn.getItems().add(item);
      }
    });
    sessionBtn.setVisible(true);
  }

  private void switchSession(NamedSession ns) {
    FileSession old = this.session;
    FileSession next = ns.factory().get();
    this.session = next;
    titleLabel.setText(next.displayName());
    sessionBtn.setText(next.displayName());
    navigate(next.home());
    old.close();
  }

  /** Navigates to {@code path} on a virtual thread, updates the table on the FX thread. */
  public void navigate(String path) {
    Thread.ofVirtual().start(() -> {
      try {
        List<FileEntry> entries = session.list(path);
        Platform.runLater(() -> {
          currentPath = path;
          pathField.setText(path);
          items.setAll(entries.stream().map(FileEntryRow::new).toList());
          updateStatus();
          if (!items.isEmpty()) {
            fileTable.getSelectionModel().clearAndSelect(0);
          }
        });
      } catch (SftpException e) {
        log.warn("Cannot list {}: {}", path, e.getMessage());
        Platform.runLater(() -> statusLabel.setText("Błąd: " + e.getMessage()));
      }
    });
  }

  /** Re-lists the current directory (called after transfers, mkdir, delete, rename). */
  public void refresh() {
    if (currentPath != null) navigate(currentPath);
  }

  /** Highlights this panel as active (thick accent border on the table). */
  public void setActive(boolean active) {
    fileTable.getStyleClass().removeAll("file-panel-active");
    if (active) {
      fileTable.getStyleClass().add("file-panel-active");
      fileTable.requestFocus();
    }
  }

  /** Returns the selected entries, excluding the parent-link ".." entry. */
  public List<FileEntry> getSelectedEntries() {
    return fileTable.getSelectionModel().getSelectedItems().stream()
        .map(FileEntryRow::getEntry)
        .filter(e -> !e.isParentLink())
        .collect(Collectors.toList());
  }

  /** Full path of the directory currently displayed in this panel. */
  public String getCurrentPath() {
    return currentPath;
  }

  /**
   * Attaches a right-click context menu to every non-empty table row.
   * Actions are wired to callbacks provided by {@link FileManagerController}.
   * Must be called after {@link #init}.
   */
  public void installContextMenu(
      Runnable copy, Runnable move, Runnable mkdir, Runnable delete, Runnable rename) {
    MenuItem copyItem   = new MenuItem("Copy to other panel");
    MenuItem moveItem   = new MenuItem("Move to other panel");
    MenuItem mkdirItem  = new MenuItem("New directory");
    MenuItem deleteItem = new MenuItem("Delete");
    MenuItem renameItem = new MenuItem("Rename");

    copyItem.setOnAction(e -> copy.run());
    moveItem.setOnAction(e -> move.run());
    mkdirItem.setOnAction(e -> mkdir.run());
    deleteItem.setOnAction(e -> delete.run());
    renameItem.setOnAction(e -> rename.run());

    ContextMenu menu = new ContextMenu(
        copyItem, moveItem,
        new SeparatorMenuItem(),
        mkdirItem,
        new SeparatorMenuItem(),
        deleteItem, renameItem);

    fileTable.setRowFactory(tv -> {
      TableRow<FileEntryRow> row = new TableRow<>();
      row.setOnMousePressed(e -> {
        if (e.getButton() == MouseButton.SECONDARY && !row.isEmpty()) {
          if (onActivated != null) onActivated.run();
          if (!fileTable.getSelectionModel().isSelected(row.getIndex())) {
            fileTable.getSelectionModel().clearAndSelect(row.getIndex());
          }
          boolean hasSelection = !getSelectedEntries().isEmpty();
          boolean singleFile   = getSelectedEntries().size() == 1;
          copyItem.setDisable(!hasSelection);
          moveItem.setDisable(!hasSelection);
          deleteItem.setDisable(!hasSelection);
          renameItem.setDisable(!singleFile);
          menu.show(row, e.getScreenX(), e.getScreenY());
          e.consume();
        } else {
          menu.hide();
        }
      });
      return row;
    });
  }

  /**
   * Sets up drag-and-drop on this panel. Must be called after {@link #init} and
   * {@link #installContextMenu}.
   *
   * <p>Logic: same session instance → move; different sessions → copy.
   */
  public void installDragAndDrop() {
    fileTable.setOnDragDetected(e -> {
      List<FileEntry> sel = getSelectedEntries();
      if (sel.isEmpty()) return;
      FileDragContext.start(sel, session, this);
      Dragboard db = fileTable.startDragAndDrop(TransferMode.COPY_OR_MOVE);
      ClipboardContent cc = new ClipboardContent();
      cc.putString(sel.stream().map(FileEntry::getName).collect(Collectors.joining(", ")));
      db.setContent(cc);
      e.consume();
    });

    fileTable.setOnDragOver(e -> {
      if (FileDragContext.isActive()) {
        e.acceptTransferModes(
            FileDragContext.isSameSession(session) ? TransferMode.MOVE : TransferMode.COPY);
        e.consume();
      }
    });

    fileTable.setOnDragEntered(e -> {
      if (FileDragContext.isActive())
        fileTable.getStyleClass().add("file-panel-drag-target");
    });

    fileTable.setOnDragExited(e ->
        fileTable.getStyleClass().remove("file-panel-drag-target"));

    fileTable.setOnDragDropped(e -> {
      if (!FileDragContext.isActive()) { e.setDropCompleted(false); return; }
      boolean move = FileDragContext.isSameSession(session);
      String destDir = resolveDropDir(e);
      List<FileEntry> dragged = FileDragContext.entries;
      FileSession srcSession = FileDragContext.sourceSession;
      FilePanelController srcPanel = FileDragContext.sourcePanel;
      FileDragContext.clear();
      fileTable.getStyleClass().remove("file-panel-drag-target");
      FileSession destSession = session;
      Thread.ofVirtual().start(() -> {
        for (FileEntry entry : dragged) {
          String destPath = destDir + "/" + entry.getName();
          Throwable[] error = {null};
          new TransferTask(srcSession, entry.getPath(), destSession, destPath)
              .start()
              .blockingSubscribe(
                  p -> {},
                  err -> {
                    log.error("DnD transfer failed: {}", entry.getPath(), err);
                    error[0] = err;
                  },
                  () -> {});
          if (error[0] != null) {
            final Throwable err = error[0];
            Platform.runLater(() ->
                new Alert(Alert.AlertType.ERROR, err.getMessage(), ButtonType.OK).showAndWait());
            return;
          }
          if (move) {
            try { srcSession.delete(entry.getPath(), true); }
            catch (SftpException ex) { log.error("DnD delete failed", ex); }
          }
        }
        Platform.runLater(() -> {
          refresh();
          if (srcPanel != this) srcPanel.refresh();
        });
      });
      e.setDropCompleted(true);
      e.consume();
    });

    fileTable.setOnDragDone(e -> FileDragContext.clear());
  }

  private String resolveDropDir(DragEvent e) {
    Node picked = e.getPickResult().getIntersectedNode();
    while (picked != null) {
      if (picked instanceof TableRow<?> row && !row.isEmpty()
          && row.getItem() instanceof FileEntryRow fer) {
        FileEntry entry = fer.getEntry();
        if (entry.isDirectory() && !entry.isParentLink()) return entry.getPath();
        break;
      }
      picked = picked.getParent();
    }
    return currentPath;
  }

  @FXML
  private void onPathEnter() {
    navigate(pathField.getText().strip());
  }

  private void activateSelected() {
    FileEntryRow row = fileTable.getSelectionModel().getSelectedItem();
    if (row == null) return;
    FileEntry entry = row.getEntry();
    if (entry.isDirectory() || entry.isParentLink()) navigate(entry.getPath());
  }

  private void navigateToParent() {
    if (!items.isEmpty() && items.get(0).getEntry().isParentLink()) {
      navigate(items.get(0).getEntry().getPath());
    }
  }

  private void updateStatus() {
    long count = items.stream().filter(r -> !r.getEntry().isParentLink()).count();
    statusLabel.setText(count + " " + pluralElements(count));
  }

  private String pluralElements(long n) {
    if (n == 1) return "element";
    if (n >= 2 && n <= 4) return "elementy";
    return "elementów";
  }
}
