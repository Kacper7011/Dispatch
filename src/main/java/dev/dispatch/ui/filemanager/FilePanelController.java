package dev.dispatch.ui.filemanager;

import dev.dispatch.sftp.FileEntry;
import dev.dispatch.sftp.FileSession;
import dev.dispatch.sftp.SftpException;
import java.util.List;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for one file-browser panel (left or right).
 * Handles directory navigation, multi-selection, and status display.
 */
public class FilePanelController {

  private static final Logger log = LoggerFactory.getLogger(FilePanelController.class);

  @FXML private Label titleLabel;
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
    navigate(session.home());
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
